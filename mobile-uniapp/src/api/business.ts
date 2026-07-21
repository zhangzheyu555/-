import { apiDelete, apiGet, apiPost, apiPut, apiUpload } from './client'
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
  ExpenseClaim, SalaryRecord, DailyLossItem, DailyLossRecord, OperationLog,
  WarehouseFacility, WarehousePurchaseOrder, WarehouseTransfer, WarehouseTransferContext,
  WarehouseStockMovement, WarehouseItem, WarehouseItemCategory,
  StoreManagerWorkbench,
  FinanceWorkbench,
  InspectionEvidenceCandidates, InspectionDetectionDecision,
  InventoryCheck, InventoryCheckLine,
  WrongQuestion, TrainingProgressReport, EmployeeAssistantHandoff, PlatformAdapterStatus,
  BossTodoDashboard, MobileUserAccount, EmployeeSelfProfile,
  ProfitEntry,
} from '../types/business'

export function getMobileWarehouseOverview(warehouseId?: number | string) {
  return apiGet<WarehouseOverview>('/api/warehouse/overview', { warehouseId })
}
export function getMobileWarehouses(){return apiGet<WarehouseFacility[]>('/api/warehouse/warehouses')}
export function getMobileWarehouseTransfers(warehouseId?:number|string){return apiGet<WarehouseTransfer[]>('/api/warehouse/transfers',{warehouseId})}
export function getMobileWarehouseTransferContext(warehouseId:number|string){return apiGet<WarehouseTransferContext>('/api/warehouse/transfers/context',{warehouseId})}
export function createMobileWarehouseTransfer(payload:{sourceWarehouseId:number|string;targetWarehouseId:number|string;lines:Array<{itemId:number;quantity:number;note?:string}>;note?:string;clientRequestId:string}){return apiPost<WarehouseTransfer,typeof payload>('/api/warehouse/transfers',payload,{idempotencyKey:payload.clientRequestId})}
export function submitMobileWarehouseTransfer(id:string){return apiPost<WarehouseTransfer>(`/api/warehouse/transfers/${encodeURIComponent(id)}/submit`)}
export function reviewMobileWarehouseTransfer(id:string,approved:boolean,note?:string){return apiPost<WarehouseTransfer,{approved:boolean;note?:string}>(`/api/warehouse/transfers/${encodeURIComponent(id)}/review`,{approved,note})}
export function shipMobileWarehouseTransfer(id:string,clientRequestId:string){return apiPost<WarehouseTransfer,{clientRequestId:string}>(`/api/warehouse/transfers/${encodeURIComponent(id)}/ship`,{clientRequestId},{idempotencyKey:clientRequestId})}
export function receiveMobileWarehouseTransfer(id:string,clientRequestId:string,lines:Array<{itemId:number;receivedQuantity:number}>=[],note?:string){return apiPost<WarehouseTransfer,{clientRequestId:string;note?:string;lines:Array<{itemId:number;receivedQuantity:number}>}>(`/api/warehouse/transfers/${encodeURIComponent(id)}/receive`,{clientRequestId,note,lines},{idempotencyKey:clientRequestId})}
export function cancelMobileWarehouseTransfer(id:string,clientRequestId:string,note?:string){return apiPost<WarehouseTransfer,{clientRequestId:string;note?:string}>(`/api/warehouse/transfers/${encodeURIComponent(id)}/cancel`,{clientRequestId,note},{idempotencyKey:clientRequestId})}
export function createMobilePurchaseOrder(payload:{supplierId?:number;warehouseId:number|string;note?:string;clientRequestId:string;lines:Array<{itemId:number;orderedQuantity:number;unitCost:number}>}){return apiPost<WarehousePurchaseOrder,typeof payload>('/api/warehouse/purchase-orders',payload,{idempotencyKey:payload.clientRequestId})}
export function approveMobilePurchaseOrder(id:string){return apiPost<WarehousePurchaseOrder>(`/api/warehouse/purchase-orders/${encodeURIComponent(id)}/approve`)}
export function receiveMobilePurchaseOrder(id:string,payload:{clientRequestId:string;note?:string;lines:Array<{itemId:number;batchNo:string;receivedDate:string;expiryDate?:string;quantity:number;note?:string}>}){return apiPost<WarehousePurchaseOrder,typeof payload>(`/api/warehouse/purchase-orders/${encodeURIComponent(id)}/receive`,payload,{idempotencyKey:payload.clientRequestId})}
export function updateMobileWarehouseAlert(itemId:number,payload:{warehouseId:number|string;minStockQuantity:number;alertEnabled:boolean;expiryAlertDays?:number}){return apiPost<void,typeof payload>(`/api/warehouse/items/${itemId}/alert-settings`,payload)}
export function getMobileWarehouseItems(){return apiGet<WarehouseItem[]>('/api/warehouse/items')}
export function saveMobileWarehouseItem(payload:{id?:number;code:string;name:string;categoryId:number;unit?:string;spec?:string;warehouseLocation?:string;minStockQuantity?:number;alertEnabled?:boolean;expiryAlertDays?:number;active?:boolean}){return apiPost<void,typeof payload>('/api/warehouse/items',payload)}
export function setMobileWarehouseItemEnabled(itemId:number,enabled:boolean){return apiPost<void,{enabled:boolean}>(`/api/warehouse/items/${itemId}/enabled`,{enabled})}
export function getMobileWarehouseItemCategories(){return apiGet<WarehouseItemCategory[]>('/api/warehouse/item-categories')}
export function saveMobileWarehouseItemCategory(payload:{id?:number;name:string;parentId?:number;sortOrder?:number;enabled?:boolean}){return apiPost<WarehouseItemCategory,typeof payload>('/api/warehouse/item-categories',payload)}
export function setMobileWarehouseItemCategoryEnabled(categoryId:number,enabled:boolean){return apiPost<void,{enabled:boolean}>(`/api/warehouse/item-categories/${categoryId}/enabled`,{enabled})}
export function deleteMobileWarehouseItemCategory(categoryId:number){return apiDelete<void>(`/api/warehouse/item-categories/${categoryId}`)}

export function getMobileRequisitions() {
  return apiGet<WarehouseRequisition[]>('/api/warehouse/requisitions')
}

export function getMobileWarehouseMovements() {
  return apiGet<WarehouseStockMovement[]>('/api/warehouse/movements')
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
export function reviewMobileRequisition(requisitionId: string, approved: boolean, note?: string, lines: Array<{ itemId: number; approvedQuantity: number }> = []) {
  return apiPost<void, { approved: boolean; note?: string; lines: Array<{ itemId: number; approvedQuantity: number }> }>(
    `/api/warehouse/requisitions/${encodeURIComponent(requisitionId)}/review`,
    { approved, note, lines },
  )
}

export function getMobileWarehouseReturns() {
  return apiGet<WarehouseReturn[]>('/api/warehouse/returns')
}

export function receiveMobileWarehouseReturn(returnId: string, note?: string) {
  return apiPost<WarehouseReturn, { note?: string }>(
    `/api/warehouse/returns/${encodeURIComponent(returnId)}/receive`, { note },
  )
}
export function reviewMobileWarehouseReturn(returnId: string, approved: boolean, note?: string) {
  return apiPost<WarehouseReturn, { approved: boolean; note?: string }>(`/api/warehouse/returns/${encodeURIComponent(returnId)}/review`, { approved, note })
}

export function getMobileStores() {
  return apiGet<StoreInfo[]>('/api/stores')
}

export function getSupervisorInspectionTodos() {
  return apiGet<RoleTodoResponse>('/api/supervisor/todos', { includeDone: true, limit: 160 })
}

export function getMobileRoleTodos(role: string, includeDone = false) {
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
  return apiGet<RoleTodoResponse>(route, { includeDone, limit: includeDone ? 160 : 100 })
}
export interface MobileTodoAttachment { fileName: string; contentType: string; dataBase64: string }
export function resolveMobileRoleTodo(role: string, todoId: string, note: string, attachments: MobileTodoAttachment[] = []) {
  const audience = todoAudience(role)
  if (!audience) return Promise.reject(new Error('当前角色没有待办处理通道'))
  return apiPost<unknown, { note: string; attachments: MobileTodoAttachment[] }>(`/api/${audience}/todos/${encodeURIComponent(todoId)}/resolve`, { note, attachments })
}
export function escalateMobileRoleTodo(role: string, todoId: string, reason: string) {
  const audience = todoAudience(role)
  if (!audience || audience === 'boss') return Promise.reject(new Error('当前待办不能继续升级'))
  return apiPost<unknown, { reason: string; severity: string }>(`/api/${audience}/todos/${encodeURIComponent(todoId)}/escalate`, { reason, severity: 'RISK' })
}
export function closeMobileBossTodo(todoId: string, note: string) { return apiPost<unknown, { note: string; attachments: unknown[] }>(`/api/boss/todos/${encodeURIComponent(todoId)}/close`, { note, attachments: [] }) }
function todoAudience(role: string) {
  return ({ BOSS: 'boss', FINANCE: 'finance', OPERATIONS: 'operations', SUPERVISOR: 'supervisor', STORE_MANAGER: 'store-manager', WAREHOUSE: 'warehouse' } as Record<string, string>)[String(role || '').trim().toUpperCase()] || ''
}

export function getMobileInspectionRecords(query:{dateFrom?:string;dateTo?:string;storeId?:string;passed?:boolean}={}) {
  return apiGet<InspectionRecord[]>('/api/inspections',query)
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

export function getMobileInspectionEvidence(recordId:string){return apiGet<InspectionEvidenceCandidates>(`/api/inspections/${encodeURIComponent(recordId)}/evidence/attachments`)}
export function revokeMobileInspectionDetection(recordId:string,detectionKey:string,expectedRevision?:number){return apiPost<InspectionDetectionDecision,{expectedRevision?:number}>(`/api/inspections/${encodeURIComponent(recordId)}/detections/${encodeURIComponent(detectionKey)}/revoke`,{expectedRevision})}
export function adjustMobileInspectionDetection(recordId:string,detectionKey:string,targetClauseId:number,reason:string){return apiPost<InspectionDetectionDecision,{targetClauseId:number;reason:string}>(`/api/inspections/${encodeURIComponent(recordId)}/detections/${encodeURIComponent(detectionKey)}/manual-adjust`,{targetClauseId,reason})}

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

export function getMobileWrongQuestions() {
  return apiGet<WrongQuestion[]>('/api/exam-center/wrong-questions')
}

export function markMobileWrongQuestionMastered(id: number, mastered: boolean) {
  return apiPost<{ mastered: boolean }, { mastered: boolean }>(`/api/exam-center/wrong-questions/${id}/mastered`, { mastered })
}

export function getMobileTrainingProgressReport() {
  return apiGet<TrainingProgressReport[]>('/api/exam-center/videos/progress-report')
}

export function getMobileEmployeeAssistantStatus() {
  return apiGet<EmployeeAssistantStatus>('/api/employee-assistant/status')
}

export function askMobileEmployeeAssistant(payload: { sessionId: string; message: string }) {
  return apiPost<EmployeeAssistantReply, typeof payload>('/api/employee-assistant/chat', payload)
}

export function getMyMobileEmployeeAssistantHandoffs() {
  return apiGet<EmployeeAssistantHandoff[]>('/api/employee-assistant/handoffs/mine')
}

export function createMobileEmployeeAssistantHandoff(payload: { question: string; storeId?: string }) {
  return apiPost<EmployeeAssistantHandoff, typeof payload>('/api/employee-assistant/handoffs', payload)
}

export function getManagedMobileEmployeeAssistantHandoffs() {
  return apiGet<EmployeeAssistantHandoff[]>('/api/employee-assistant/handoffs/manage')
}

export function claimMobileEmployeeAssistantHandoff(id: string) {
  return apiPost<EmployeeAssistantHandoff>(`/api/employee-assistant/handoffs/${encodeURIComponent(id)}/claim`)
}

export function replyMobileEmployeeAssistantHandoff(id: string, resolution: string) {
  return apiPost<EmployeeAssistantHandoff, { resolution: string }>(`/api/employee-assistant/handoffs/${encodeURIComponent(id)}/reply`, { resolution })
}

export function closeMobileEmployeeAssistantHandoff(id: string, resolution: string) {
  return apiPost<EmployeeAssistantHandoff, { resolution: string }>(`/api/employee-assistant/handoffs/${encodeURIComponent(id)}/close`, { resolution })
}

export function sendMobileEmployeeAssistantFeedback(payload: { answerSource: string; helpful: boolean; reasonCode?: string }) {
  return apiPost<void, typeof payload>('/api/employee-assistant/feedback', payload)
}

export function getMobilePlatformStatuses() {
  return apiGet<PlatformAdapterStatus[]>('/api/platforms/status')
}

export function getMobileProfitDashboard(month?: string, storeId?: string) {
  return apiGet<ProfitDashboard>('/api/finance/dashboard', {
    month: month || undefined,
    storeId: storeId || undefined,
  })
}
export function getMobileFinanceMonths() { return apiGet<string[]>('/api/finance/months') }
export function getMobileProfitEntries(query: { month?: string; storeId?: string } = {}) {
  return apiGet<ProfitEntry[]>('/api/finance/entries', query)
}

export function getMobileStoreManagerWorkbench() {
  return apiGet<StoreManagerWorkbench>('/api/store-manager/workbench')
}

export function getMobileFinanceWorkbench(query:{month?:string;storeId?:string}={}) {
  return apiGet<FinanceWorkbench>('/api/finance/workbench',query)
}

export function completeMobileFinanceCheck(todoId:string,note:string) {
  return apiPost<unknown,{note:string;attachments:unknown[]}>(`/api/finance/todos/${encodeURIComponent(todoId)}/complete`,{note,attachments:[]})
}

export function getMobileBossExamSummary() {
  return apiGet<BossExamSummary>('/api/boss/exam-summary')
}

export function getMobileBossTodoDashboard() {
  return apiGet<BossTodoDashboard>('/api/boss/todo-dashboard', { includeDone: true, limit: 160 })
}

export function getMobileUserAccounts() {
  return apiGet<MobileUserAccount[]>('/api/users')
}

export function getMobileExpenses(query: { month?: string; storeId?: string; status?: string } = {}) { return apiGet<ExpenseClaim[]>('/api/expenses', query) }
export function createMobileExpense(payload: { storeId: string; month: string; amount: number; category?: string; reason?: string }) { return apiPost<ExpenseClaim, typeof payload>('/api/expenses', payload) }
export function updateMobileExpense(id: string, payload: { storeId: string; month: string; amount: number; category?: string; reason?: string }) { return apiPut<ExpenseClaim, typeof payload>(`/api/expenses/${encodeURIComponent(id)}`, payload) }
export function submitMobileExpense(id: string) { return apiPost<ExpenseClaim>(`/api/expenses/${encodeURIComponent(id)}/submit`) }
export function reviewMobileExpense(id: string, action: 'approve' | 'reject' | 'request-info', note?: string) { return apiPost<ExpenseClaim, { note?: string }>(`/api/expenses/${encodeURIComponent(id)}/${action}`, { note }) }
export function uploadMobileExpenseSupplement(id: string, filePath: string, note: string) { return apiUpload<ExpenseClaim>(`/api/expenses/${encodeURIComponent(id)}/supplements`, filePath, 'files', { note }) }
export function getMobileSalaries(query: { month?: string; storeId?: string } = {}) { return apiGet<SalaryRecord[]>('/api/salaries', query) }
export function getMobileSalary(id:string){return apiGet<SalaryRecord>(`/api/salaries/${encodeURIComponent(id)}`)}
/** 员工本人档案及最近一笔工资；后端仅允许已授权的 EMPLOYEE 账号访问。 */
export function getMobileEmployeeProfile() { return apiGet<EmployeeSelfProfile>('/api/employee/profile') }
export function reviewMobileSalary(id: string, action: 'approve' | 'reject' | 'mark-paid', note?: string) { return apiPost<SalaryRecord, { note?: string }>(`/api/salaries/${encodeURIComponent(id)}/${action}`, note ? { note } : undefined) }
export function getMobileDailyLossItems() { return apiGet<DailyLossItem[]>('/api/daily-loss/items') }
export function getMobileDailyLossRecords(query: { storeId?: string; date?: string; status?: string } = {}) { return apiGet<DailyLossRecord[]>('/api/daily-loss/records', query) }
export function createMobileDailyLoss(payload: { storeId: string; lossDate: string; itemId: number; lossQuantity: number; lossReason: string }) { return apiPost<DailyLossRecord, typeof payload>('/api/daily-loss/records', payload) }
export function submitMobileDailyLoss(id: string) { return apiPost<DailyLossRecord>(`/api/daily-loss/records/${encodeURIComponent(id)}/submit`) }
export function uploadMobileDailyLossAttachment(id: string, filePath: string) { return apiUpload<DailyLossRecord>(`/api/daily-loss/records/${encodeURIComponent(id)}/attachments`, filePath, 'files') }
export function approveMobileDailyLoss(id: string, reviewNote?: string) { return apiPost<DailyLossRecord, { reviewNote?: string }>(`/api/daily-loss/records/${encodeURIComponent(id)}/approve`, { reviewNote }) }
export function getMobileAuditLogs(limit = 80) { return apiGet<OperationLog[]>('/api/audit/logs', { limit }) }
export function getMobileInventoryChecks() { return apiGet<InventoryCheck[]>('/api/operations/inventory-checks') }
export function saveMobileInventoryCheck(payload:{id?:number;storeId:string;checkDate:string;note?:string;lines:InventoryCheckLine[]}){return apiPost<InventoryCheck,typeof payload>('/api/operations/inventory-checks',payload)}
export function submitMobileInventoryCheck(id:number){return apiPost<InventoryCheck>(`/api/operations/inventory-checks/${id}/submit`)}
export function reviewMobileInventoryCheck(id: number) { return apiPost<InventoryCheck>(`/api/operations/inventory-checks/${id}/review`) }
export function cancelMobileInventoryCheck(id:number){return apiPost<InventoryCheck>(`/api/operations/inventory-checks/${id}/cancel`)}
export function getMobileBusinessAssistantStatus() { return apiGet<{ enabled: boolean; configured: boolean; state?: string }>('/api/assistant/status') }
export function askMobileBusinessAssistant(payload: { message: string; storeId?: string; month?: string }) { return apiPost<{ localData?: { summary?: string }; aiAnalysis?: { summary?: string; findings?: string[]; actions?: Array<{ action: string }> }; error?: { message?: string } }, typeof payload>('/api/assistant/chat', payload, { timeout: 90_000 }) }
