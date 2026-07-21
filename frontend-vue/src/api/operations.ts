import { apiDelete, apiGet, apiPost, apiPut } from './http'

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
  openDate?: string
  status?: string
  note?: string
  regionCode?: string
  supplyWarehouseId?: number
  supplyWarehouseName?: string
}

export interface BrandInfo {
  id: number
  code: string
  name: string
  color?: string
  sortOrder?: number
}

export interface StorePayload {
  id: string
  code?: string
  name: string
  brandId: number
  area?: string
  manager?: string
  openDate?: string
  status?: string
  note?: string
  regionCode?: string
}

export function getAuditLogs(limit = 80) {
  return apiGet<OperationLog[]>(`/api/audit/logs?limit=${limit}`)
}

export function getStores() {
  return apiGet<StoreInfo[]>('/api/stores')
}

export function getBrands() {
  return apiGet<BrandInfo[]>('/api/brands')
}

export function updateStore(payload: StorePayload) {
  return apiPut<void, StorePayload>('/api/stores', payload)
}

export function createStore(payload: StorePayload) {
  return apiPost<void, StorePayload>('/api/stores', payload)
}

export function deleteStore(id: string) {
  return apiDelete<void>(`/api/stores/${encodeURIComponent(id)}`)
}
