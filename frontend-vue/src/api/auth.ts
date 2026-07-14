import { apiGet, apiPost } from './http'

export interface SessionDataScope {
  mode: 'ALL' | 'STORE_LIST' | 'OWN_STORE' | 'NONE' | 'CENTRAL_WAREHOUSE' | 'SELF' | string
  storeIds: string[]
  warehouseIds: string[]
}

export interface SessionUser {
  id: number
  tenantId: number
  tenantName: string
  displayName: string
  role: string
  roleLabel: string
  storeScope: string[]
  permissions: string[]
  dataScopes: Record<string, SessionDataScope>
  dataScope: SessionDataScope
  boundStoreId: string | null
  boundStoreName: string | null
  brandId: number | null
  brandName: string | null
  defaultWorkspace: string
  permissionVersion: number
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

export function currentSessionApi() {
  return apiGet<SessionUser>('/api/auth/me')
}
