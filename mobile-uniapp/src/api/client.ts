import { expireSession, readSessionToken } from '@/platform/session'
import { showOperationFailure } from '@/platform/operation-feedback'
import { resolveApiUrl } from '@/platform/runtime'
import { uploadFile } from '@/platform/upload'
import type { ApiEnvelope, QueryParams, RequestOptions, UploadOptions } from '@/types/api'

export class ApiError extends Error {
  status?: number
  code?: string
  requestId?: string

  constructor(message: string, status?: number, code?: string, requestId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.requestId = requestId
  }
}

export function apiGet<T>(path: string, query?: QueryParams, options?: RequestOptions): Promise<T> {
  return request<T>('GET', appendQuery(path, query), undefined, options)
}

export function apiPost<T, B = unknown>(path: string, body?: B, options?: RequestOptions): Promise<T> {
  return request<T>('POST', path, body, options)
}

export function apiPut<T, B = unknown>(path: string, body?: B, options?: RequestOptions): Promise<T> {
  return request<T>('PUT', path, body, options)
}

export function apiDelete<T>(path: string, options?: RequestOptions): Promise<T> {
  return request<T>('DELETE', path, undefined, options)
}

export async function apiUpload<T>(
  path: string,
  filePath: string,
  fieldName = 'file',
  formData?: Record<string, string>,
  options: UploadOptions = {},
): Promise<T> {
  try {
    const response = await uploadFile({
      path,
      filePath,
      fieldName,
      formData: { ...formData, ...options.formData },
      headers: buildHeaders(options),
    }).catch(() => {
      throw new ApiError('上传失败，请检查网络后重试')
    })
    const payload = parsePayload<T>(response.data)
    return unwrap(payload, response.statusCode, options)
  } catch (error) {
    showOperationFailure('操作失败', error)
    throw error
  }
}

function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown,
  options: RequestOptions = {},
): Promise<T> {
  const pending = new Promise<T>((resolve, reject) => {
    let url: string
    try {
      url = resolveApiUrl(path)
    } catch (error) {
      reject(error)
      return
    }

    uni.request({
      url,
      method,
      data: body as UniApp.RequestOptions['data'],
      header: buildHeaders(options),
      timeout: options.timeout || 15_000,
      success: (response) => {
        try {
          resolve(unwrap(response.data as ApiEnvelope<T>, response.statusCode, options))
        } catch (error) {
          reject(error)
        }
      },
      fail: () => reject(new ApiError('网络连接失败，请检查网络后重试')),
    })
  })
  if (method === 'GET') return pending
  return pending.catch((error: unknown) => {
    showOperationFailure('操作失败', error)
    throw error
  })
}

function buildHeaders(options: RequestOptions): Record<string, string> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...options.headers,
  }
  if (!options.skipAuth) {
    const token = readSessionToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey
  return headers
}

function unwrap<T>(payload: ApiEnvelope<T>, status: number, options: RequestOptions): T {
  if (status === 401 && !options.skipAuth) expireSession()
  if (status < 200 || status >= 300 || !payload || payload.success === false) {
    const code = payload?.code || (status === 401 ? 'UNAUTHORIZED' : status === 403 ? 'FORBIDDEN' : '')
    throw new ApiError(normalizeMessage(payload?.message, status), status, code, payload?.requestId)
  }
  return payload.data
}

function parsePayload<T>(raw: string): ApiEnvelope<T> {
  try {
    return JSON.parse(raw) as ApiEnvelope<T>
  } catch {
    throw new ApiError('服务返回内容无法识别，请稍后重试')
  }
}

function normalizeMessage(message: string | undefined, status: number): string {
  if (status === 401) return '登录已失效，请重新登录'
  if (status === 403) return '当前账号没有访问该数据或执行此操作的权限'
  if (status === 404) return '未找到相关数据'
  if (status >= 500) return '系统处理失败，请稍后重试'
  const normalized = String(message || '').trim()
  return normalized || '请求失败，请稍后重试'
}

function appendQuery(path: string, query?: QueryParams): string {
  if (!query) return path
  const pairs: string[] = []
  Object.entries(query).forEach(([key, raw]) => {
    const values = Array.isArray(raw) ? raw : [raw]
    values.forEach((value) => {
      if (value === undefined || value === null || value === '') return
      pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    })
  })
  if (!pairs.length) return path
  return `${path}${path.includes('?') ? '&' : '?'}${pairs.join('&')}`
}
