import { apiPost, apiPostForm } from './http'

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

export function recognizeProfitImport(
  file: File,
  options: { storeId: string; month: string; sourceType?: ProfitImportSourceType },
) {
  const form = new FormData()
  form.append('file', file)
  form.append('sourceType', options.sourceType || 'AUTO')
  form.append('storeId', options.storeId)
  form.append('month', options.month)
  return apiPostForm<ProfitImportRecognizeResponse>('/api/imports/profit/recognize', form)
}

export function commitProfitImport(rows: ProfitImportCommitRow[]) {
  return apiPost<ProfitImportCommitResponse, { rows: ProfitImportCommitRow[] }>('/api/imports/profit/commit', { rows })
}
