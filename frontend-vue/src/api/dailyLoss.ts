import { apiGet, apiPost, apiPostForm } from './http'

export interface DailyLossItem {
  id: number
  code?: string
  name: string
  stockUnit?: string
  unitPrice?: number
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

export interface DailyLossRecordQuery {
  storeId?: string
  date?: string
  status?: string
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
  return apiGet<DailyLossItem[]>('/api/daily-loss/items')
}

export function getDailyLossRecords(query: DailyLossRecordQuery = {}) {
  return apiGet<DailyLossRecord[]>(queryUrl('/api/daily-loss/records', {
    storeId: query.storeId,
    date: query.date,
    status: query.status,
  }))
}

export function getDailyLossRecord(id: string) {
  return apiGet<DailyLossRecord>(`/api/daily-loss/records/${encodeURIComponent(id)}`)
}

export function createDailyLossRecord(payload: DailyLossCreateRequest) {
  return apiPost<DailyLossRecord, DailyLossCreateRequest>('/api/daily-loss/records', payload)
}

export function submitDailyLossRecord(id: string) {
  return apiPost<DailyLossRecord>(`/api/daily-loss/records/${encodeURIComponent(id)}/submit`)
}

export function approveDailyLossRecord(id: string, reviewNote?: string) {
  return apiPost<DailyLossRecord, { reviewNote?: string }>(
    `/api/daily-loss/records/${encodeURIComponent(id)}/approve`,
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
