import { apiDelete, apiGet, apiPost, apiPostForm } from './http'

export type ProfitImportSourceType = 'AUTO' | 'EXCEL' | 'CSV' | 'SCREENSHOT'

export interface ProfitImportRow {
  rowId: string
  storeId: string
  storeName: string
  month: string
  confidence: number
  values: Record<string, number>
  /** Existing target record values, supplied only when this one row may be overwritten. */
  existingValues?: Record<string, number>
  warnings: string[]
  errors: string[]
  existing: boolean
  status: string
}

export interface ProfitImportRecognizeResponse {
  importId: string
  sourceType: ProfitImportSourceType
  status: string
  rows: ProfitImportRow[]
  errors: string[]
}

export interface ProfitImportCommitRow {
  rowId: string
  storeId: string
  month: string
  overwrite: boolean
  values: Record<string, number>
  note?: string
}

export interface ProfitImportCommitResponse {
  saved: number
  skipped: number
  rows: ProfitImportRow[]
}

export interface ProfitImportPreviewJob {
  jobId: string
  status: 'QUEUED' | 'PARSING' | 'VALIDATING' | 'READY' | 'PARTIAL' | 'FAILED' | 'CONFIRMING' | 'COMPLETED' | 'CANCELLED'
  stage: string
  progress: number
  parsedRows: number
  validRows: number
  errorRows: number
  salesTotal: number
  fieldMappings: string[]
  rows: ProfitImportRow[]
  errors: string[]
  selectedMonth: string
  detectedMonths: string[]
  monthConflict: boolean
  /** Immutable scope captured by the preview task. */
  targetStoreId?: string
  targetStoreName?: string
  targetMonth?: string
  elapsedMs: number
}

export function recognizeProfitImport(
  file: File,
  options: { storeId: string; month: string; sourceType?: ProfitImportSourceType },
) {
  const form = new FormData()
  form.append('file', file)
  form.append('sourceType', options.sourceType || 'AUTO')
  form.append('storeId', options.storeId)
  form.append('month', options.month)
  return apiPostForm<ProfitImportRecognizeResponse>('/api/imports/profit/recognize', form, {
    timeout: PROFIT_PREVIEW_UPLOAD_TIMEOUT_MS,
  })
}

const PROFIT_PREVIEW_UPLOAD_TIMEOUT_MS = 60_000

export async function createProfitImportPreview(
  file: File,
  options: { storeId: string; month: string; sourceType?: ProfitImportSourceType },
): Promise<ProfitImportPreviewJob> {
  const form = new FormData()
  form.append('file', file)
  form.append('sourceType', options.sourceType || 'AUTO')
  form.append('storeId', options.storeId)
  form.append('month', options.month)
  return apiPostForm<ProfitImportPreviewJob>('/api/profit-imports/preview', form, {
    timeout: PROFIT_PREVIEW_UPLOAD_TIMEOUT_MS,
  })
}

export function getProfitImportPreview(jobId: string) {
  return apiGet<ProfitImportPreviewJob>(`/api/profit-imports/${encodeURIComponent(jobId)}`)
}

export function confirmProfitImportPreview(
  jobId: string,
  body: { rows: Array<{ rowId: string; overwrite: boolean }> },
) {
  return apiPost<ProfitImportCommitResponse, typeof body>(
    `/api/profit-imports/${encodeURIComponent(jobId)}/confirm`,
    body,
    { timeout: 60_000 },
  )
}

export function cancelProfitImportPreview(jobId: string) {
  return apiDelete<void>(`/api/profit-imports/${encodeURIComponent(jobId)}`)
}

export function commitProfitImport(rows: ProfitImportCommitRow[]) {
  return apiPost<ProfitImportCommitResponse, { rows: ProfitImportCommitRow[] }>('/api/imports/profit/commit', { rows })
}
