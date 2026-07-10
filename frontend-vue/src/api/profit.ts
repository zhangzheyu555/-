import { apiGet } from './http'

export interface ProfitBrand {
  id: number
  code?: string
  name: string
  color?: string
  sortOrder?: number
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

export interface ProfitEntry {
  id?: number
  storeId: string
  storeCode?: string
  storeName?: string
  brandId?: number
  brandName?: string
  area?: string
  manager?: string
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

export interface ProfitTrendPoint {
  month: string
  income: number
  net: number
  margin: number
}

export interface ProfitDashboard {
  months: string[]
  brands: ProfitBrand[]
  summary: ProfitSummary
  entries: ProfitEntry[]
  trend: ProfitTrendPoint[]
}

export interface ProfitDashboardParams {
  month?: string
  brandId?: number | string
}

export function getProfitDashboard(params: ProfitDashboardParams = {}) {
  const search = new URLSearchParams()
  if (params.month) search.set('month', String(params.month))
  if (params.brandId) search.set('brandId', String(params.brandId))
  const query = search.toString()
  return apiGet<ProfitDashboard>(`/api/finance/dashboard${query ? `?${query}` : ''}`)
}
