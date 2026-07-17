import { apiGet } from './http'

export interface EmployeeWorkbenchProfile {
  userId: number
  displayName: string
  role: string
}

export interface EmployeeWorkbenchStore {
  storeId: string
  storeName: string
  brandName?: string
}

export interface EmployeeWorkbenchItem {
  id: string
  type: 'EXAM' | 'ASSISTANT' | string
  title: string
  description: string
  status: string
  priority: 'HIGH' | 'NORMAL' | string
  actionText: string
  route: string
}

export interface EmployeeWorkbenchSummary {
  total: number
  pending: number
  overdue: number
  completed: number
  retakePending: number
}

export interface EmployeeAssistantEntry {
  enabled: boolean
  state: string
  message: string
  route: string
}

export interface EmployeeWorkbench {
  profile: EmployeeWorkbenchProfile
  store: EmployeeWorkbenchStore
  workItems: EmployeeWorkbenchItem[]
  workSummary: EmployeeWorkbenchSummary
  assistant: EmployeeAssistantEntry
}

export interface EmployeeProfileArchive {
  linked: boolean
  employeeId?: string
  name?: string
  position?: string
  employmentType?: string
  status?: string
  hireDate?: string
  baseSalary?: number
  message: string
}

export interface EmployeeProfileSalary {
  available: boolean
  recordId?: string
  month?: string
  status: string
  statusLabel: string
  employeeId?: string
  employeeName?: string
  position?: string
  attendance?: string
  base?: number
  gross?: number
  netPay?: number
  commission?: number
  overtime?: number
  performance?: number
  deductUniform?: number
  returnUniform?: number
  vacationLeft?: number
  vacationNote?: string
  reviewedAt?: string
  paidAt?: string
  message: string
}

export interface EmployeeProfileChecklistItem {
  key: string
  title: string
  description: string
  state: string
  severity: 'HIGH' | 'NORMAL' | 'LOW' | string
}

export interface EmployeeProfile {
  profile: EmployeeWorkbenchProfile & { username: string }
  store: EmployeeWorkbenchStore
  archive: EmployeeProfileArchive
  salary: EmployeeProfileSalary
  checklist: EmployeeProfileChecklistItem[]
}

export function getEmployeeWorkbench() {
  return apiGet<EmployeeWorkbench>('/api/employee/workbench')
}

export function getEmployeeProfile() {
  return apiGet<EmployeeProfile>('/api/employee/profile')
}
