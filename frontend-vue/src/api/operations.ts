import { apiGet, apiPost, apiPut } from './http'

export interface OperationLog {
  id: number
  operatorId?: number
  operatorName?: string
  action: string
  targetType?: string
  targetId?: string
  storeId?: string
  month?: string
  reason?: string
  createdAt?: string
}

export interface StoreInfo {
  id: string
  code: string
  name: string
  brandId: number
  brandName: string
  area?: string
  manager?: string
  managerPhone?: string
  openDate?: string
  status?: string
  note?: string
  regionCode?: string
  supplyWarehouseId?: number
  supplyWarehouseName?: string
  managerEmployeeId?: string
  costAccountStoreId?: string
  costAccountStoreName?: string
  version?: number
}

export interface BrandInfo {
  id: number
  code: string
  name: string
  color?: string
  sortOrder?: number
}

export interface StorePayload {
  id?: string
  code: string
  name: string
  brandId: number
  managerEmployeeId: string
  managerPhone: string
  openDate?: string
  status: string
  note?: string
  regionCode: string
  costAccountStoreId: string
  version?: number
}

export interface StoreArchiveOptions {
  regions: Array<{
    code: string
    name: string
    supplyWarehouseId: number
  }>
  managers: Array<{
    employeeId: string
    name: string
    phone?: string
    storeId: string
    storeName: string
  }>
  statuses: Array<{
    value: string
    label: string
    active: boolean
  }>
  costAccounts: Array<{
    storeId: string
    storeCode: string
    storeName: string
    status: string
  }>
}

export function getAuditLogs(limit = 80) {
  return apiGet<OperationLog[]>(`/api/audit/logs?limit=${limit}`)
}

export function getStores(options: { knowledgeBaseScope?: boolean } = {}) {
  const query = options.knowledgeBaseScope ? '?knowledgeBaseScope=true' : ''
  return apiGet<StoreInfo[]>(`/api/stores${query}`)
}

export function getBrands() {
  return apiGet<BrandInfo[]>('/api/brands')
}

export function getStoreArchiveOptions() {
  return apiGet<StoreArchiveOptions>('/api/stores/options')
}

export function updateStore(payload: StorePayload) {
  return apiPut<StoreInfo, StorePayload>('/api/stores', payload)
}

export function createStore(payload: StorePayload) {
  return apiPost<StoreInfo, StorePayload>('/api/stores', payload)
}

export function updateStoreStatus(id: string, status: string, version: number) {
  return apiPut<StoreInfo, { status: string; version: number }>(
    `/api/stores/${encodeURIComponent(id)}/status`,
    { status, version },
  )
}
