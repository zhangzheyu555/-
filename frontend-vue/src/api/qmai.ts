import { apiGet, apiPost, apiPut, http } from './http'

export interface QmaiMapping {
  qmaiShopId: string
  qmaiShopName: string
  storeId: string
  storeName?: string
}

export interface QmaiBatch {
  id: number
  month: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED'
  totalTasks: number
  completedTasks: number
  failedTasks: number
  dailyRows: number
  productRows: number
  errorSummary?: string
  requestedByName?: string
  createdAt?: string
  startedAt?: string
  finishedAt?: string
}

export interface QmaiConfig {
  credentialConfigured: boolean
  enabled: boolean
  displayName: string
  mappings: QmaiMapping[]
  latestBatch?: QmaiBatch | null
}

export interface QmaiStoreSummary {
  storeId: string
  storeName: string
  activeDays: number
  sourceRows: number
  receivable: string | number
  received: string | number
  cost: string | number
  refund: string | number
  grossProfit: string | number
  grossMargin: string | number
  lowMargin: boolean
}

export interface QmaiProductSummary {
  storeId: string
  storeName: string
  productKey: string
  productId?: string
  skuId?: string
  itemName: string
  categoryName?: string
  quantity: string | number
  refundQuantity: string | number
  receivable: string | number
  received: string | number
  cost: string | number
  refund: string | number
}

export interface QmaiSummary {
  month: string
  dataStatus: 'EMPTY' | 'READY'
  lastSyncedAt?: string
  receivable: string | number
  received: string | number
  cost: string | number
  refund: string | number
  grossProfit: string | number
  grossMargin: string | number
  stores: QmaiStoreSummary[]
  products: QmaiProductSummary[]
  latestBatch?: QmaiBatch | null
}

export interface QmaiDiscoveredShop {
  qmaiShopId: string
  qmaiShopName: string
}

export function getQmaiStatus() {
  return apiGet<QmaiConfig>('/api/qmai/status')
}

export function getQmaiConfig() {
  return apiGet<QmaiConfig>('/api/qmai/config')
}

export function saveQmaiConfig(payload: Pick<QmaiConfig, 'enabled' | 'displayName' | 'mappings'>) {
  return apiPut<QmaiConfig, typeof payload>('/api/qmai/config', payload)
}

export function discoverQmaiShops() {
  return apiGet<QmaiDiscoveredShop[]>('/api/qmai/shops', { timeout: 30_000 })
}

export function startQmaiSync(month: string) {
  return apiPost<QmaiBatch>(`/api/qmai/sync?month=${encodeURIComponent(month)}`, undefined, { timeout: 15_000 })
}

export function getQmaiSummary(month: string, storeId = '') {
  const query = new URLSearchParams({ month })
  if (storeId) query.set('storeId', storeId)
  return apiGet<QmaiSummary>(`/api/qmai/summary?${query.toString()}`)
}

export async function downloadQmaiExport(month: string, view: 'stores' | 'products', storeId = '') {
  const query = new URLSearchParams({ month, view })
  if (storeId) query.set('storeId', storeId)
  const response = await http.get<Blob>(`/api/qmai/export.csv?${query.toString()}`, {
    responseType: 'blob',
    timeout: 30_000,
  })
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = `企迈${view === 'products' ? '商品销售' : '营业额'}-${month}.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
