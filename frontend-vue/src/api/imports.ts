import { ApiError, apiDelete, apiGet, apiPost, apiPostForm } from './http'

export type ProfitImportSourceType = 'AUTO' | 'EXCEL' | 'CSV' | 'SCREENSHOT'

export interface ProfitImportRow {
  rowId: string
  storeId: string
  storeName: string
  month: string
  confidence: number
  values: Record<string, number>
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
  elapsedMs: number
  legacy?: boolean
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
  try {
    return await apiPostForm<ProfitImportPreviewJob>('/api/profit-imports/preview', form, {
      timeout: PROFIT_PREVIEW_UPLOAD_TIMEOUT_MS,
    })
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 404) throw error
    const legacy = await recognizeProfitImport(file, options)
    const rows = legacy.rows || []
    const validRows = rows.filter((row) => row.status !== 'ERROR' && !row.errors.length).length
    const detectedMonths = [...new Set(rows.map((row) => row.month).filter(Boolean))].sort()
    const fallback: ProfitImportPreviewJob = {
      jobId: legacy.importId,
      status: legacy.status === 'READY' ? 'READY' : legacy.status === 'PARTIAL' ? 'PARTIAL' : 'FAILED',
      stage: legacy.status === 'READY' ? '等待确认' : '校验未通过',
      progress: 100,
      parsedRows: rows.length,
      validRows,
      errorRows: rows.length - validRows,
      salesTotal: rows.reduce((sum, row) => sum + Number(row.values.sales || 0), 0),
      fieldMappings: [],
      rows,
      errors: legacy.errors || [],
      selectedMonth: options.month,
      detectedMonths,
      monthConflict: detectedMonths.some((month) => month !== options.month),
      elapsedMs: 0,
      legacy: true,
    }
    return fallback
  }
}

export function getProfitImportPreview(jobId: string) {
  return apiGet<ProfitImportPreviewJob>(`/api/profit-imports/${encodeURIComponent(jobId)}`)
}

export function confirmProfitImportPreview(
  jobId: string,
  body: { confirmMonthConflict: boolean; rows: Array<{ rowId: string; overwrite: boolean }> },
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
