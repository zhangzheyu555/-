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
  expenseCreate: 'expense.create', expenseRead: 'expense.read', expenseReview: 'expense.review',
  salaryRead: 'salary.read', salaryReview: 'salary.review', salaryPay: 'salary.pay',
  dailyLossRead: 'daily_loss.read', dailyLossCreate: 'daily_loss.create', dailyLossReview: 'daily_loss.review',
  auditRead: 'system.audit.read', operationsRead: 'operations.dashboard.read', assistantUse: 'assistant.use',
} as const

export interface ExpenseClaim {
  id: string; storeId: string; storeName?: string; month: string; amount: number; category?: string; reason?: string
  status: string; latestSupplementNote?: string; supplementAttachmentCount?: number; supplements?: ExpenseSupplement[]
}
export interface ExpenseSupplement { id:number; note?:string; submittedAt?:string; attachments:ExpenseAttachment[] }
export interface ExpenseAttachment { id:number; fileName:string; contentType?:string; fileSize?:number; previewUrl?:string; downloadUrl?:string }
export interface SalaryRecord {
  id: string; storeId: string; storeName?: string; month: string; employeeName: string; position?: string
  employeeId?:string; attendance?:string; gross?: number|null; netPay?: number|null; normalHours?:number; otHours?:number; workHours?:number; vacationLeft?:number; vacationNote?:string
  base?:number|null; social?:number|null; post?:number|null; meal?:number|null; fullAttendance?:number|null; commission?:number|null; overtime?:number|null; seniority?:number|null; lateNight?:number|null; subsidy?:number|null; performance?:number|null; deductUniform?:number|null; returnUniform?:number|null
  status?: string; submittedBy?:number; reviewedBy?:number; reviewedAt?:string; reviewNote?: string; paidAt?: string; version?:number
}
export interface SalaryEmployeePage {
  content: SalaryRecord[]; rows?: SalaryRecord[]; total: number; totalElements?: number
  page: number; size: number; totalPages: number
}
export interface EmployeeSelfProfile {
  profile: { userId: number; username: string; displayName: string; role: string }
  store: { storeId: string; storeName: string; brandName?: string | null }
  archive: {
    linked: boolean; employeeId?: string | null; name?: string | null; position?: string | null
    employmentType?: string | null; status?: string | null; hireDate?: string | null
    baseSalary?: number | null; message: string
  }
  salary: {
    available: boolean; recordId?: string | null; month?: string | null; status?: string | null
    statusLabel: string; employeeId?: string | null; employeeName?: string | null; position?: string | null
    attendance?: string | null; base?: number | null; gross?: number | null; netPay?: number | null
    commission?: number | null; overtime?: number | null; performance?: number | null
    deductUniform?: number | null; returnUniform?: number | null; vacationLeft?: number | null
    vacationNote?: string | null; reviewedAt?: string | null; paidAt?: string | null; message: string
  }
  checklist: Array<{ key: string; title: string; description: string; state: string; severity: string }>
}
export interface DailyLossItem { id: number; code: string; name: string; stockUnit: string; unitPrice: number }
export interface DailyLossRecord {
  id: string; storeId: string; storeName: string; lossDate: string; itemId: number; itemName: string; stockUnit: string
  lossQuantity: number; amountSnapshot: number; lossReason: string; status: string; reviewNote?: string; attachments?: DailyLossAttachment[]
}
export interface DailyLossAttachment { id:number; fileName:string; contentType?:string; fileSize?:number; downloadUrl:string }
export interface OperationLog { id: number; operatorName?: string; action: string; targetType?: string; targetId?: string; reason?: string; createdAt?: string }
export interface InventoryCheckLine { id?:number;itemName:string;itemCode?:string;category?:string;spec?:string;unit?:string;packageQuantity?:number;unitPrice:number;unitPriceEach?:number;countedQuantity:number;amount?:number;note?:string }
export interface InventoryCheck { id:number;checkNo:string;storeId:string;storeName:string;checkDate:string;status:string;statusLabel:string;totalAmount:number;submittedBy?:number;reviewedBy?:number;reviewedAt?:string;note?:string;createdAt?:string;updatedAt?:string;lines:InventoryCheckLine[] }

export interface WarehouseItem {
  id: number
  code: string
  name: string
  categoryId?: number
  categoryName?: string
  category?: string
  imageUrl?: string
  unit?: string
  purchaseUnit?: string
  stockUnit?: string
  ingredientUnit?: string
  unitConversionText?: string
  spec?: string
  warehouseLocation?: string
  unitPrice?: number
  shelfLifeDays?: number
  dailyUsageEstimate?: number
  minStockDays?: number
  maxStockDays?: number
  storeStockQuantity: number
  warehouseAvailableQuantity: number
  minStockQuantity?: number
  alertEnabled?: boolean
  expiryAlertDays?: number
  daysAvailable?: number
  nearestExpiryDate?: string
  itemDescription?: string
  itemAttributes?: string
  departments?: Array<{departmentName:string;departmentCode?:string;departmentGroup?:string;purchaseMethod?:string;supplierName?:string}>
  stockStatus: string
  alertLevel: string
  alertText?: string
  active: boolean
}

export interface WarehouseItemCategory {
  id: number
  name: string
  parentId?: number
  sortOrder?: number
  enabled: boolean
  children?: WarehouseItemCategory[]
}

export interface WarehouseRequisitionLine {
  id?: number
  itemId: number
  itemName: string
  unit?: string
  requestedQuantity: number
  approvedQuantity: number
  shippedQuantity?: number
  receivedQuantity?: number
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
  totalAmount?: number
  note?: string
  submittedBy?: string
  reviewedBy?: string
  shippedBy?: string
  receivedBy?: string
  submittedAt?: string
  reviewedAt?: string
  shippedAt?: string
  receivedAt?: string
  lines: WarehouseRequisitionLine[]
}

export interface WarehouseReturn {
  id: string
  returnNo?: string
  sourceRequisitionId?: string
  returnStoreName?: string
  receiveWarehouseName?: string
  status: string
  statusLabel?: string
  reason?: string
  note?: string
  returnDate?: string
  createdAt?: string
  reviewedAt?: string
  receivedAt?: string
  lineCount?: number
  lines?: WarehouseReturnLine[]
}
export interface WarehouseReturnLine { itemId:number; itemName:string; spec?:string; batchNo?:string; quantity:number; unit?:string; reason?:string; note?:string }

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
    overstockCount?: number
    pendingRequisitionCount: number
    pendingReceiptCount: number
    pendingPurchaseCount?: number
    stockValue?: number
  }
  items: WarehouseItem[]
  requisitions: WarehouseRequisition[]
  suppliers?: WarehouseSupplier[]
  purchaseOrders?: WarehousePurchaseOrder[]
  movements?: WarehouseStockMovement[]
  stockBatches?: WarehouseStockBatch[]
}
export interface WarehouseFacility { id:number|string; code:string; name:string; type:string; enabled:boolean; parentWarehouseId?:number|string; parentWarehouseName?:string; canPurchase?:boolean; canRequestTransfer?:boolean; canApproveTransfer?:boolean; canShipTransfer?:boolean; canReceiveTransfer?:boolean }
export interface WarehouseSupplier { id:number; name:string; active:boolean }
export interface WarehousePurchaseOrderLine { itemId:number; itemName:string; unit?:string; orderedQuantity:number; receivedQuantity:number; unitCost:number; note?:string }
export interface WarehousePurchaseOrder { id:string; supplierName?:string; warehouseId?:number|string; warehouseName?:string; status:string; statusLabel:string; totalAmount:number; createdAt?:string; lines:WarehousePurchaseOrderLine[] }
export interface WarehouseStockMovement { id:number; itemId:number; itemName:string; movementType:string; movementTypeLabel:string; quantityDelta:number; warehouseId?:number|string; warehouseName?:string; sourceWarehouseId?:number|string; sourceWarehouseName?:string; targetWarehouseId?:number|string; targetWarehouseName?:string; sourceType?:string; sourceId?:string; storeId?:string; storeName?:string; note?:string; operatorName?:string; createdAt?:string; batchNo?:string }
export interface WarehouseStockBatch { id:number; itemId:number; itemName:string; warehouseId?:number|string; warehouseName?:string; batchNo:string; receivedDate:string; expiryDate?:string; quantity:number; unitCost:number; status:string }
export interface WarehouseTransferLine { id?:number; itemId:number; itemName:string; requestedQuantity:number; approvedQuantity:number; reservedQuantity?:number; shippedQuantity:number; receivedQuantity:number; inTransitQuantity:number; unitCost?:number; amount?:number; note?:string; unit?:string }
export interface WarehouseTransfer { id:string; transferNo?:string; status:string; statusLabel?:string; sourceWarehouseId:number|string; sourceWarehouseName:string; targetWarehouseId:number|string; targetWarehouseName:string; totalAmount:number; requestedBy?:string; approvedBy?:string; shippedBy?:string; receivedBy?:string; cancelledBy?:string; note?:string; reviewNote?:string; lines:WarehouseTransferLine[]; createdAt?:string; submittedAt?:string; reviewedAt?:string; shippedAt?:string; receivedAt?:string; cancelledAt?:string }
export interface WarehouseTransferContext { currentWarehouse:{id:number|string;name:string}; mode?:string; workbenchLabel?:string; todos?:{draft?:number;pendingApproval?:number;pendingShipment?:number;pendingReceipt?:number;completed?:number}; routes:Array<{sourceWarehouse:{id:number|string;name:string};targetWarehouse:{id:number|string;name:string};actions:{canCreate:boolean;canSubmit:boolean;canApprove:boolean;canReject:boolean;canShip:boolean;canReceive:boolean;canCancel:boolean};materials:Array<{itemId:number;itemName:string;unit?:string;availableQuantity:number}>}> }

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
  sourceModule?: string
  sourceRecordId?: string
  processStatus?: string
  escalatedToBoss?: boolean
  action?: {
    target: string
    label: string
    params?: Record<string, unknown>
  }
  updatedAt?: string
  occurredAt?: string
}

export interface RoleTodoResponse {
  roleName: string
  updatedAt: string
  items: RoleTodoItem[]
}

export interface StoreManagerWorkbenchItem { id:string; title:string; summary:string; status:string; priority:number; sourceModule?:string; sourceRecordId?:string; dueAt?:string; nextActionLabel?:string; target?:string; actionMonth?:string; storeId?:string; storeName?:string }
export interface StoreManagerWorkbench {
  roleName:string
  dataSource:string
  updatedAt:string
  store:{storeId:string;storeName:string}
  todayFocus:{pendingCount:number;pendingReceiptCount:number;rectificationCount:number;rejectedExpenseCount:number;businessRiskCount:number;summary:string}
  todayFocusItems:StoreManagerWorkbenchItem[]
  needMyAction:StoreManagerWorkbenchItem[]
  businessReminder:{month:string;income:number;net:number;margin:number;costRatio:number;risk:string;previousMonth?:string;previousIncome?:number;incomeChangeRate?:number;reminders:string[]}
  records?:{inspections?:InspectionRecord[];expenses?:ExpenseClaim[]}
}

export interface StoreInfo {
  id: string
  code: string
  name: string
  brandId: number
  brandName?: string
  status?: string
  area?: string
  manager?: string
  openDate?: string
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
  snapshotId?: number
  standardItemId: number
  categoryCode?: InspectionCategoryCode
  dimension?: string
  code?: string
  title?: string
  description?: string
  checkMethod?: string
  standardScore?: number
  actualScore: number
  deductionScore?: number
  issueFound: boolean
  deductionReason?: string
  photoAttachmentIds: number[]
  beforePhotoAttachmentIds: number[]
  afterPhotoAttachmentIds: number[]
  rectificationStatus: string
  reviewResult?: string
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
  displayResultCode?: string
  note?: string
  photosJson?: string
  deductionsJson?: string
  redlinesJson?: string
  standardVersionId?: number
  standardVersion?: string
  itemResults?: InspectionItemResult[]
  redLineCount?: number
  yellowLineCount?: number
}

export interface InspectionEvidenceCandidate { photoIndex?:number;attachmentId?:number;fileName?:string;contentType?:string;status:string;message?:string;linkedClauseIds:number[] }
export interface InspectionEvidenceCandidates { recordId:string;storeId:string;candidates:InspectionEvidenceCandidate[] }
export interface InspectionDetectionDecision { record:InspectionRecord;detection:Record<string,unknown>;changed:boolean }

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

export interface WrongQuestion {
  id: number
  attemptId: number
  questionId: number
  paperName: string
  questionType: string
  questionText: string
  standardAnswer: string
  userAnswer: string
  answerAnalysis?: string
  mastered: boolean
  createdAt: string
}

export interface TrainingProgressReport {
  userId: number
  userName: string
  storeId?: string
  storeName?: string
  videoId: number
  videoTitle: string
  videoCategory?: string
  watchedSeconds: number
  percent: number
  completed: boolean
  lastWatchedAt?: string
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

export interface EmployeeAssistantHandoff {
  id: string
  storeId?: string
  question: string
  category?: string
  status: string
  requestedBy: number
  requestedByName?: string
  handledBy?: number
  handledByName?: string
  resolution?: string
  createdAt: string
  claimedAt?: string
  respondedAt?: string
  closedAt?: string
  expiresAt?: string
}

export interface PlatformAdapterStatus {
  platform: string
  orderSync: 'READY' | 'NOT_CONFIGURED' | string
  webhook: 'READY' | 'NOT_CONFIGURED' | string
  message?: string
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
  brands?: Array<{ id:number; code?:string; name:string }>
  summary: ProfitSummary
  entries?: ProfitEntry[]
  trend: Array<{
    month: string
    sales: number
    income: number
    net: number
    margin: number
  }>
}

export interface ProfitEntry {
  id:number
  storeId:string
  storeCode?:string
  storeName:string
  brandId?:number
  brandName?:string
  area?:string
  manager?:string
  month:string
  sales:number
  refund:number
  discount:number
  income:number
  material:number
  packaging:number
  loss:number
  costOther:number
  costSum:number
  costRatio:number
  gross:number
  grossMargin:number
  rent:number
  labor:number
  utility:number
  property:number
  commission:number
  promo:number
  repair:number
  equip:number
  expOther:number
  expenseSum:number
  net:number
  margin:number
  risk?:string
  note?:string
}

export interface FinanceSalaryCheck { id:string; employeeName:string; storeId:string; storeName:string; month:string; gross:number; anomaly?:string; status:string }
export interface FinanceDataCheck { id:string; source:string; issue:string; storeId:string; storeName:string; month:string; status:string }
export interface FinanceWorkbench {
  roleName:string
  dataSource:string
  updatedAt:string
  month:string
  todayFocus:{pendingExpenseCount:number;profitRiskStoreCount:number;salaryCheckCount:number;escalatedToBossCount:number;summary:string}
  needMyAction:RoleTodoItem[]
  profitRisks:ProfitEntry[]
  expenseReviews:ExpenseClaim[]
  salaryChecks:FinanceSalaryCheck[]
  dataChecks:FinanceDataCheck[]
  doneReview:RoleTodoItem[]
  assistantPrompts:string[]
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
  riskStores: BossExamRiskStore[]
}

export interface BossExamRiskStore {
  storeId: string
  storeName: string
  assignedCount: number
  completedCount: number
  completionRate: number
  passedCount: number
  passRate: number
  overdueCount: number
  averageScore: number
  risks: string[]
}

export interface BossTodoRiskGroup {
  groupKey: string
  sourceModule: string
  ownerName: string
  storeName: string
  month?: string
  count: number
  highestRisk: string
  highestPriority: number
  earliestDueAt?: string
  topStores: string[]
  action?: RoleTodoItem['action']
}

export interface BossTodoOwnerGroup {
  ownerName: string
  openCount: number
  riskCount: number
  pendingCount: number
  earliestDueAt?: string
  topSources: string[]
}

export interface BossTodoDashboard {
  roleName: string
  dataSource: string
  updatedAt: string
  todayFocus: {
    totalOpenCount: number
    needsBossActionCount: number
    roleWorkCount: number
    highRiskCount: number
    highRiskGroupCount: number
    doneReviewCount: number
    summary: string
  }
  needsBossAction: RoleTodoItem[]
  highRiskReminders: BossTodoRiskGroup[]
  roleProgress: BossTodoOwnerGroup[]
  doneReview: RoleTodoItem[]
}

export interface MobileUserAccount {
  id: number
  tenantId: number
  tenantName: string
  username: string
  displayName: string
  role: string
  roleLabel: string
  storeId?: string
  enabled: boolean
  storeScope: string[]
  availableWorkspaces: string[]
  defaultWorkspace: string
  effectivePermissionStatus: string
  effectivePermissionMessage: string
}
