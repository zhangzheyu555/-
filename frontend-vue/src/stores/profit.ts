import { defineStore } from 'pinia'
import { getProfitDashboard, type ProfitBrand, type ProfitDashboard, type ProfitEntry, type ProfitSummary, type ProfitTrendPoint } from '../api/profit'

interface ProfitState {
  dashboard: ProfitDashboard | null
  /** Entries across all brands (same month + store scope), used by brand cards. */
  allBrandEntries: ProfitEntry[]
  month: string
  brandId: string
  storeId: string
  loading: boolean
  error: string
  requestSerial: number
}

const emptySummary: ProfitSummary = {
  month: '',
  storeCount: 0,
  entryCount: 0,
  sales: 0,
  income: 0,
  costSum: 0,
  expenseSum: 0,
  net: 0,
  margin: 0,
  riskStoreCount: 0,
}

export const useProfitStore = defineStore('profit', {
  state: (): ProfitState => ({
    dashboard: null,
    allBrandEntries: [],
    month: '',
    brandId: '',
    storeId: '',
    loading: false,
    error: '',
    requestSerial: 0,
  }),
  getters: {
    months: (state): string[] => state.dashboard?.months || [],
    brands: (state): ProfitBrand[] => state.dashboard?.brands || [],
    allEntries: (state): ProfitEntry[] => state.dashboard?.entries || [],
    entries(): ProfitEntry[] {
      if (!this.storeId) return this.allEntries
      return this.allEntries.filter((entry) => entry.storeId === this.storeId)
    },
    summary(): ProfitSummary {
      if (!this.dashboard) return emptySummary
      if (!this.storeId) return this.dashboard.summary
      return summarizeEntries(this.entries, this.dashboard.summary.month)
    },
    trend(): ProfitTrendPoint[] {
      return this.dashboard?.trend || []
    },
    storeOptions(): ProfitEntry[] {
      const seen = new Set<string>()
      return this.allEntries.filter((entry) => {
        if (seen.has(entry.storeId)) return false
        seen.add(entry.storeId)
        return true
      })
    },
    profitableStoreCount(): number {
      return this.entries.filter((entry) => amount(entry.net) > 0).length
    },
    lossStoreCount(): number {
      return this.entries.filter((entry) => amount(entry.net) < 0).length
    },
    topProfitStores(): ProfitEntry[] {
      return [...this.entries].sort((a, b) => amount(b.net) - amount(a.net)).slice(0, 5)
    },
    lowProfitStores(): ProfitEntry[] {
      return [...this.entries].sort((a, b) => amount(a.net) - amount(b.net)).slice(0, 5)
    },
    topRevenueStores(): ProfitEntry[] {
      return [...this.entries].sort((a, b) => amount(b.income ?? b.sales) - amount(a.income ?? a.sales)).slice(0, 5)
    },
    riskStores(): ProfitEntry[] {
      return this.entries.filter(isRiskEntry).sort((a, b) => amount(a.net) - amount(b.net)).slice(0, 10)
    },
    headline(): string {
      const summary = this.summary
      if (!summary.entryCount) return '当前月份暂无利润数据，请先完成财务录入或数据导入。'
      return `${summary.month} 净利润 ${money(summary.net)}，利润率 ${percent(summary.margin)}，亏损门店 ${this.lossStoreCount} 家，需要关注 ${summary.riskStoreCount} 家。`
    },
  },
  actions: {
    async load() {
      const requestSerial = ++this.requestSerial
      this.loading = true
      this.error = ''
      try {
        const filterParams = {
          month: this.month || undefined,
          brandId: this.brandId || undefined,
          storeId: this.storeId || undefined,
        }
        // Fetch both the filtered dashboard and the all-brand entries in parallel.
        // When no brandId is set the two calls are identical; skip the duplicate.
        const allBrandPromise = this.brandId
          ? getProfitDashboard({ month: filterParams.month, storeId: filterParams.storeId })
          : null
        const [data, allBrandData] = await Promise.all([
          getProfitDashboard(filterParams),
          allBrandPromise,
        ])
        if (requestSerial !== this.requestSerial) return
        this.dashboard = data
        this.allBrandEntries = allBrandData?.entries
          ?? (this.brandId ? [] : data.entries)
        this.month = data.summary?.month || this.month || data.months?.[0] || ''
      } catch (error) {
        if (requestSerial !== this.requestSerial) return
        this.error = normalizeProfitError(error)
      } finally {
        if (requestSerial === this.requestSerial) this.loading = false
      }
    },
    setFilters(filters: { month?: string; brandId?: string; storeId?: string }) {
      this.month = filters.month || ''
      this.brandId = filters.brandId || ''
      this.storeId = filters.storeId || ''
    },
    async setMonth(month: string) {
      this.month = month
      await this.load()
    },
    async setBrand(brandId: string) {
      this.brandId = brandId
      this.storeId = ''
      await this.load()
    },
    setStore(storeId: string) {
      this.storeId = storeId
    },
  },
})

export function isRiskEntry(entry: ProfitEntry) {
  if (amount(entry.net) < 0) return true
  if (entry.risk && !['健康', '正常'].includes(entry.risk)) return true
  const margin = amount(entry.margin)
  return margin > 0 && margin < 0.08
}

export function riskStatus(entry: ProfitEntry) {
  if (amount(entry.net) < 0) return '亏损门店'
  if (entry.risk && !['健康', '正常'].includes(entry.risk)) return '需要关注'
  if (amount(entry.margin) < 0.08) return '利润率偏低'
  return '正常'
}

export function money(value: unknown) {
  const n = amount(value)
  return `¥${Math.round(n).toLocaleString('zh-CN')}`
}

export function percent(value: unknown) {
  return `${(amount(value) * 100).toFixed(1)}%`
}

export function amount(value: unknown) {
  const n = Number(value ?? 0)
  return Number.isFinite(n) ? n : 0
}

function summarizeEntries(entries: ProfitEntry[], month: string): ProfitSummary {
  const sales = sum(entries.map((entry) => entry.sales))
  const income = sum(entries.map((entry) => entry.income))
  const costSum = sum(entries.map((entry) => entry.costSum))
  const expenseSum = sum(entries.map((entry) => entry.expenseSum))
  const net = sum(entries.map((entry) => entry.net))
  return {
    month,
    storeCount: new Set(entries.map((entry) => entry.storeId)).size,
    entryCount: entries.length,
    sales,
    income,
    costSum,
    expenseSum,
    net,
    margin: income === 0 ? 0 : net / income,
    riskStoreCount: entries.filter(isRiskEntry).length,
  }
}

function sum(values: unknown[]): number {
  return values.reduce<number>((total, value) => total + amount(value), 0)
}

function normalizeProfitError(error: unknown) {
  const message = error instanceof Error ? error.message : ''
  if (!message || /系统处理失败|request failed|java\.|exception|500|sql/i.test(message)) {
    return '利润数据加载失败，请稍后重试。'
  }
  return message
}
