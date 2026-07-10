import { apiGet, apiPost, apiPut } from './http'

export interface UserAccount {
  id: number
  tenantId: number
  tenantName: string
  username: string
  displayName: string
  role: string
  roleLabel: string
  storeId?: string
  enabled: boolean
  storeScope: string[]
}

export interface UserProfilePayload {
  displayName: string
  role: string
  storeId?: string
  storeScope: string[]
  enabled: boolean
}

export interface UserCreatePayload extends UserProfilePayload {
  username: string
  password: string
}

export function getUsers() {
  return apiGet<UserAccount[]>('/api/users')
}

export function createUser(payload: UserCreatePayload) {
  return apiPost<UserAccount, UserCreatePayload>('/api/users', payload)
}

export function updateUser(id: number, payload: UserProfilePayload) {
  return apiPut<UserAccount, UserProfilePayload>(`/api/users/${id}`, payload)
}

export function resetUserPassword(id: number, password: string) {
  return apiPost<void, { password: string }>(`/api/users/${id}/reset-password`, { password })
}
