import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import router from '../router'
import { clearStoredAuth, getStoredToken } from '../stores/auth'

export interface ApiResponse<T> {
  success: boolean
  message?: string
  code?: string
  data: T
}

export class ApiError extends Error {
  status?: number

  constructor(message: string, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export const http = axios.create({
  baseURL: '/',
  timeout: 15000,
})

const activeProtectedRequests = new Set<AbortController>()
const controllerByRequest = new WeakMap<object, AbortController>()

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
  for (const controller of activeProtectedRequests) {
    controller.abort('用户已退出登录')
  }
  activeProtectedRequests.clear()
}

http.interceptors.request.use((config) => {
  const token = getStoredToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
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

http.interceptors.response.use(
  (response) => {
    releaseProtectedRequest(response.config)
    return response
  },
  (error: AxiosError<unknown>) => {
    releaseProtectedRequest(error.config)
    if (axios.isCancel(error)) {
      return Promise.reject(new ApiError('请求已取消'))
    }
    const status = error.response?.status
    const message = extractResponseMessage(error.response?.data) || error.message || '接口请求失败'
    const requestId = extractRequestId(error.response?.data)

    const logPayload: Record<string, unknown> = {
      method: error.config?.method?.toUpperCase(),
      url: error.config?.url,
      status,
      message,
    }
    if (requestId) logPayload.requestId = requestId
    console.error('[API Error]', logPayload)

    if (status === 401) {
      if (!isHandling401) {
        isHandling401 = true
        clearStoredAuth()

        if (router.currentRoute.value.name !== 'login') {
          const currentPath = router.currentRoute.value.fullPath
          void router.push({ name: 'login', query: { redirect: currentPath } })
        }

        setTimeout(() => {
          isHandling401 = false
        }, 2000)
      }
    }

    return Promise.reject(new ApiError(message, status))
  },
)

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

function unwrap<T>(payload: ApiResponse<T>): T {
  if (!payload || payload.success === false) {
    console.error('[API Business Error]', payload)
    throw new ApiError(payload?.message || '接口返回异常')
  }
  return payload.data
}

export async function apiGet<T>(url: string): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url)
  return unwrap(response.data)
}

export async function apiPost<T, B = unknown>(url: string, body?: B, config?: AxiosRequestConfig): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, body, config)
  return unwrap(response.data)
}

export async function apiPostForm<T>(url: string, body: FormData): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, body)
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
