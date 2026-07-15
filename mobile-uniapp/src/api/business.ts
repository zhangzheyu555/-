import { apiGet, apiPost, apiPut, apiUpload } from './client'
import type {
  BossExamSummary,
  EmployeeAssistantReply,
  EmployeeAssistantStatus,
  ExamAttempt,
  ExamOverview,
  ExamPaper,
  ExamResult,
  InspectionDetection,
  InspectionRecord,
  InspectionRecordPayload,
  InspectionStandard,
  ProfitDashboard,
  RequisitionCreatePayload,
  RoleTodoResponse,
  StorageAttachment,
  StoreInfo,
  TrainingProgress,
  TrainingVideo,
  WarehouseOverview,
  WarehouseRequisition,
  WarehouseReturn,
  InspectionRectification,
} from '../types/business'

export function getMobileWarehouseOverview() {
  return apiGet<WarehouseOverview>('/api/warehouse/overview')
}

export function getMobileRequisitions() {
  return apiGet<WarehouseRequisition[]>('/api/warehouse/requisitions')
}

export function createMobileRequisition(payload: RequisitionCreatePayload) {
  return apiPost<WarehouseRequisition, RequisitionCreatePayload>('/api/warehouse/requisitions', payload, {
    idempotencyKey: payload.clientRequestId,
  })
}

export function receiveMobileRequisition(requisitionId: string, note?: string) {
  return apiPost<void, { note?: string }>(
    `/api/warehouse/requisitions/${encodeURIComponent(requisitionId)}/receive`,
    { note },
  )
}

export function shipMobileRequisition(requisitionId: string) {
  return apiPost<void>(`/api/warehouse/requisitions/${encodeURIComponent(requisitionId)}/ship`)
}

export function getMobileWarehouseReturns() {
  return apiGet<WarehouseReturn[]>('/api/warehouse/returns')
}

export function receiveMobileWarehouseReturn(returnId: string, note?: string) {
  return apiPost<WarehouseReturn, { note?: string }>(
    `/api/warehouse/returns/${encodeURIComponent(returnId)}/receive`, { note },
  )
}

export function getMobileStores() {
  return apiGet<StoreInfo[]>('/api/stores')
}

export function getSupervisorInspectionTodos() {
  return apiGet<RoleTodoResponse>('/api/supervisor/todos', { includeDone: true, limit: 160 })
}

export function getMobileRoleTodos(role: string) {
  const routes: Record<string, string> = {
    BOSS: '/api/boss/todos',
    FINANCE: '/api/finance/todos',
    OPERATIONS: '/api/operations/todos',
    SUPERVISOR: '/api/supervisor/todos',
    STORE_MANAGER: '/api/store-manager/todos',
    WAREHOUSE: '/api/warehouse/todos',
  }
  const route = routes[String(role || '').trim().toUpperCase()]
  if (!route) return Promise.resolve({ roleName: '', updatedAt: '', items: [] } as RoleTodoResponse)
  return apiGet<RoleTodoResponse>(route, { includeDone: false, limit: 100 })
}

export function getMobileInspectionRecords() {
  return apiGet<InspectionRecord[]>('/api/inspections')
}

export function getMobileInspectionStandard() {
  return apiGet<InspectionStandard>('/api/inspection/standards')
}

export function uploadMobileInspectionAttachment(filePath: string, storeId: string, businessId = 'inspection-draft') {
  return apiUpload<StorageAttachment>('/api/storage/upload', filePath, 'file', {
    businessType: 'INSPECTION_RECORD',
    businessId,
    storeId,
  })
}

export function detectMobileInspectionPhoto(filePath: string) {
  return apiUpload<InspectionDetection>('/api/inspections/detect', filePath)
}

export function confirmMobileInspectionSuggestion(detectionKey: string, evidence: Record<string, unknown>) {
  return apiPost<InspectionDetection, { evidence: Record<string, unknown> }>(
    `/api/inspections/detection-suggestions/${encodeURIComponent(detectionKey)}/confirm`,
    { evidence },
  )
}

export function getMobileInspectionRecord(recordId: string) {
  return apiGet<InspectionRecord>(`/api/inspections/${encodeURIComponent(recordId)}`)
}

export function saveMobileInspectionRecord(recordId: string, payload: InspectionRecordPayload) {
  return apiPut<InspectionRecord, InspectionRecordPayload>(
    `/api/inspections/${encodeURIComponent(recordId)}`,
    payload,
  )
}

export function getMyMobileRectifications() {
  return apiGet<InspectionRectification[]>('/api/inspections/rectifications/mine')
}

export function getMobileRectificationReviews() {
  return apiGet<InspectionRectification[]>('/api/inspections/rectifications/reviews')
}

export function uploadMobileRectificationEvidence(recordId: string, filePath: string) {
  return apiUpload<{ attachmentId: number }>(
    `/api/inspections/${encodeURIComponent(recordId)}/rectification/evidence`, filePath,
  )
}

export function submitMobileRectification(recordId: string, payload: { note: string; attachmentIds: number[] }) {
  return apiPost<InspectionRectification, typeof payload>(
    `/api/inspections/${encodeURIComponent(recordId)}/rectification`, payload,
  )
}

export function reviewMobileRectification(recordId: string, payload: { decision: 'APPROVED' | 'REJECTED'; note: string }) {
  return apiPost<InspectionRectification, typeof payload>(
    `/api/inspections/${encodeURIComponent(recordId)}/rectification/review`, payload,
  )
}

export function getMobileTrainingVideos() {
  return apiGet<TrainingVideo[]>('/api/exam-center/videos')
}

export function reportMobileTrainingProgress(
  videoId: number,
  payload: { positionSeconds: number; durationSeconds: number },
) {
  return apiPost<TrainingProgress, typeof payload>(`/api/exam-center/videos/${videoId}/progress`, payload)
}

export function getMobileExamOverview() {
  return apiGet<ExamOverview>('/api/exam-center/overview')
}

export function getMobileExamPaper(assignmentId: number) {
  return apiGet<ExamPaper>(`/api/exam-center/assignments/${assignmentId}/paper`)
}

export function submitMobileExam(
  assignmentId: number,
  payload: { violated: boolean; answers: Array<{ questionId: number; userAnswer: string }> },
) {
  return apiPost<ExamAttempt, typeof payload>(`/api/exam-center/assignments/${assignmentId}/submit`, payload)
}

export function getMobileExamResults() {
  return apiGet<ExamResult[]>('/api/exam-center/results')
}

export function getMobileEmployeeAssistantStatus() {
  return apiGet<EmployeeAssistantStatus>('/api/employee-assistant/status')
}

export function askMobileEmployeeAssistant(payload: { sessionId: string; message: string }) {
  return apiPost<EmployeeAssistantReply, typeof payload>('/api/employee-assistant/chat', payload)
}

export function getMobileProfitDashboard(month?: string, storeId?: string) {
  return apiGet<ProfitDashboard>('/api/finance/dashboard', {
    month: month || undefined,
    storeId: storeId || undefined,
  })
}

export function getMobileBossExamSummary() {
  return apiGet<BossExamSummary>('/api/boss/exam-summary')
}
