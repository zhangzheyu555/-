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
  availableWorkspaces: string[]
  defaultWorkspace: string
  effectivePermissionStatus: string
  effectivePermissionMessage: string
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

export type PermissionRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'
export type PermissionEffect = 'ALLOW' | 'DENY'
export type DataScopeMode = 'ALL' | 'STORE_LIST' | 'WAREHOUSE_LIST' | 'OWN_STORE' | 'NONE' | 'CENTRAL_WAREHOUSE' | 'SELF'

export interface PermissionCatalogEntry {
  permissionCode: string
  moduleCode: string
  permissionName: string
  description: string
  riskLevel: PermissionRiskLevel
  enabled: boolean
  sortOrder: number
}

export interface AuthorizationCatalog {
  permissions: PermissionCatalogEntry[]
  dataScopeDomains: string[]
  dataScopeModes: DataScopeMode[]
}

export interface UserDataScopeAssignment {
  domainCode: string
  mode: DataScopeMode
  storeIds: string[]
  warehouseIds: string[]
}

export interface UserPermissionOverride {
  permissionCode: string
  effect: PermissionEffect
}

export interface UserAuthorization {
  userId: number
  role: string
  storeId: string | null
  permissionVersion: number
  roleTemplatePermissions: string[]
  dataScopes: UserDataScopeAssignment[]
  overrides: UserPermissionOverride[]
  effectivePermissions: string[]
  availableWorkspaces: string[]
  defaultWorkspace: string
  effectivePermissionStatus: string
  effectivePermissionMessage: string
}

export interface UserAuthorizationUpdate {
  overrides: UserPermissionOverride[]
  dataScopes: UserDataScopeAssignment[]
}

export interface UserAccessProfileUpdate extends Omit<UserProfilePayload, 'storeId'> {
  storeId: string | null
  overrides: UserPermissionOverride[]
  dataScopes: UserDataScopeAssignment[]
}

export interface UserAccessProfileResponse {
  user: UserAccount
  authorization: UserAuthorization
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

export function resetUserPassword(id: number, password: string, currentPassword?: string) {
  return apiPost<void, { password: string; currentPassword?: string }>(`/api/users/${id}/reset-password`, {
    password,
    currentPassword,
  })
}

export function getAuthorizationCatalog() {
  return apiGet<AuthorizationCatalog>('/api/users/authorization/catalog')
}

export function getUserAuthorization(id: number) {
  return apiGet<UserAuthorization>(`/api/users/${id}/authorization`)
}

export function updateUserAuthorization(id: number, payload: UserAuthorizationUpdate) {
  return apiPut<UserAuthorization, UserAuthorizationUpdate>(`/api/users/${id}/authorization`, payload)
}

export function updateUserAccessProfile(id: number, payload: UserAccessProfileUpdate) {
  return apiPut<UserAccessProfileResponse, UserAccessProfileUpdate>(`/api/users/${id}/access-profile`, payload)
}
