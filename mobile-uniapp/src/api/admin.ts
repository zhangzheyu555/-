import { apiGet, apiPost, apiPut } from './client'
import type { MobileUserAccount } from '@/types/business'

export interface MobileStore { id: string; code: string; name: string; brandId: number; brandName: string; area?: string; manager?: string; openDate?: string; status?: string; note?: string }
export interface MobileUserProfile { displayName: string; role: string; storeId?: string; storeScope: string[]; enabled: boolean }
export interface MobileUserCreate extends MobileUserProfile { username: string; password: string }
export interface PermissionCatalog { permissions: Array<{ permissionCode: string; permissionName: string; moduleCode: string; description: string }>; dataScopeDomains: string[]; dataScopeModes: string[] }
export interface UserAuthorization { dataScopes: Array<{ domainCode: string; mode: string; storeIds: string[]; warehouseIds: string[] }>; overrides: Array<{ permissionCode: string; effect: 'ALLOW'|'DENY' }> }
export function getAdminStores() { return apiGet<MobileStore[]>('/api/stores') }
export function createAdminStore(payload: Partial<MobileStore>) { return apiPost<void, Partial<MobileStore>>('/api/stores', payload) }
export function updateAdminStore(payload: Partial<MobileStore> & { id: string }) { return apiPut<void, typeof payload>('/api/stores', payload) }
export function getAdminUsers() { return apiGet<MobileUserAccount[]>('/api/users') }
export function createAdminUser(payload: MobileUserCreate) { return apiPost<MobileUserAccount, MobileUserCreate>('/api/users', payload) }
export function updateAdminUser(id: number, payload: MobileUserProfile) { return apiPut<MobileUserAccount, MobileUserProfile>(`/api/users/${id}`, payload) }
export function getAuthorizationCatalog() { return apiGet<PermissionCatalog>('/api/users/authorization/catalog') }
export function getUserAuthorization(id: number) { return apiGet<UserAuthorization>(`/api/users/${id}/authorization`) }
export function updateUserAuthorization(id: number, payload: UserAuthorization) { return apiPut<UserAuthorization, UserAuthorization>(`/api/users/${id}/authorization`, payload) }
