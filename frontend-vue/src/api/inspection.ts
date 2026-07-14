import { apiGet, apiPost, http, type ApiResponse } from './http'
import { downloadBlob } from './reports'
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
  maxScore?: number
  passScore?: number
  passed?: boolean
  deductionsJson?: string
  redlinesJson?: string
  photosJson?: string
  note?: string
  standardVersionId?: number
  standardVersion?: string
  materialScore?: number
  hygieneScore?: number
  serviceScore?: number
  resultCode?: InspectionResultCode
  itemResults?: InspectionItemResult[]
  originalFullScore?: number
  originalScore?: number
  originalPassScore?: number
  originalPassed?: boolean
  originalResultCode?: InspectionResultCode
  displayFullScore?: number
  displayScore?: number
  displayPassScore?: number
  displayMaterialScore?: number
  displayHygieneScore?: number
  displayServiceScore?: number
  displayPassed?: boolean
  displayResultCode?: InspectionResultCode
  repairStatus?: InspectionRepairStatus
  repaired?: boolean
  repairedFullScore?: number
  repairedScore?: number
  repairedPassScore?: number
  repairedMaterialScore?: number
  repairedHygieneScore?: number
  repairedServiceScore?: number
  repairedPassed?: boolean
  repairedResultCode?: InspectionResultCode
  repairReason?: string
  originalStandardVersion?: string
  repairAuditId?: number
  repairedAt?: string
  repairedBy?: string | number
  referenceScore200?: number
  redLineCount?: number
  yellowLineCount?: number
}

export type InspectionRiskLevel = 'RED' | 'YELLOW' | 'NORMAL'
export type InspectionResultCode = 'PASSED' | 'FAILED' | 'RED_LINE_FAILED' | 'MANUAL_REVIEW'
export type InspectionCategoryCode = 'MATERIAL' | 'HYGIENE' | 'SERVICE'
export type InspectionRepairStatus = 'NOT_NEEDED' | 'REPAIRED' | 'MANUAL_REVIEW' | 'PENDING_REVIEW' | string

export interface InspectionItemResult {
  standardItemId: number
  code?: string
  dimension?: string
  categoryCode?: InspectionCategoryCode
  categoryName?: string
  title?: string
  description?: string
  checkMethod?: string
  standardScore?: number
  actualScore: number
  deductionReason?: string
  riskLevel?: InspectionRiskLevel
  issueFound?: boolean
  redLineHit?: boolean
  photoAttachmentIds: number[]
  beforePhotoAttachmentIds?: number[]
  afterPhotoAttachmentIds?: number[]
  responsiblePerson?: string
  rectificationDeadline?: string
  rectificationStatus?: string
  reviewResult?: string
}

export interface InspectionRecordPayload {
  storeId: string
  inspectionDate: string
  inspector?: string
  brand?: string
  fullScore?: number
  score?: number
  materialScore?: number
  hygieneScore?: number
  serviceScore?: number
  productScore?: number
  displayScore?: number
  issueDescription?: string
  rectificationRequirement?: string
  deductionsJson?: string
  redlinesJson?: string
  photosJson?: string
  note?: string
  standardVersionId?: number
  itemResults?: InspectionItemResult[]
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
  checkMethod?: string
  suggestedScore: number
  redLine: boolean
  riskLevel?: InspectionRiskLevel
  enabled: boolean
  sortOrder: number
}

export interface InspectionStandardResponse {
  id?: number
  title: string
  fullScore: number
  passScore?: number
  version: string
  effectiveDate?: string
  items: InspectionStandardItem[]
  valid?: boolean
  validFlag?: boolean
  saveAllowed?: boolean
  validationError?: string
  diagnostics?: InspectionStandardDiagnostic[]
  categoryStats?: InspectionStandardCategoryStat[]
  riskStats?: {
    redLineCount?: number
    yellowLineCount?: number
  }
}

export interface InspectionStandardDiagnostic {
  categoryCode?: InspectionCategoryCode
  categoryName?: string
  expectedCount?: number
  actualCount?: number
  expectedScore?: number
  actualScore?: number
  message?: string
}

export interface InspectionStandardCategoryStat {
  categoryCode: InspectionCategoryCode
  categoryName?: string
  expectedCount?: number
  actualCount?: number
  expectedScore?: number
  actualScore?: number
  valid?: boolean
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

export function getInspectionRecords(params: {
  storeId?: string
  dateFrom?: string
  dateTo?: string
  passed?: boolean
} = {}) {
  const query = new URLSearchParams()
  if (params.storeId) query.set('storeId', params.storeId)
  if (params.dateFrom) query.set('dateFrom', params.dateFrom)
  if (params.dateTo) query.set('dateTo', params.dateTo)
  if (params.passed !== undefined) query.set('passed', String(params.passed))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return apiGet<InspectionRecord[]>(`/api/inspections${suffix}`)
}

export function getInspectionRecord(recordId: string) {
  return apiGet<InspectionRecord>(`/api/inspections/${encodeURIComponent(recordId)}`)
}

export interface InspectionDetectionItem {
  class_id?: number
  class_name?: string
  confidence?: number
  source?: string
  on_floor?: boolean
  box_xyxy?: number[]
}

export interface InspectionDetectionResult {
  image_id?: string
  imageId?: string
  filename?: string
  attachmentId?: number
  passed?: boolean
  review_status?: string
  auto_status?: string
  detection_count?: number
  detectionCount?: number
  detections?: InspectionDetectionItem[]
  detection_summary?: string
  annotated_image?: string
  deduction_project?: string
  deduction_content?: string
  deduction_score?: number | string
  detectionKey?: string
  clauseId?: number
  clauseCode?: string
  clauseTitle?: string
  issueCode?: string
  issueName?: string
  scoreScale?: number
  persistedScoreScale?: number
  legacyDeduction?: number
  convertedDeduction200?: number
  standardDeduction?: number
  clauseDeduction?: number
  scaleAdjustmentDeduction?: number
  deductionPolicyVersion?: string
  suggestedDeduction?: number
  finalDeduction?: number
  confirmedDeduction?: number
  confidence?: number
  decisionStatus?: string
  revision?: number
}

export interface InspectionDetectionBindingPayload {
  inspector?: string
  brand?: string
  fullScore?: number
  results: Array<Record<string, unknown>>
  note?: string
}

export interface InspectionDetectionDecisionRequest {
  expectedRevision?: number
}

export interface InspectionDetectionDecisionResponse {
  record: InspectionRecord
  detection: InspectionDetectionResult
  changed: boolean
}

export interface InspectionDraftDetectionConfirmationRequest {
  evidence: Record<string, unknown>
}

export interface InspectionServiceHealth {
  status: 'UP' | 'DOWN' | 'UNCONFIGURED' | string
  configured: boolean
  healthUrl?: string
  detectUrl?: string
  exportUrl?: string
  message: string
  details?: {
    ok?: boolean
    model_path?: string
    has_trained_model?: boolean
  }
}

export function getInspectionStandard() {
  return apiGet<InspectionStandardResponse>('/api/inspection/standards')
}

export function getInspectionServiceHealth() {
  return apiGet<InspectionServiceHealth>('/api/inspections/service-health')
}

export function createInspectionRecord(payload: InspectionRecordPayload) {
  return apiPost<InspectionRecord, InspectionRecordPayload>('/api/inspections', payload)
}

export async function downloadInspectionExcel(recordId: string, fallbackName = '巡检报告.xlsx') {
  const response = await http.get<Blob>(`/api/inspections/${encodeURIComponent(recordId)}/export.xlsx`, {
    responseType: 'blob',
  })
  const disposition = String(response.headers['content-disposition'] || '')
  downloadBlob(response.data, decodeFilename(disposition) || fallbackName)
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

export async function detectInspectionPhoto(file: File) {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResponse<InspectionDetectionResult>>('/api/inspections/detect', form)
  if (!response.data || response.data.success === false || !response.data.data) {
    throw new Error(response.data?.message || '图片识别失败')
  }
  return response.data.data
}

export function bindInspectionDetectionResults(
  recordId: string,
  payload: InspectionDetectionBindingPayload,
) {
  return apiPost<InspectionRecord, InspectionDetectionBindingPayload>(
    `/api/inspections/${encodeURIComponent(recordId)}/detection-results`,
    payload,
  )
}

export function confirmInspectionDetectionSuggestion(
  detectionKey: string,
  evidence: Record<string, unknown>,
) {
  return apiPost<InspectionDetectionResult, InspectionDraftDetectionConfirmationRequest>(
    `/api/inspections/detection-suggestions/${encodeURIComponent(detectionKey)}/confirm`,
    { evidence },
  )
}

export function confirmInspectionDetection(
  recordId: string,
  detectionKey: string,
  expectedRevision?: number,
) {
  const body: InspectionDetectionDecisionRequest = {}
  if (typeof expectedRevision === 'number' && Number.isFinite(expectedRevision)) body.expectedRevision = expectedRevision
  return apiPost<InspectionDetectionDecisionResponse, InspectionDetectionDecisionRequest>(
    `/api/inspections/${encodeURIComponent(recordId)}/detections/${encodeURIComponent(detectionKey)}/confirm`,
    body,
  )
}

export function revokeInspectionDetection(
  recordId: string,
  detectionKey: string,
  expectedRevision?: number,
) {
  const body: InspectionDetectionDecisionRequest = {}
  if (typeof expectedRevision === 'number' && Number.isFinite(expectedRevision)) body.expectedRevision = expectedRevision
  return apiPost<InspectionDetectionDecisionResponse, InspectionDetectionDecisionRequest>(
    `/api/inspections/${encodeURIComponent(recordId)}/detections/${encodeURIComponent(detectionKey)}/revoke`,
    body,
  )
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

function decodeFilename(disposition: string) {
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) return decodeURIComponent(encoded)
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return plain ? decodeURIComponent(plain) : ''
}
