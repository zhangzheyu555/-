import { apiGet } from './http'

export interface EmployeeRecord {
  id: string
  storeId: string
  storeCode?: string
  storeName?: string
  brandId?: number
  brandName?: string
  name: string
  phone?: string
  role?: string
  position?: string
  employmentType?: string
  baseSalary?: number
  status?: string
  hireDate?: string
  remark?: string
}

export interface EmployeeQuery {
  brandId?: number
  storeId?: string
  status?: string
}

export function getEmployees(params: EmployeeQuery = {}) {
  const query = new URLSearchParams()
  if (params.brandId !== undefined) query.set('brandId', String(params.brandId))
  if (params.storeId) query.set('storeId', params.storeId)
  if (params.status) query.set('status', params.status)
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return apiGet<EmployeeRecord[]>(`/api/employees${suffix}`)
}
