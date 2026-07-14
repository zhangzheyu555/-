import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import router from '../router'
import { AUTH_SESSION_INVALIDATED_EVENT, clearStoredAuth, getStoredToken } from '../stores/auth'

export interface ApiResponse<T> {
  success: boolean
  message?: string
  code?: string
  requestId?: string
  data: T
}

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

export const http = axios.create({
  baseURL: '/',
  timeout: 15000,
})

const activeProtectedRequests = new Set<AbortController>()
const controllerByRequest = new WeakMap<object, AbortController>()
let protectedRequestsBlocked = false
const PROTECTED_REQUEST_BLOCK_KEY = 'ai_profit_protected_requests_blocked'

function areProtectedRequestsBlocked() {
  return protectedRequestsBlocked || sessionStorage.getItem(PROTECTED_REQUEST_BLOCK_KEY) === '1'
}

function isProtectedApiRequest(url?: string) {
  return Boolean(url?.startsWith('/api/') && !['/api/auth/login', '/api/auth/logout'].includes(url))
}

function releaseProtectedRequest(config?: object) {
  if (!config) return
  const controller = controllerByRequest.get(config)
  if (!controller) return
  activeProtectedRequests.delete(controller)
  controllerByRequest.delete(config)
}

export function cancelProtectedRequests() {
  protectedRequestsBlocked = true
  sessionStorage.setItem(PROTECTED_REQUEST_BLOCK_KEY, '1')
  for (const controller of activeProtectedRequests) {
    controller.abort('用户已退出登录')
  }
  activeProtectedRequests.clear()
}

export function allowProtectedRequests() {
  protectedRequestsBlocked = false
  sessionStorage.removeItem(PROTECTED_REQUEST_BLOCK_KEY)
}

http.interceptors.request.use((config) => {
  const token = getStoredToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  if (isProtectedApiRequest(config.url) && areProtectedRequestsBlocked()) {
    throw new axios.CanceledError('用户已退出登录')
  }
  if (isProtectedApiRequest(config.url) && !config.signal) {
    const controller = new AbortController()
    config.signal = controller.signal
    activeProtectedRequests.add(controller)
    controllerByRequest.set(config, controller)
  }
  return config
})

let isHandling401 = false
const recentApiErrors = new Map<string, number>()

function shouldLogApiError(method: string | undefined, url: string | undefined, status: number | undefined, requestId: string) {
  const key = requestId || `${method || ''}:${url || ''}:${status || 0}`
  const now = Date.now()
  const previous = recentApiErrors.get(key) || 0
  recentApiErrors.set(key, now)
  if (recentApiErrors.size > 100) {
    for (const [entry, at] of recentApiErrors) if (now - at > 10_000) recentApiErrors.delete(entry)
  }
  return now - previous > 2_000
}

http.interceptors.response.use(
  (response) => {
    releaseProtectedRequest(response.config)
    if (isProtectedApiRequest(response.config.url) && areProtectedRequestsBlocked()) {
      return Promise.reject(new ApiError('请求已取消'))
    }
    return response
  },
  async (error: AxiosError<unknown>) => {
    releaseProtectedRequest(error.config)
    if (axios.isCancel(error)) {
      return Promise.reject(new ApiError('请求已取消', undefined, 'REQUEST_CANCELLED'))
    }
    const status = error.response?.status
    // 文件下载使用 responseType: 'blob'。后端返回 JSON 业务错误时，Axios 仍会把
    // 它交给前端一个 Blob；先还原 JSON，才能保留业务错误码和可操作的中文说明。
    const responseData = await decodeBlobErrorPayload(error.response?.data)
    const timedOut = error.code === 'ECONNABORTED' || /timeout\s+of\s+\d+ms\s+exceeded/i.test(error.message || '')
    const rawMessage = timedOut
      ? '文件解析超时，请重试'
      : extractResponseMessage(responseData) || error.message || '接口请求失败'
    const message = normalizeApiMessage(rawMessage, status)
    const requestId = extractRequestId(responseData)
    const code = extractResponseCode(responseData)

    const logPayload: Record<string, unknown> = {
      method: error.config?.method?.toUpperCase(),
      url: error.config?.url,
      status,
      code,
      message: rawMessage,
      data: responseData,
    }
    if (requestId) logPayload.requestId = requestId
    if (shouldLogApiError(error.config?.method, error.config?.url, status, requestId)) {
      console.error('[API Error]', logPayload)
    }

    if (status === 401) {
      if (!isHandling401) {
        isHandling401 = true
        cancelProtectedRequests()
        clearStoredAuth()
        window.dispatchEvent(new Event(AUTH_SESSION_INVALIDATED_EVENT))

        if (router.currentRoute.value.name !== 'login') {
          const currentPath = router.currentRoute.value.fullPath
          void router.push({ name: 'login', query: { redirect: currentPath } })
        }

        setTimeout(() => {
          isHandling401 = false
        }, 2000)
      }
    }

    return Promise.reject(new ApiError(message, status, code, requestId))
  },
)

async function decodeBlobErrorPayload(data: unknown): Promise<unknown> {
  if (typeof Blob === 'undefined' || !(data instanceof Blob)) return data
  if (!data.size || data.size > 1_000_000) return data

  const contentType = data.type.toLowerCase()
  if (contentType && !contentType.includes('json') && !contentType.startsWith('text/')) return data

  try {
    const text = (await data.text()).trim()
    if (!text) return ''
    return JSON.parse(text) as unknown
  } catch {
    return data
  }
}

function extractResponseMessage(data: unknown) {
  if (!data) return ''
  if (typeof data === 'string') return data
  if (typeof data !== 'object') return ''
  const payload = data as Record<string, unknown>
  return String(payload.message || payload.detail || payload.error || '')
}

function extractRequestId(data: unknown) {
  if (!data || typeof data !== 'object') return ''
  const payload = data as Record<string, unknown>
  return String(payload.requestId || '')
}

function extractResponseCode(data: unknown) {
  if (!data || typeof data !== 'object') return ''
  const payload = data as Record<string, unknown>
  return String(payload.code || '')
}

const technicalMessagePatterns = [
  /request failed/i,
  /network error/i,
  /status code/i,
  /handler dispatch failed/i,
  /java\./i,
  /org\.springframework/i,
  /noclassdeffounderror/i,
  /exception/i,
  /stacktrace/i,
  /sqlstate/i,
  /select\s+.+\s+from/i,
  /errorresponse\$builder/i,
]

function normalizeApiMessage(message: string, status?: number) {
  if (status === 401) return '登录已失效，请重新登录'
  if (status === 403) return '当前账号没有访问该数据或执行此操作的权限，如需访问请联系老板调整权限'
  if (status === 404) return '未找到相关数据'
  if (status === 409) return '数据已发生变化，请刷新后重试'
  if (technicalMessagePatterns.some((pattern) => pattern.test(message))) {
    return status && status >= 500 ? '系统处理失败，请稍后重试' : '数据请求失败，请稍后重试'
  }
  return message || '数据请求失败，请稍后重试'
}

function unwrap<T>(payload: ApiResponse<T>): T {
  if (!payload || payload.success === false) {
    const requestId = payload?.requestId || ''
    if (shouldLogApiError('BUSINESS', payload?.code, undefined, requestId)) {
      console.error('[API Business Error]', payload)
    }
    const status = payload?.code === 'FORBIDDEN'
      ? 403
      : payload?.code === 'UNAUTHORIZED' ? 401 : undefined
    throw new ApiError(normalizeApiMessage(payload?.message || '数据处理失败', status), status, payload?.code, payload?.requestId)
  }
  return payload.data
}

export async function apiGet<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, config)
  return unwrap(response.data)
}

export async function apiPost<T, B = unknown>(url: string, body?: B, config?: AxiosRequestConfig): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, body, config)
  return unwrap(response.data)
}

export async function apiPostForm<T>(url: string, body: FormData, config?: AxiosRequestConfig): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, body, config)
  return unwrap(response.data)
}

export async function apiPut<T, B = unknown>(url: string, body?: B): Promise<T> {
  const response = await http.put<ApiResponse<T>>(url, body)
  return unwrap(response.data)
}

export async function apiDelete<T>(url: string): Promise<T> {
  const response = await http.delete<ApiResponse<T>>(url)
  return unwrap(response.data)
}
