import { apiGet, apiPost, apiPostForm, http, type ApiResponse } from './http'
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
  /** fullScore/score/passScore 均为 200 分制展示值，后端已统一归一化。 */
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
  snapshotId?: number
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
  /** 后端快照的权威实际扣分；缺失时页面才按标准分减实得分兼容旧记录。 */
  deductionScore?: number
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

/**
 * Historical evidence candidates are deliberately identified by attachmentId only.
 * File names are display metadata, never a signal that permits an association.
 */
export type InspectionEvidenceCandidateStatus = 'UNLINKED' | 'LINKED' | 'MISSING' | 'ORIGINAL_NOT_STORED' | 'INVALID_TYPE' | string

export interface InspectionEvidenceCandidate {
  attachmentId?: number
  /** Server-validated photosJson array position; never derive this from a file name. */
  photoIndex?: number
  fileName?: string
  contentType?: string
  status: InspectionEvidenceCandidateStatus
  message?: string
  linkedClauseIds: number[]
}

export interface InspectionEvidenceAttachmentsResponse {
  recordId: string
  storeId?: string
  candidates: InspectionEvidenceCandidate[]
}

export interface InspectionEvidenceLinkRequest {
  attachmentIds: number[]
  /** Standard item ids (standardItemId); mutually non-overlapping with historicalSnapshotIds. */
  clauseIds: number[]
  /** Snapshot row ids (snapshotId); required when standard_id is NULL in inspection_record_standard_snapshot. */
  historicalSnapshotIds?: number[]
}

export interface InspectionEvidenceLinkResponse {
  recordId: string
  attachmentIds: number[]
  clauseIds: number[]
  /** `ASSOCIATE` or `SUPPLEMENT`; rendered as business copy by the caller. */
  action?: string
  record: InspectionRecord
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

/**
 * 通过受认证、门店数据范围校验的附件接口读取巡检现场图片。
 * 调用方只使用返回的 Blob/ObjectURL，绝不能把 storagePath 或静态路径暴露给页面。
 */
export async function fetchInspectionAttachment(attachmentId: number, signal?: AbortSignal) {
  const response = await http.get<Blob>(`/api/storage/attachments/${encodeURIComponent(String(attachmentId))}`, {
    responseType: 'blob',
    timeout: 120_000,
    signal,
  })
  return response.data
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

/**
 * Reads only the backend-authorized evidence candidates for one historical record.
 * Do not derive candidates from file names or browser state.
 */
export function getInspectionEvidenceAttachments(recordId: string) {
  return apiGet<InspectionEvidenceAttachmentsResponse>(
    `/api/inspections/${encodeURIComponent(recordId)}/evidence/attachments`,
  )
}

/** Writes only attachment-to-snapshot-clause evidence associations. */
export function linkInspectionEvidence(recordId: string, payload: InspectionEvidenceLinkRequest) {
  return apiPost<InspectionEvidenceLinkResponse, InspectionEvidenceLinkRequest>(
    `/api/inspections/${encodeURIComponent(recordId)}/evidence/link`,
    payload,
  )
}

/**
 * Uploads an original image for a historical record and associates it in the same
 * backend operation.  At least one of {@code clauseIds} (standardItemId) or
 * {@code historicalSnapshotIds} (snapshot row id) must be non-empty.
 */
export async function uploadAndLinkInspectionEvidence(
  recordId: string,
  file: File,
  clauseIds: number[],
  historicalSnapshotIds: number[],
  sourcePhotoIndex?: number,
) {
  const form = new FormData()
  form.append('file', file)
  clauseIds.forEach((clauseId) => form.append('clauseIds', String(clauseId)))
  historicalSnapshotIds.forEach((snapshotId) => form.append('historicalSnapshotIds', String(snapshotId)))
  // The server uses this original photosJson position to replace a metadata-only
  // entry. It is intentionally optional for a brand-new historical upload.
  if (Number.isInteger(sourcePhotoIndex) && Number(sourcePhotoIndex) >= 0) {
    form.append('sourcePhotoIndex', String(sourcePhotoIndex))
  }
  return apiPostForm<InspectionEvidenceLinkResponse>(
    `/api/inspections/${encodeURIComponent(recordId)}/evidence/upload`,
    form,
  )
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
