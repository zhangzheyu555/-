import { apiPost } from './http'

export interface SessionUser {
  id: number
  tenantId: number
  tenantName: string
  displayName: string
  role: string
  roleLabel: string
  storeScope: string[]
}

export interface LoginResponse {
  token: string
  user: SessionUser
}

export function loginApi(username: string, password: string) {
  return apiPost<LoginResponse, { username: string; password: string }>('/api/auth/login', {
    username,
    password,
  })
}

export function logoutApi() {
  return apiPost<void, undefined>('/api/auth/logout', undefined, { timeout: 5000 })
}
