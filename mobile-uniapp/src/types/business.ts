export const MOBILE_PERMISSIONS = {
  warehouseRead: 'warehouse.store.read',
  requisitionCreate: 'warehouse.requisition.create',
  requisitionReceive: 'warehouse.requisition.receive',
  requisitionProcess: 'warehouse.requisition.process',
  warehouseCentralRead: 'warehouse.central.read',
  inspectionRead: 'inspection.read',
  inspectionManage: 'inspection.manage',
  attachmentWrite: 'attachment.write',
  todoRead: 'todo.read',
  examLearn: 'exam.learn',
  employeeAssistantUse: 'employee_assistant.use',
  financeRead: 'finance.profit.read',
  bossDashboardRead: 'system.dashboard.read',
  storeRead: 'store.read',
} as const

export interface WarehouseItem {
  id: number
  code: string
  name: string
  categoryName?: string
  unit?: string
  spec?: string
  storeStockQuantity: number
  warehouseAvailableQuantity: number
  minStockQuantity?: number
  stockStatus: string
  alertLevel: string
  alertText?: string
  active: boolean
}

export interface WarehouseRequisitionLine {
  id?: number
  itemId: number
  itemName: string
  unit?: string
  requestedQuantity: number
  approvedQuantity: number
  shippedQuantity?: number
  warningText?: string
  note?: string
}

export interface WarehouseRequisition {
  id: string
  storeId: string
  storeName: string
  warehouseId?: number | string
  warehouseName?: string
  status: string
  statusLabel: string
  note?: string
  submittedAt?: string
  shippedAt?: string
  receivedAt?: string
  lines: WarehouseRequisitionLine[]
}

export interface WarehouseReturn {
  id: string
  returnNo?: string
  returnStoreName?: string
  receiveWarehouseName?: string
  status: string
  statusLabel?: string
  reason?: string
  lineCount?: number
}

export interface InspectionRectification {
  recordId: string
  storeId: string
  storeName?: string
  inspectionDate?: string
  status: 'PENDING_SUBMISSION' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | string
  statusLabel?: string
  requirement?: string
  evidenceAttachmentIds: number[]
  managerNote?: string
  reviewNote?: string
  updatedAt?: string
}

export interface WarehouseOverview {
  summary: {
    itemCount: number
    lowStockCount: number
    expiringCount: number
    pendingRequisitionCount: number
    pendingReceiptCount: number
  }
  items: WarehouseItem[]
  requisitions: WarehouseRequisition[]
}

export interface RequisitionCreatePayload {
  storeId?: string
  note?: string
  clientRequestId: string
  lines: Array<{
    itemId: number
    requestedQuantity: number
    note?: string
  }>
}

export interface RoleTodoItem {
  id: string
  title: string
  summary: string
  status: string
  priority: number
  storeId?: string
  storeName?: string
  dueAt?: string
  sourceRecordId?: string
  updatedAt?: string
}

export interface RoleTodoResponse {
  roleName: string
  updatedAt: string
  items: RoleTodoItem[]
}

export interface StoreInfo {
  id: string
  code: string
  name: string
  brandId: number
  brandName?: string
  status?: string
}

export type InspectionRiskLevel = 'RED' | 'YELLOW' | 'NORMAL'
export type InspectionCategoryCode = 'MATERIAL' | 'HYGIENE' | 'SERVICE'

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

export interface InspectionStandard {
  id?: number
  title: string
  fullScore: number
  passScore?: number
  version: string
  valid?: boolean
  validFlag?: boolean
  saveAllowed?: boolean
  validationError?: string
  items: InspectionStandardItem[]
}

export interface InspectionItemResult {
  standardItemId: number
  categoryCode?: InspectionCategoryCode
  actualScore: number
  issueFound: boolean
  deductionReason?: string
  photoAttachmentIds: number[]
  beforePhotoAttachmentIds: number[]
  afterPhotoAttachmentIds: number[]
  rectificationStatus: string
}

export interface InspectionRecord {
  id: string
  storeId: string
  storeName?: string
  inspectionDate: string
  inspector?: string
  score?: number
  displayScore?: number
  fullScore?: number
  displayFullScore?: number
  passed?: boolean
  displayPassed?: boolean
  resultCode?: string
  note?: string
  itemResults?: InspectionItemResult[]
  redLineCount?: number
  yellowLineCount?: number
}

export interface InspectionRecordPayload {
  storeId: string
  inspectionDate: string
  inspector?: string
  fullScore: number
  standardVersionId?: number
  standardVersion?: string
  itemResults: InspectionItemResult[]
  photosJson?: string
  deductionsJson?: string
  redlinesJson?: string
  note?: string
}

export interface StorageAttachment {
  id: number
  fileName: string
  contentType?: string
  fileSize?: number
  sizeBytes?: number
  downloadUrl?: string
}

export interface InspectionDetection {
  image_id?: string
  imageId?: string
  filename?: string
  attachmentId?: number
  passed?: boolean
  detection_count?: number
  detectionCount?: number
  detections?: Array<Record<string, unknown>>
  detection_summary?: string
  auto_status?: string
  deduction_project?: string
  deduction_content?: string
  detectionKey?: string
  clauseId?: number
  clauseCode?: string
  clauseTitle?: string
  suggestedDeduction?: number
  confirmedDeduction?: number
  confidence?: number
  decisionStatus?: string
  revision?: number
  [key: string]: unknown
}

export interface TrainingVideo {
  id: number
  videoCode: string
  courseId?: number
  courseTitle?: string
  title: string
  category?: string
  description?: string
  durationSeconds?: number
  enabled: boolean
  myWatchedSeconds: number
  myLastPosition: number
  myPercent: number
  myCompleted: boolean
}

export interface TrainingProgress {
  videoId: number
  watchedSeconds: number
  lastPosition: number
  percent: number
  completed: boolean
}

export interface ExamQuestion {
  id: number
  questionType: 'SINGLE_CHOICE' | 'TEXT' | 'NUMBER' | 'ESSAY' | string
  questionText: string
  options: string[]
  score: number
  sortOrder: number
}

export interface ExamPaper {
  id: number
  paperCode: string
  paperName: string
  passScore: number
  questions: ExamQuestion[]
}

export interface ExamAssignment {
  id: number
  campaignId: number
  paperId: number
  examTitle: string
  paperName: string
  status: 'NOT_STARTED' | 'ASSIGNED' | 'COMPLETED' | 'OVERDUE' | 'REVIEW_PENDING' | 'RETAKE_PENDING'
  statusLabel: string
  startAt: string
  dueAt: string
  completedAt?: string
  score?: number
  passed?: boolean
}

export interface ExamOverview {
  accessMode: 'COMPANY' | 'STORE' | 'SELF'
  canManage: boolean
  canExport: boolean
  assignments: ExamAssignment[]
}

export interface ExamAttempt {
  id: number
  paperName: string
  score: number
  passed: boolean
  submittedAt: string
}

export interface ExamResult {
  attemptId: number
  assignmentId?: number
  examTitle?: string
  paperName: string
  score: number
  passed: boolean
  reviewStatus: string
  submittedAt: string
}

export interface EmployeeAssistantStatus {
  enabled: boolean
  configured: boolean
  state?: 'UNCONFIGURED' | 'AUTH_FAILED' | 'UNAVAILABLE' | 'READY'
  message?: string
  canAsk?: boolean
}

export interface EmployeeAssistantReply {
  answer: string
  requestId?: string
  sessionId?: string
  needsHuman?: boolean
  answerSource?: 'KNOWLEDGE' | 'ASSISTANT' | 'HUMAN_REQUIRED'
  knowledgeTitle?: string
}

export interface ProfitSummary {
  month: string
  storeCount: number
  entryCount: number
  sales: number
  income: number
  costSum: number
  expenseSum: number
  net: number
  margin: number
  riskStoreCount: number
}

export interface ProfitDashboard {
  months: string[]
  summary: ProfitSummary
  trend: Array<{
    month: string
    sales: number
    income: number
    net: number
    margin: number
  }>
}

export interface BossExamSummary {
  activeExamCount: number
  assignedCount: number
  completedCount: number
  completionRate: number
  passedCount: number
  passRate: number
  overdueCount: number
  averageScore: number
}
