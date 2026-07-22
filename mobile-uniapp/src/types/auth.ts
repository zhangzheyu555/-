export interface SessionDataScope {
  mode: string
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

export interface LoginRequest {
  username: string
  password: string
  tenantId?: number
}

export interface LoginResponse {
  token: string
  user: SessionUser
}

export interface StoreOption {
  id: string
  code: string
  name: string
  brandId: number
  brandName: string
  area: string
  status: string
  supplyWarehouseId: number | null
  supplyWarehouseName: string | null
}
