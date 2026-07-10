import { apiGet, apiPost } from './http'
import type { RoleTodoItem, RoleTodoResponse } from './todos'

export interface MigrationStatus {
  migrationRequired: boolean
  businessKeyCount: number
  presentBusinessKeyCount: number
  legacyBusinessKeys: Array<{
    key: string
    targetTable: string
    present: boolean
    valueBytes: number
    migrationState: string
  }>
}

export interface LegacyKvPreview {
  automaticRunAvailable: boolean
  businessKeyCount: number
  actionableKeyCount: number
  totalValueBytes: number
  items: Array<{
    key: string
    targetTable: string
    present: boolean
    valueBytes: number
    plannedAction: string
    automaticMigrationReady: boolean
  }>
}

export interface OperationLog {
  id: number
  operatorId?: number
  operatorName?: string
  action: string
  targetType?: string
  targetId?: string
  storeId?: string
  month?: string
  reason?: string
  createdAt?: string
}

export interface StoreInfo {
  id: string
  code: string
  name: string
  brandId: number
  brandName: string
  area?: string
  manager?: string
  openDate?: string
  status?: string
  note?: string
}

export interface BrandInfo {
  id: number
  code: string
  name: string
  color?: string
  sortOrder?: number
}

export interface ElemeStatus {
  configured: boolean
  mode: 'LIVE' | 'UNCONFIGURED' | string
  shopCount: number
}

export interface ElemeSummaryRow {
  shopId: string
  shopName: string
  bizDate: string
  validOrderCount: number
  totalPriceSum: number
  incomeSum: number
}

export interface ElemeSummary {
  mode: 'LIVE' | 'UNCONFIGURED' | 'UNAVAILABLE' | 'ERROR' | string
  note: string
  days: number
  generatedAt: string
  totalPrice: number
  income: number
  orderCount: number
  shops: ElemeSummaryRow[]
}

export interface InventoryCheckLine {
  id?: number
  itemName: string
  itemCode?: string
  category?: string
  spec?: string
  unit?: string
  packageQuantity?: number
  unitPrice?: number
  unitPriceEach?: number
  countedQuantity?: number
  amount?: number
  note?: string
}

export interface InventoryCheck {
  id: number
  checkNo: string
  storeId: string
  storeName: string
  checkDate: string
  status: string
  statusLabel: string
  totalAmount: number
  submittedBy?: number
  reviewedBy?: number
  reviewedAt?: string
  note?: string
  createdAt?: string
  updatedAt?: string
  lines: InventoryCheckLine[]
}

export interface InventoryCheckRequest {
  id?: number
  storeId?: string
  checkDate: string
  note?: string
  lines: InventoryCheckLine[]
}

export interface ExamQuestion {
  id: number
  questionType: string
  questionText: string
  options: string[]
  score: number
  sortOrder: number
}

export interface ExamPaper {
  id: number
  paperCode: string
  paperName: string
  roleScope?: string
  passScore: number
  enabled: boolean
  questions: ExamQuestion[]
}

export interface ExamAttemptRequest {
  paperId: number
  examineeName?: string
  storeId?: string
  violated?: boolean
  answers: Array<{
    questionId: number
    userAnswer: string
  }>
}

export interface ExamAnswerResult {
  questionId: number
  questionText: string
  userAnswer?: string
  correct: boolean
  score: number
}

export interface ExamAttempt {
  id: number
  paperId: number
  paperName: string
  examineeName: string
  examineeRole: string
  storeId?: string
  storeName?: string
  score: number
  passed: boolean
  violated: boolean
  submittedBy?: number
  submittedAt: string
  answers: ExamAnswerResult[]
}

export interface TrainingMaterial {
  id: number
  materialCode: string
  title: string
  category: string
  imageUrls: string[]
  content?: string
  enabled: boolean
  sortOrder: number
  learned: boolean
  learnedAt?: string
}

export interface TrainingLearningRecord {
  id: number
  materialId: number
  materialTitle: string
  userName: string
  storeId?: string
  learned: boolean
  learnedAt: string
}

export interface OperationsActionResult {
  todoId?: string
  status?: string
  message?: string
  item?: RoleTodoItem
}

export function getOperationsTodos() {
  return apiGet<RoleTodoResponse>('/api/operations/todos?includeDone=true&limit=160')
}

export function resolveOperationsTodo(todoId: string, note: string) {
  return apiPost<OperationsActionResult, { note: string }>(`/api/operations/todos/${encodeURIComponent(todoId)}/resolve`, { note })
}

export function escalateOperationsTodo(todoId: string, reason: string) {
  return apiPost<OperationsActionResult, { reason: string }>(`/api/operations/todos/${encodeURIComponent(todoId)}/escalate`, { reason })
}

export function getMigrationStatus() {
  return apiGet<MigrationStatus>('/api/migration/status')
}

export function getLegacyKvPreview() {
  return apiGet<LegacyKvPreview>('/api/migration/legacy-kv/preview')
}

export function getAuditLogs(limit = 80) {
  return apiGet<OperationLog[]>(`/api/audit/logs?limit=${limit}`)
}

export function getStores() {
  return apiGet<StoreInfo[]>('/api/stores')
}

export function getBrands() {
  return apiGet<BrandInfo[]>('/api/brands')
}

export function getElemeStatus() {
  return apiGet<ElemeStatus>('/api/eleme/status')
}

export function getElemeSummary(month?: string) {
  const query = month ? `?month=${encodeURIComponent(month)}` : ''
  return apiGet<ElemeSummary>(`/api/eleme/summary${query}`)
}

export function getInventoryChecks() {
  return apiGet<InventoryCheck[]>('/api/operations/inventory-checks')
}

export function getInventoryCheck(id: number) {
  return apiGet<InventoryCheck>(`/api/operations/inventory-checks/${id}`)
}

export function saveInventoryCheck(payload: InventoryCheckRequest) {
  return apiPost<InventoryCheck, InventoryCheckRequest>('/api/operations/inventory-checks', payload)
}

export function submitInventoryCheck(id: number) {
  return apiPost<InventoryCheck>(`/api/operations/inventory-checks/${id}/submit`)
}

export function reviewInventoryCheck(id: number) {
  return apiPost<InventoryCheck>(`/api/operations/inventory-checks/${id}/review`)
}

export function cancelInventoryCheck(id: number) {
  return apiPost<InventoryCheck>(`/api/operations/inventory-checks/${id}/cancel`)
}

export function getExamPapers() {
  return apiGet<ExamPaper[]>('/api/operations/exam-papers')
}

export function getExamPaper(id: number) {
  return apiGet<ExamPaper>(`/api/operations/exam-papers/${id}`)
}

export function submitExamAttempt(payload: ExamAttemptRequest) {
  return apiPost<ExamAttempt, ExamAttemptRequest>('/api/operations/exam-attempts', payload)
}

export function getExamAttempts() {
  return apiGet<ExamAttempt[]>('/api/operations/exam-attempts')
}

export function getTrainingMaterials() {
  return apiGet<TrainingMaterial[]>('/api/operations/training-materials')
}

export function markTrainingMaterialLearned(id: number) {
  return apiPost<TrainingMaterial[]>(`/api/operations/training-materials/${id}/learned`)
}

export function getTrainingLearningRecords() {
  return apiGet<TrainingLearningRecord[]>('/api/operations/training-learning-records')
}
