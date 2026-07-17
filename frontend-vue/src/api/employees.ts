import { apiDelete, apiGet, apiPost, apiPostForm, apiPut } from './http'

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
  birthday?: string
  idCardNo?: string
  healthCertIssueDate?: string
  healthCertExpireDate?: string
  contractSignText?: string
  regularDate?: string
  trainerDate?: string
  shiftLeaderDate?: string
  managerDate?: string
  authUserId?: number
  accountUsername?: string
  accountEnabled?: boolean
}

export interface EmployeeQuery {
  brandId?: number
  storeId?: string
  status?: string
}

export interface EmployeeUpsert {
  storeId: string
  name: string
  phone?: string
  position?: string
  employmentType?: string
  status?: string
  hireDate?: string
  birthday?: string
  idCardNo?: string
  healthCertIssueDate?: string
  healthCertExpireDate?: string
  contractSignText?: string
  regularDate?: string
  trainerDate?: string
  shiftLeaderDate?: string
  managerDate?: string
  remark?: string
}

export interface EmployeeAccountResult {
  employeeId: string
  employeeName: string
  username: string
  initialPassword: string
}

export interface EmployeeImportReport {
  created: number
  updated: number
  skipped: number
  createdStores: string[]
  problems: string[]
}

export function getEmployees(params: EmployeeQuery = {}) {
  const query = new URLSearchParams()
  if (params.brandId !== undefined) query.set('brandId', String(params.brandId))
  if (params.storeId) query.set('storeId', params.storeId)
  if (params.status) query.set('status', params.status)
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return apiGet<EmployeeRecord[]>(`/api/employees${suffix}`)
}

export function getMyEmployeeProfile() {
  return apiGet<EmployeeRecord>('/api/employees/me')
}

export function createEmployee(body: EmployeeUpsert) {
  return apiPost<EmployeeRecord, EmployeeUpsert>('/api/employees', body)
}

export function updateEmployee(id: string, body: EmployeeUpsert) {
  return apiPut<EmployeeRecord, EmployeeUpsert>(`/api/employees/${id}`, body)
}

export function removeEmployee(id: string) {
  return apiDelete<void>(`/api/employees/${id}`)
}

export function createEmployeeAccount(id: string) {
  return apiPost<EmployeeAccountResult>(`/api/employees/${id}/account`)
}

export function importEmployees(file: File) {
  const form = new FormData()
  form.append('file', file)
  return apiPostForm<EmployeeImportReport>('/api/employees/import', form)
}
