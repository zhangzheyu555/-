import { apiGet, apiPost, http } from './http'

export interface RoleTodoAction {
  target?: string
  label?: string
  params?: Record<string, unknown>
  targetRoute?: string
  sourcePage?: string
}

export interface RoleTodoItem {
  id: string
  title: string
  summary: string
  status: string
  priority: number
  brandName?: string
  storeId?: string
  storeName?: string
  month?: string
  ownerName?: string
  dueAt?: string
  sourceModule?: string
  sourceRecordId?: string
  processStatus?: string
  escalatedToBoss?: boolean
  dataSource?: string
  updatedAt?: string
  occurredAt?: string
  action?: RoleTodoAction
}

export interface RoleTodoResponse {
  roleName: string
  dataSource: string
  updatedAt: string
  stats: Array<{ status: string; count: number }>
  aiSummary?: {
    source: string
    text: string
    fallbackReason?: string
  }
  items: RoleTodoItem[]
}

export interface BusinessTodoAttachment {
  id: string
  fileName: string
  contentType?: string
  sizeBytes: number
  downloadUrl: string
}

export interface BusinessTodoAction {
  id: string
  actionType: string
  status: string
  statusLabel: string
  note: string
  actorName?: string
  actorRole?: string
  createdAt?: string
  attachments: BusinessTodoAttachment[]
}

export interface BusinessTodo {
  id: string
  ruleCode: string
  title: string
  summary: string
  status: 'PENDING' | 'IN_PROGRESS' | 'PENDING_REVIEW' | 'COMPLETED' | 'REJECTED'
  statusLabel: string
  priority: number
  sourceModule: string
  sourceRecordId: string
  targetRoute: string
  storeId?: string
  storeName?: string
  brandName?: string
  month?: string
  assigneeRole?: string
  reviewRole?: string
  createdAt?: string
  updatedAt?: string
  completedAt?: string
  canTransition: boolean
  actions: BusinessTodoAction[]
}

export interface BusinessTodoTransitionPayload {
  status: 'IN_PROGRESS' | 'PENDING_REVIEW' | 'COMPLETED' | 'REJECTED'
  note: string
  attachments?: Array<{
    fileName: string
    contentType?: string
    dataBase64: string
  }>
}

export interface BossTodoDashboard {
  roleName: string
  dataSource: string
  updatedAt: string
  todayFocus?: {
    totalOpenCount?: number
    needsBossActionCount?: number
    needsBossCount?: number
    roleWorkCount?: number
    highRiskCount?: number
    highRiskGroupCount?: number
    doneReviewCount?: number
    summary?: string
  }
  needsBossAction?: RoleTodoItem[]
  highRiskReminders?: Array<{
    groupKey?: string
    sourceModule?: string
    ownerName?: string
    storeName?: string
    month?: string
    count?: number
    itemCount?: number
    highestRisk?: string
    riskLabel?: string
    highestPriority?: number
    earliestDueAt?: string
    topStores?: string[]
    action?: RoleTodoAction
  }>
  roleProgress?: Array<{
    ownerName?: string
    openCount?: number
    riskCount?: number
    pendingCount?: number
    earliestDueAt?: string
    topSources?: string[]
    topModules?: string[]
  }>
  doneReview?: RoleTodoItem[]
}

export function getRoleTodos(endpoint: 'finance' | 'supervisor' | 'warehouse' | 'store-manager' | 'operations') {
  return apiGet<RoleTodoResponse>(`/api/${endpoint}/todos?includeDone=true&limit=120`)
}

export function getBossTodoDashboard(params: { includeDone?: boolean; limit?: number } = {}) {
  const includeDone = params.includeDone ?? true
  const limit = params.limit ?? 120
  return apiGet<BossTodoDashboard>(`/api/boss/todo-dashboard?includeDone=${includeDone}&limit=${limit}`)
}

export interface ManualBusinessTodoPayload {
  title: string
  summary: string
  storeId: string
  month: string
  assigneeRole: string
  dueAt: string
  sourceModule: 'ASSISTANT'
  sourceRecordId: string
  expectedImpact: string
  verificationMetric: string
  confirmed: true
}

export function getBusinessTodos(status?: BusinessTodo['status']) {
  const query = status ? `?status=${encodeURIComponent(status)}` : ''
  return apiGet<BusinessTodo[]>(`/api/todos${query}`)
}

export function getBusinessTodo(id: string) {
  return apiGet<BusinessTodo>(`/api/todos/${encodeURIComponent(id)}`)
}

export function createManualBusinessTodo(payload: ManualBusinessTodoPayload) {
  return apiPost<BusinessTodo, ManualBusinessTodoPayload>('/api/todos/manual', payload)
}

export function transitionBusinessTodo(id: string, payload: BusinessTodoTransitionPayload) {
  return apiPost<BusinessTodo, BusinessTodoTransitionPayload>(`/api/todos/${encodeURIComponent(id)}/transition`, payload)
}

export async function downloadBusinessTodoAttachment(todoId: string, attachment: BusinessTodoAttachment) {
  const path = attachment.downloadUrl
    || `/api/todos/${encodeURIComponent(todoId)}/attachments/${encodeURIComponent(attachment.id)}`
  const response = await http.get<Blob>(path, { responseType: 'blob' })
  const blobUrl = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = blobUrl
  link.download = attachment.fileName || '待办附件'
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(blobUrl)
}
