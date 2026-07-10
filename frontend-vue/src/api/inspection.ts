import { apiGet, apiPost, http, type ApiResponse } from './http'
import type { RoleTodoItem } from './todos'

export interface InspectionRecord {
  id: string
  storeId: string
  storeCode?: string
  storeName?: string
  brandId?: number
  brandName?: string
  inspectionDate: string
  inspector?: string
  brand?: string
  fullScore?: number
  score?: number
  passed: boolean
  deductionsJson?: string
  redlinesJson?: string
  photosJson?: string
  note?: string
}

export interface InspectionRecordPayload {
  storeId: string
  inspectionDate: string
  inspector?: string
  brand?: string
  fullScore?: number
  score?: number
  hygieneScore?: number
  serviceScore?: number
  productScore?: number
  displayScore?: number
  issueDescription?: string
  rectificationRequirement?: string
  passed?: boolean
  deductionsJson?: string
  redlinesJson?: string
  photosJson?: string
  note?: string
}

export interface InspectionAttachment {
  id?: number
  fileName: string
  contentType?: string
  fileSize?: number
  url?: string
  storagePath?: string
}

export interface InspectionSummary {
  todayTaskCount: number
  reviewCount: number
  issueCount: number
  escalatedCount: number
  headline: string
}

export interface InspectionStandardItem {
  id: number
  dimension: string
  code?: string
  title: string
  description?: string
  suggestedScore: number
  redLine: boolean
  enabled: boolean
  sortOrder: number
}

export interface InspectionStandardResponse {
  title: string
  fullScore: number
  version: string
  effectiveDate?: string
  items: InspectionStandardItem[]
}

export interface InspectionWorkbench {
  summary: InspectionSummary
  todoItems: RoleTodoItem[]
  tasks: RoleTodoItem[]
  records: InspectionRecord[]
  issues: RoleTodoItem[]
  reviews: RoleTodoItem[]
  escalatedItems: RoleTodoItem[]
  doneItems: RoleTodoItem[]
}

export function getInspectionRecords() {
  return apiGet<InspectionRecord[]>('/api/inspections')
}

export function getInspectionStandard() {
  return apiGet<InspectionStandardResponse>('/api/inspection/standards')
}

export function createInspectionRecord(payload: InspectionRecordPayload) {
  return apiPost<InspectionRecord, InspectionRecordPayload>('/api/inspections', payload)
}

export async function uploadInspectionAttachment(file: File, storeId: string, businessId = 'inspection-draft') {
  const form = new FormData()
  form.append('file', file)
  form.append('businessType', 'INSPECTION_RECORD')
  form.append('businessId', businessId || 'inspection-draft')
  form.append('storeId', storeId)
  const response = await http.post<ApiResponse<InspectionAttachment>>('/api/storage/upload', form)
  if (!response.data || response.data.success === false) {
    throw new Error(response.data?.message || '附件上传失败')
  }
  return response.data.data
}

export function getSupervisorTodos() {
  return apiGet<{ items: RoleTodoItem[] }>('/api/supervisor/todos?includeDone=true&limit=160')
}

export function resolveSupervisorTodo(todoId: string, note: string) {
  return apiPost<unknown, { note: string; attachments: unknown[] }>(
    `/api/supervisor/todos/${encodeURIComponent(todoId)}/resolve`,
    { note, attachments: [] },
  )
}

export function escalateSupervisorTodo(todoId: string, reason: string, severity = 'RISK') {
  return apiPost<unknown, { reason: string; severity: string }>(
    `/api/supervisor/todos/${encodeURIComponent(todoId)}/escalate`,
    { reason, severity },
  )
}
