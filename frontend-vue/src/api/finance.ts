import { apiDelete, apiGet, apiPost, apiPostForm, apiPut, http } from './http'

export interface ExpenseClaim {
  id: string
  storeId: string
  storeCode?: string
  storeName?: string
  brandId?: number
  brandName?: string
  month: string
  amount: number
  category?: string
  reason?: string
  status: string
  imageUrl?: string
  submittedBy?: number
  reviewedBy?: number
  reviewedAt?: string
  supplements?: ExpenseSupplement[]
  supplementAttachmentCount?: number
  latestSupplementNote?: string
}

export interface ExpenseSupplementAttachment {
  id: string | number
  fileName: string
  originalFileName?: string
  contentType?: string
  fileSize?: number
  sizeBytes?: number
  uploadedBy?: number
  uploadedByName?: string
  uploadedAt?: string
  previewUrl?: string
  downloadUrl?: string
}

export interface ExpenseSupplement {
  id: string | number
  note?: string
  submittedBy?: number
  submittedByName?: string
  submittedAt?: string
  attachments?: ExpenseSupplementAttachment[]
}

export interface ProfitEntry {
  id?: number
  storeId: string
  storeCode?: string
  storeName?: string
  brandId?: number
  brandName?: string
  month: string
  sales?: number
  refund?: number
  discount?: number
  income?: number
  material?: number
  packaging?: number
  loss?: number
  costOther?: number
  costSum?: number
  costRatio?: number
  gross?: number
  grossMargin?: number
  rent?: number
  labor?: number
  utility?: number
  property?: number
  commission?: number
  promo?: number
  repair?: number
  equip?: number
  expOther?: number
  expenseSum?: number
  net?: number
  margin?: number
  risk?: string
  note?: string
}

export interface ExpenseClaimPayload {
  storeId: string
  month: string
  amount: number
  category?: string
  reason?: string
  imageUrl?: string
}

export interface StorageUpload {
  id?: number
  fileName: string
  contentType?: string
  sizeBytes: number
  downloadUrl: string
}

export interface ProfitEntryPayload {
  storeId: string
  month: string
  sales: number
  refund: number
  discount: number
  material: number
  packaging: number
  loss: number
  costOther: number
  rent: number
  labor: number
  utility: number
  property: number
  commission: number
  promo: number
  repair: number
  equip: number
  expOther: number
  note?: string
}

export interface ProfitEntryQuery {
  month?: string
  brandId?: number
  storeId?: string
}

export interface SalaryRecord {
  id: string
  storeId: string
  storeCode?: string
  storeName?: string
  brandId?: number
  brandName?: string
  month: string
  employeeId?: string
  employeeName: string
  position?: string
  attendance?: string
  gross?: number
  netPay?: number
  normalHours?: number
  otHours?: number
  workHours?: number
  overtime?: number
  vacationLeft?: number
  vacationNote?: string
  base?: number
  social?: number
  post?: number
  meal?: number
  fullAttendance?: number
  commission?: number
  seniority?: number
  lateNight?: number
  subsidy?: number
  performance?: number
  deductUniform?: number
  returnUniform?: number
  status?: 'PENDING_GENERATION' | 'DRAFT' | 'SUBMITTED' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'PAID' | 'LOCKED'
  submittedBy?: number
  reviewedBy?: number
  reviewedAt?: string
  reviewNote?: string
  paidAt?: string
  version?: number
}

export interface SalaryGenerateReport {
  generated: number
  skipped: number
  errors: number
  skipDetails: SalarySkipDetail[]
}

export interface SalarySkipDetail {
  employeeId: string
  employeeName: string
  reason: string
}

export interface SalaryPageResponse {
  content: SalaryRecord[]
  rows?: SalaryRecord[]
  total: number
  totalElements?: number
  page: number
  size: number
  totalPages: number
  summary: SalarySummary
  statusCounts?: Record<string, number>
  workHoursTotal?: number
  vacationBalanceTotal?: number
}

export interface SalarySummary {
  month: string
  storeCount: number
  recordCount: number
  grossTotal: number
  baseTotal: number
  commissionTotal: number
  overtimeTotal: number
}

export interface SalaryPageQuery {
  month?: string
  brandId?: number
  storeId?: string
  page?: number
  size?: number
}

export interface SalaryQuery {
  month?: string
  brandId?: number
  storeId?: string
  allMonths?: boolean
}

export interface SalaryRecordPayload {
  storeId: string
  month: string
  employeeId?: string
  employeeName: string
  position?: string
  attendance?: string
  gross?: number
  normalHours?: number
  otHours?: number
  workHours?: number
  vacationLeft?: number
  vacationNote?: string
  base?: number
  social?: number
  post?: number
  meal?: number
  fullAttendance?: number
  commission?: number
  overtime?: number
  seniority?: number
  lateNight?: number
  subsidy?: number
  performance?: number
  deductUniform?: number
  returnUniform?: number
}

export function getExpenses() {
  return apiGet<ExpenseClaim[]>('/api/expenses')
}

export function createExpense(payload: ExpenseClaimPayload) {
  return apiPost<ExpenseClaim, ExpenseClaimPayload>('/api/expenses', payload)
}

export function updateExpense(id: string, payload: ExpenseClaimPayload) {
  return apiPut<ExpenseClaim, ExpenseClaimPayload>(`/api/expenses/${encodeURIComponent(id)}`, payload)
}

export function submitExpense(id: string) {
  return apiPost<ExpenseClaim>(`/api/expenses/${encodeURIComponent(id)}/submit`)
}

export function requestExpenseInfo(id: string, note?: string) {
  return apiPost<ExpenseClaim, { note?: string }>(`/api/expenses/${encodeURIComponent(id)}/request-info`, { note })
}

export function submitExpenseSupplement(
  id: string,
  note: string,
  files: File[],
  onProgress?: (percent: number) => void,
) {
  const form = new FormData()
  form.append('note', note.trim())
  for (const file of files) form.append('files', file, file.name)
  return apiPostForm<ExpenseClaim>(`/api/expenses/${encodeURIComponent(id)}/supplements`, form, {
    timeout: 120_000,
    onUploadProgress: (event) => {
      if (!onProgress) return
      if (!event.total) {
        onProgress(0)
        return
      }
      onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
    },
  })
}

export async function fetchExpenseSupplementAttachment(
  expenseId: string,
  attachment: ExpenseSupplementAttachment,
  mode: 'preview' | 'download',
  signal?: AbortSignal,
) {
  const contentUrl = `/api/expenses/${encodeURIComponent(expenseId)}/attachments/${encodeURIComponent(String(attachment.id))}/content`
  const response = await http.get<Blob>(contentUrl, {
    responseType: 'blob',
    timeout: 120_000,
    signal,
    params: mode === 'download' ? { download: true } : undefined,
  })
  return response.data
}

export function expenseAttachmentDisplayName(attachment: ExpenseSupplementAttachment) {
  return String(attachment.originalFileName || attachment.fileName || '').trim() || '未命名附件'
}

export async function downloadExpenseSupplementAttachment(expenseId: string, attachment: ExpenseSupplementAttachment) {
  const blob = await fetchExpenseSupplementAttachment(expenseId, attachment, 'download')
  const blobUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = blobUrl
  link.download = expenseAttachmentDisplayName(attachment)
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(blobUrl), 0)
}

export function approveExpense(id: string, note?: string) {
  return apiPost<ExpenseClaim, { note?: string }>(`/api/expenses/${encodeURIComponent(id)}/approve`, { note })
}

export function rejectExpense(id: string, note?: string) {
  return apiPost<ExpenseClaim, { note?: string }>(`/api/expenses/${encodeURIComponent(id)}/reject`, { note })
}

export function deleteExpense(id: string) {
  return apiDelete<void>(`/api/expenses/${encodeURIComponent(id)}`)
}

export function uploadExpenseAttachment(file: File, storeId: string, expenseId: string) {
  const form = new FormData()
  form.append('file', file)
  form.append('storeId', storeId)
  form.append('businessType', 'EXPENSE_CLAIM')
  form.append('businessId', expenseId)
  return apiPostForm<StorageUpload>('/api/storage/upload', form)
}

export function getProfitMonths() {
  return apiGet<string[]>('/api/finance/months')
}

export function getProfitEntries(params: ProfitEntryQuery = {}, signal?: AbortSignal) {
  const query = new URLSearchParams()
  if (params.month) query.set('month', params.month)
  if (params.brandId !== undefined) query.set('brandId', String(params.brandId))
  if (params.storeId) query.set('storeId', params.storeId)
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return apiGet<ProfitEntry[]>(`/api/finance/entries${suffix}`, { signal })
}

export function saveProfitEntry(payload: ProfitEntryPayload) {
  return apiPut<void, ProfitEntryPayload>('/api/finance/entries', payload)
}

export function getSalaryRecords(params: SalaryQuery = {}) {
  const query = new URLSearchParams()
  if (params.month) query.set('month', params.month)
  if (params.brandId !== undefined) query.set('brandId', String(params.brandId))
  if (params.storeId) query.set('storeId', params.storeId)
  if (params.allMonths) query.set('allMonths', 'true')
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return apiGet<SalaryRecord[]>(`/api/salaries${suffix}`)
}

export function saveSalaryRecord(payload: SalaryRecordPayload, id?: string) {
  if (id) {
    return apiPut<SalaryRecord, SalaryRecordPayload>(`/api/salaries/${encodeURIComponent(id)}`, payload)
  }
  return apiPost<SalaryRecord, SalaryRecordPayload>('/api/salaries', payload)
}

export function submitSalaryRecord(id: string) {
  return apiPost<SalaryRecord>(`/api/salaries/${encodeURIComponent(id)}/submit`)
}

export function approveSalaryRecord(id: string) {
  return apiPost<SalaryRecord>(`/api/salaries/${encodeURIComponent(id)}/approve`)
}

export function rejectSalaryRecord(id: string, note?: string) {
  return apiPost<SalaryRecord, { note?: string }>(`/api/salaries/${encodeURIComponent(id)}/reject`, { note })
}

export function generateSalaryRecords(payload: { storeId: string; month: string }) {
  return apiPost<SalaryRecord[], { storeId: string; month: string }>('/api/salaries/generate', payload)
}

export function deleteSalaryRecord(id: string) {
  return apiDelete<void>(`/api/salaries/${encodeURIComponent(id)}`)
}

export function getSalaryPage(params: SalaryPageQuery = {}) {
  const query = new URLSearchParams()
  if (params.month) query.set('month', params.month)
  if (params.brandId !== undefined) query.set('brandId', String(params.brandId))
  if (params.storeId) query.set('storeId', params.storeId)
  if (params.page !== undefined) query.set('page', String(params.page))
  if (params.size !== undefined) query.set('size', String(params.size))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return apiGet<SalaryPageResponse>(`/api/salaries/page${suffix}`)
}

export function getSalaryEmployeePage(params: SalaryPageQuery & { status?: string; keyword?: string } = {}, signal?: AbortSignal) {
  const query = new URLSearchParams()
  if (params.month) query.set('month', params.month)
  if (params.brandId !== undefined) query.set('brandId', String(params.brandId))
  if (params.storeId) query.set('storeId', params.storeId)
  if (params.status) query.set('status', params.status)
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.page !== undefined) query.set('page', String(params.page))
  if (params.size !== undefined) query.set('size', String(params.size))
  return apiGet<SalaryPageResponse>(`/api/salaries/employee-page?${query.toString()}`, { signal })
}

export function getSalaryRecord(id: string) {
  return apiGet<SalaryRecord>(`/api/salaries/${encodeURIComponent(id)}`)
}

export function previewSalaryGeneration(storeId: string, month: string) {
  return apiGet<SalaryGenerateReport>(`/api/salaries/preview?storeId=${encodeURIComponent(storeId)}&month=${encodeURIComponent(month)}`)
}

export function generateSalaryWithReport(payload: { storeId: string; month: string }) {
  return apiPost<SalaryGenerateReport, { storeId: string; month: string }>('/api/salaries/generate-report', payload)
}

export function markSalaryPaid(id: string) {
  return apiPost<SalaryRecord>(`/api/salaries/${encodeURIComponent(id)}/mark-paid`)
}

export function lockSalaryRecord(id: string) {
  return apiPost<SalaryRecord>(`/api/salaries/${encodeURIComponent(id)}/lock`)
}

export async function exportSalaryCsv(params: { month?: string; brandId?: number; storeId?: string } = {}) {
  const query = new URLSearchParams()
  if (params.month) query.set('month', params.month)
  if (params.brandId !== undefined) query.set('brandId', String(params.brandId))
  if (params.storeId) query.set('storeId', params.storeId)
  const suffix = query.toString() ? `?${query.toString()}` : ''
  const response = await http.get(`/api/salaries/export${suffix}`, { responseType: 'blob' })
  const blob = response.data as Blob
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `salary-export-${params.month || 'all'}.csv`
  link.click()
  window.URL.revokeObjectURL(url)
}

export function escalateFinanceTodo(todoId: string, reason: string, severity = 'RISK') {
  return apiPost<unknown, { reason: string; severity: string }>(`/api/finance/todos/${encodeURIComponent(todoId)}/escalate`, {
    reason,
    severity,
  })
}
