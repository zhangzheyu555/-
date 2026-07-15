import { http } from './http'

export interface ProfitRankingExportParams {
  month?: string
  brandId?: string | number
  storeId?: string
}

export async function downloadProfitRankingCsv(params: ProfitRankingExportParams, fallbackName: string) {
  return downloadCsv('/api/export/profit-ranking.csv', params, fallbackName)
}

export async function downloadExpenseCsv(params: ProfitRankingExportParams, fallbackName: string) {
  return downloadCsv('/api/export/expenses.csv', params, fallbackName)
}

export async function downloadSalaryCsv(params: ProfitRankingExportParams, fallbackName: string) {
  return downloadCsv('/api/export/salaries.csv', params, fallbackName)
}

async function downloadCsv(path: string, params: ProfitRankingExportParams, fallbackName: string) {
  const response = await http.get<Blob>(path, {
    responseType: 'blob',
    params: {
      month: params.month || undefined,
      brandId: params.brandId || undefined,
      storeId: params.storeId || undefined,
    },
  })
  // The page passes the approved business scope in fallbackName. Prefer it so the
  // downloaded filename stays aligned with the visible range even when the legacy
  // endpoint only returns a month-level Content-Disposition filename.
  downloadBlob(response.data, fallbackName || decodeFilename(String(response.headers['content-disposition'] || '')) || '数据导出.csv')
}

export function downloadBlob(blob: Blob, filename: string) {
  const blobUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = blobUrl
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(blobUrl)
}

function decodeFilename(disposition: string) {
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) return decodeURIComponent(encoded)
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return plain ? decodeURIComponent(plain) : ''
}
