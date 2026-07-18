import { apiGet, apiPost, apiPostForm, http } from './http'

export interface DailyLossItem {
  id: number
  itemCode?: string
  itemName?: string
  unit?: string
  code?: string
  name?: string
  category?: string
  categoryCode?: string
  categoryName?: string
  stockUnit?: string
  unitPrice?: number
  active?: boolean
}

export interface DailyLossAttachment {
  id: number | string
  fileName: string
  contentType?: string
  fileSize?: number
  downloadUrl?: string
}

export interface DailyLossRecord {
  id: string
  storeId: string
  storeName?: string
  lossDate: string
  itemId: number
  itemCode?: string
  itemName: string
  stockUnit?: string
  lossQuantity: number
  unitPriceSnapshot?: number
  amountSnapshot?: number
  lossReason: string
  status: string
  submittedByName?: string
  submittedAt?: string
  reviewedByName?: string
  reviewedAt?: string
  reviewNote?: string
  inventoryDeducted?: boolean
  attachments?: DailyLossAttachment[]
}

export interface DailyLossReportDetail {
  id: string
  itemConfigId?: number
  itemCode?: string
  itemName: string
  category?: string
  unit?: string
  lossQuantity: number
  unitPriceSnapshot?: number
  amountSnapshot?: number
  lossReason?: string
}

export interface DailyLossReport {
  id?: string
  storeId: string
  storeCode?: string
  storeName?: string
  lossDate: string
  month: string
  status: string
  statusLabel?: string
  reported: boolean
  totalAmount?: number
  detailCount?: number
  attachmentCount?: number
  submittedByName?: string
  submittedAt?: string
  reviewedByName?: string
  reviewedAt?: string
  reviewNote?: string
  details?: DailyLossReportDetail[]
  attachments?: DailyLossAttachment[]
}

export interface DailyLossReportLinePayload {
  itemConfigId: number
  lossQuantity: number
  lossReason?: string
}

export interface DailyLossReportSaveRequest {
  storeId: string
  lossDate: string
  details: DailyLossReportLinePayload[]
}

export interface DailyLossRecordQuery {
  storeId?: string
  date?: string
  status?: string
  month?: string
}

export interface DailyLossCreateRequest {
  storeId: string
  lossDate: string
  itemId: number
  lossQuantity: number
  lossReason: string
}

function queryUrl(path: string, query: Record<string, string | undefined>) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value?.trim()) params.set(key, value.trim())
  }
  const suffix = params.toString()
  return suffix ? `${path}?${suffix}` : path
}

export function getDailyLossItems() {
  return apiGet<DailyLossItem[]>('/api/daily-loss/items').then((items) => items.map(normalizeDailyLossItem))
}

function normalizeDailyLossItem(item: DailyLossItem): DailyLossItem {
  const itemCode = item.itemCode || item.code || ''
  const itemName = item.itemName || item.name || ''
  const unit = item.unit || item.stockUnit || ''
  const categoryName = item.categoryName || item.category || ''
  const categoryCode = item.categoryCode || categoryName
  return {
    ...item,
    itemCode,
    itemName,
    unit,
    categoryName,
    categoryCode,
    code: item.code || itemCode,
    name: item.name || itemName,
    category: item.category || categoryName,
    stockUnit: item.stockUnit || unit,
    active: item.active ?? true,
  }
}

export function getDailyLossRecords(query: DailyLossRecordQuery = {}) {
  return apiGet<DailyLossRecord[]>(queryUrl('/api/daily-loss/records', {
    storeId: query.storeId,
    date: query.date,
    status: query.status,
  }))
}

export function getDailyLossReports(query: Pick<DailyLossRecordQuery, 'storeId' | 'month'> = {}) {
  return apiGet<DailyLossReport[]>(queryUrl('/api/daily-loss/reports', {
    storeId: query.storeId,
    month: query.month,
  }))
}

export function getTodayDailyLossReport() {
  return apiGet<DailyLossReport>('/api/daily-loss/reports/today')
}

export function getDailyLossRecord(id: string) {
  return apiGet<DailyLossRecord>(`/api/daily-loss/records/${encodeURIComponent(id)}`)
}

export function createDailyLossRecord(payload: DailyLossCreateRequest) {
  return apiPost<DailyLossRecord, DailyLossCreateRequest>('/api/daily-loss/records', payload)
}

export function saveDailyLossReport(payload: DailyLossReportSaveRequest) {
  return apiPost<DailyLossReport, DailyLossReportSaveRequest>('/api/daily-loss/reports', payload)
}

export function submitDailyLossRecord(id: string) {
  return apiPost<DailyLossRecord>(`/api/daily-loss/records/${encodeURIComponent(id)}/submit`)
}

export function submitDailyLossReport(id: string) {
  return apiPost<DailyLossReport>(`/api/daily-loss/reports/${encodeURIComponent(id)}/submit`)
}

export function approveDailyLossRecord(id: string, reviewNote?: string) {
  return apiPost<DailyLossRecord, { reviewNote?: string }>(
    `/api/daily-loss/records/${encodeURIComponent(id)}/approve`,
    reviewNote?.trim() ? { reviewNote: reviewNote.trim() } : {},
  )
}

export function reviewDailyLossReport(id: string, reviewNote?: string) {
  return apiPost<DailyLossReport, { reviewNote?: string }>(
    `/api/daily-loss/reports/${encodeURIComponent(id)}/review`,
    reviewNote?.trim() ? { reviewNote: reviewNote.trim() } : {},
  )
}

export function uploadDailyLossAttachments(id: string, files: File[], onProgress?: (percent: number) => void) {
  const form = new FormData()
  for (const file of files) form.append('files', file, file.name)
  return apiPostForm<DailyLossRecord>(
    `/api/daily-loss/records/${encodeURIComponent(id)}/attachments`,
    form,
    {
      timeout: 120_000,
      onUploadProgress: (event) => {
        if (!onProgress) return
        if (!event.total) return onProgress(0)
        onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
      },
    },
  )
}

export function uploadDailyLossReportAttachments(id: string, files: File[], onProgress?: (percent: number) => void) {
  const form = new FormData()
  for (const file of files) form.append('files', file, file.name)
  return apiPostForm<DailyLossReport>(
    `/api/daily-loss/reports/${encodeURIComponent(id)}/attachments`,
    form,
    {
      timeout: 120_000,
      onUploadProgress: (event) => {
        if (!onProgress) return
        if (!event.total) return onProgress(0)
        onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
      },
    },
  )
}

export async function fetchDailyLossAttachment(attachment: DailyLossAttachment, signal?: AbortSignal) {
  const response = await http.get<Blob>(attachment.downloadUrl || `/api/storage/attachments/${attachment.id}`, {
    responseType: 'blob',
    timeout: 120_000,
    signal,
  })
  return response.data
}

export async function downloadDailyLossPhotosZip(storeId: string, month: string) {
  const response = await http.get<Blob>(
    `/api/daily-loss/stores/${encodeURIComponent(storeId)}/months/${encodeURIComponent(month)}/photos.zip`,
    { responseType: 'blob', timeout: 120_000 },
  )
  const blob = response.data
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${storeId}-${month}-报损照片.zip`
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}
