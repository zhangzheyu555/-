<script setup lang="ts">
import { computed, watch } from 'vue'
import { Download, FileBarChart } from 'lucide-vue-next'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import BusinessScopeBar from '../components/common/BusinessScopeBar.vue'
import PageHeader from '../components/common/PageHeader.vue'
import { useBusinessScope } from '../composables/useBusinessScope'
import { amount, money, percent, useProfitStore } from '../stores/profit'
import type { ProfitEntry, ProfitTrendPoint } from '../api/profit'
import { getBrandTheme, normalizeBrandName, STANDARD_BRANDS } from '../utils/brand'

const router = useRouter()
const route = useRoute()
const profit = useProfitStore()
const scope = useBusinessScope()
let routeLoadSerial = 0

const standardBrandNames = STANDARD_BRANDS.map((brand) => brand.name)

const brandIncomeCards = computed(() => {
  const groups = new Map<string, { name: string; income: number; net: number; stores: number; totalStores: number; color: string; soft: string }>()
  for (const brand of profit.brands) {
    const name = normalizeBrandName(brand.name)
    if (!name || /[?�]/.test(name)) continue
    const theme = getBrandTheme(name, brand.color)
    groups.set(name, {
      name,
      income: 0,
      net: 0,
      stores: 0,
      totalStores: 0,
      color: theme.dark,
      soft: theme.soft,
    })
  }
  for (const name of standardBrandNames) {
    const theme = getBrandTheme(name)
    if (!groups.has(name)) groups.set(name, { name, income: 0, net: 0, stores: 0, totalStores: 0, color: theme.dark, soft: theme.soft })
  }
  for (const entry of profit.entries) {
    const key = normalizeBrandName(entry.brandName || '未分品牌')
    if (!key || /[?�]/.test(key)) continue
    const theme = getBrandTheme(key)
    const current = groups.get(key) || { name: key, income: 0, net: 0, stores: 0, totalStores: 0, color: theme.dark, soft: theme.soft }
    current.income += amount(entry.income ?? entry.sales)
    current.net += amount(entry.net)
    current.stores += 1
    current.totalStores += 1
    groups.set(key, current)
  }
  return Array.from(groups.values()).sort((a, b) => {
    const aIndex = standardBrandNames.indexOf(a.name)
    const bIndex = standardBrandNames.indexOf(b.name)
    if (aIndex !== -1 || bIndex !== -1) return (aIndex === -1 ? 99 : aIndex) - (bIndex === -1 ? 99 : bIndex)
    return b.income - a.income
  })
})

const currentBrandName = computed(() => normalizeBrandName(profit.brands.find((item) => String(item.id) === profit.brandId)?.name || ''))

const marginRanking = computed(() => {
  return [...profit.entries].sort((a, b) => amount(b.margin) - amount(a.margin))
})

const scopeStoreOptions = computed(() => profit.storeOptions.map((entry) => ({
  id: entry.storeId,
  name: entry.storeName || entry.storeCode || entry.storeId,
  brandId: entry.brandId,
  brandName: normalizeBrandName(entry.brandName || ''),
})))
const managerEntry = computed(() => profit.entries.find((entry) => entry.storeId === scope.boundStoreId.value) || profit.entries[0] || null)
const managerCostExpenseMissing = computed(() => !managerEntry.value
  || (amount(managerEntry.value.costSum) === 0 && amount(managerEntry.value.expenseSum) === 0))
const managerCompleteness = computed(() => {
  if (!managerEntry.value) return 0
  const checks = [
    amount(managerEntry.value.sales) > 0,
    amount(managerEntry.value.income ?? managerEntry.value.sales) > 0,
    amount(managerEntry.value.costSum) > 0,
    amount(managerEntry.value.expenseSum) > 0,
  ]
  return Math.round((checks.filter(Boolean).length / checks.length) * 100)
})
const managerTrend = computed(() => profit.trend.slice(-6))
const managerTrendMax = computed(() => Math.max(1, ...managerTrend.value.flatMap((point) => [
  Math.abs(amount(point.sales ?? point.income)),
  Math.abs(amount(point.net)),
])))
const managerComposition = computed(() => {
  const entry = managerEntry.value
  if (!entry) return []
  return [
    { label: '原材料', value: amount(entry.material), group: '成本' },
    { label: '包材与损耗', value: amount(entry.packaging) + amount(entry.loss), group: '成本' },
    { label: '其他成本', value: amount(entry.costOther), group: '成本' },
    { label: '人工工资', value: amount(entry.labor), group: '费用' },
    { label: '房租物业', value: amount(entry.rent) + amount(entry.property), group: '费用' },
    { label: '经营费用', value: amount(entry.utility) + amount(entry.commission) + amount(entry.promo) + amount(entry.repair) + amount(entry.equip) + amount(entry.expOther), group: '费用' },
  ].filter((item) => item.value > 0)
})
const managerCompositionMax = computed(() => Math.max(1, ...managerComposition.value.map((item) => item.value)))

const trendMax = computed(() => {
  const values = profit.trend.map((point) => Math.abs(amount(point.net)))
  return Math.max(...values, 1)
})

function openStoreDetail(entry?: ProfitEntry) {
  if (entry) {
    void router.push({ path: '/store-detail', query: { storeId: entry.storeId } })
    return
  }
  void router.push('/store-detail')
}

function selectBrandCard(brandName: string) {
  const brand = profit.brands.find((item) => normalizeBrandName(item.name) === brandName)
  if (!brand) return
  const brandId = currentBrandName.value === brandName ? '' : String(brand.id)
  updateFilterQuery({ brandId })
}

function selectMonth(month: string) {
  updateFilterQuery({ month })
}

function selectBrand(brandId: string) {
  updateFilterQuery({ brandId })
}

function selectStore(storeId: string) {
  updateFilterQuery({ storeId })
}

function clearBrand() {
  updateFilterQuery({ brandId: '' })
}

function openProfitTable() {
  const query: LocationQueryRaw = {
    month: profit.month || undefined,
    brandId: scope.isStoreManager.value ? undefined : profit.brandId || undefined,
    storeId: scope.isStoreManager.value ? undefined : profit.storeId || undefined,
    mode: scope.isStoreManager.value || profit.storeId ? 'single' : 'summary',
  }
  void router.push({ path: '/profit-table', query })
}

function updateFilterQuery(filters: { month?: string; brandId?: string; storeId?: string }) {
  const query: LocationQueryRaw = { ...route.query }
  for (const [key, value] of Object.entries(filters)) {
    if (value) query[key] = value
    else delete query[key]
  }
  if (scope.isStoreManager.value) {
    delete query.brandId
    delete query.storeId
  }
  void router.push({ path: '/profit', query })
}

function queryValue(value: unknown) {
  return Array.isArray(value) ? String(value[0] || '') : String(value || '')
}

async function applyRouteFilters() {
  const serial = ++routeLoadSerial
  const requestedMonth = queryValue(route.query.month)
  const requestedBrandId = queryValue(route.query.brandId)
  const requestedStoreId = queryValue(route.query.storeId)
  const month = /^\d{4}-\d{2}$/.test(requestedMonth) ? requestedMonth : profit.month
  const brandId = scope.scopedBrandId(/^\d+$/.test(requestedBrandId) ? requestedBrandId : '')
  const storeId = scope.scopedStoreId(requestedStoreId)
  const dashboardChanged = !profit.dashboard
    || profit.brandId !== brandId
    || profit.storeId !== storeId
    || Boolean(month && profit.summary.month !== month)

  profit.setFilters({ month, brandId, storeId })
  if (dashboardChanged) await profit.load()
  if (serial !== routeLoadSerial) return

  if (!scope.isStoreManager.value && !profit.error && profit.storeId && !profit.storeOptions.some((entry) => entry.storeId === profit.storeId)) {
    profit.setStore('')
  }

  const query: LocationQueryRaw = { ...route.query }
  if (profit.month) query.month = profit.month
  else delete query.month
  if (!scope.isStoreManager.value && profit.brandId) query.brandId = profit.brandId
  else delete query.brandId
  if (!scope.isStoreManager.value && profit.storeId) query.storeId = profit.storeId
  else delete query.storeId

  if (
    queryValue(route.query.month) !== queryValue(query.month)
    || queryValue(route.query.brandId) !== queryValue(query.brandId)
    || queryValue(route.query.storeId) !== queryValue(query.storeId)
  ) {
    await router.replace({ path: '/profit', query })
  }
}

function brandMargin(brand: { income: number; net: number }) {
  return brand.income === 0 ? 0 : brand.net / brand.income
}

function compactMoney(value: unknown) {
  const n = amount(value)
  if (Math.abs(n) >= 10000) return `${(n / 10000).toFixed(1)}万`
  return money(n).replace('¥', '')
}

function trendBarHeight(point: ProfitTrendPoint) {
  return `${Math.max(18, Math.round((Math.abs(amount(point.net)) / trendMax.value) * 124))}px`
}

function managerTrendWidth(value: unknown) {
  return `${Math.max(3, Math.round((Math.abs(amount(value)) / managerTrendMax.value) * 100))}%`
}

function trendMonthLabel(month: string) {
  const match = month.match(/\d{4}-(\d{2})/)
  return match ? `${Number(match[1])}月` : month
}

function marginClass(value: unknown) {
  const n = amount(value)
  if (n >= 0.3) return 'hi'
  if (n >= 0.15) return 'mid'
  return 'lo'
}

function brandPillStyle(name?: string) {
  const theme = getBrandTheme(name)
  return {
    '--pill-color': theme.main,
    '--pill-bg': theme.soft,
  }
}

function exportCsv() {
  if (!profit.entries.length) {
    window.alert('暂无可导出的数据')
    return
  }
  const headers = ['门店', '品牌', '月份', '营业额', '净利润', '净利率']
  const rows = profit.entries.map((entry) => [
    entry.storeName || entry.storeId,
    normalizeBrandName(entry.brandName || ''),
    entry.month,
    amount(entry.income ?? entry.sales).toFixed(2),
    amount(entry.net).toFixed(2),
    percent(entry.margin),
  ])
  const csv = [headers, ...rows]
    .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const blob = new Blob([`\ufeff${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `利润概览-${profit.summary.month || '当前月份'}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

watch(
  () => [route.query.month, route.query.brandId, route.query.storeId],
  () => { void applyRouteFilters() },
  { immediate: true },
)
</script>

<template>
  <section class="page-panel profit-overview-page">
    <PageHeader :title="scope.isStoreManager.value ? '本店经营概览' : '利润概览'">
      <template #actions>
        <button class="ghost-button profit-table-button" type="button" @click="openProfitTable">
          <FileBarChart :size="16" />
          查看利润表
        </button>
        <button class="ghost-button export-report-button" type="button" title="导出当前筛选结果为 CSV" :disabled="profit.loading" @click="exportCsv">
          <Download :size="16" />
          导出报表
        </button>
      </template>
    </PageHeader>

    <section class="profit-filter-bar" aria-label="利润筛选条件">
      <BusinessScopeBar
        :brands="profit.brands"
        :stores="scopeStoreOptions"
        :brand-id="profit.brandId"
        :store-id="profit.storeId"
        :disabled="profit.loading"
        @update:brand-id="selectBrand"
        @update:store-id="selectStore"
      />
      <label>
        月份
        <select :value="profit.month" :disabled="profit.loading" @change="selectMonth(($event.target as HTMLSelectElement).value)">
          <option v-for="month in profit.months" :key="month" :value="month">{{ month }}</option>
        </select>
      </label>
    </section>

    <div v-if="profit.loading && !profit.dashboard" class="empty-state">正在读取利润概览...</div>

    <template v-else>
      <div v-if="profit.error" class="data-region-error" role="alert">
        <span>{{ profit.error }}</span>
        <button type="button" @click="profit.load()">重新加载</button>
      </div>
      <div v-else-if="scope.isStoreManager.value" class="profit-metric-grid manager-metrics">
        <article class="content-card profit-metric-card revenue"><span>本月营业额</span><b>{{ money(profit.summary.sales) }}</b></article>
        <article class="content-card profit-metric-card"><span>实收收入</span><b>{{ money(profit.summary.income) }}</b></article>
        <article class="content-card profit-metric-card"><span>成本合计</span><b>{{ money(profit.summary.costSum) }}</b></article>
        <article class="content-card profit-metric-card"><span>费用合计</span><b>{{ money(profit.summary.expenseSum) }}</b></article>
        <article class="content-card profit-metric-card"><span>净利润</span><b>{{ money(profit.summary.net) }}</b></article>
        <article class="content-card profit-metric-card">
          <span>净利率</span>
          <b>{{ managerCostExpenseMissing ? '待补全' : percent(profit.summary.margin) }}</b>
        </article>
      </div>
      <div v-else class="profit-metric-grid">
        <article class="content-card profit-metric-card revenue">
          <span>合并营业收入</span>
          <b>{{ money(profit.summary.income || profit.summary.sales) }}</b>
        </article>
        <article class="content-card profit-metric-card">
          <span>合并净利润</span>
          <b>{{ money(profit.summary.net) }}</b>
          <small>{{ profit.lossStoreCount }} 家亏损门店</small>
        </article>
        <article class="content-card profit-metric-card">
          <span>整体净利率</span>
          <b>{{ percent(profit.summary.margin) }}</b>
          <small>{{ profit.summary.riskStoreCount }} 家需要关注</small>
        </article>
        <article class="content-card profit-metric-card">
          <span>已录入门店</span>
          <b>{{ profit.summary.storeCount }}</b>
          <small>{{ profit.summary.entryCount }} 条利润记录</small>
        </article>
      </div>

      <div v-if="scope.isStoreManager.value" class="manager-completeness" :class="{ warning: managerCostExpenseMissing }">
        <span><b>数据完整度 {{ managerEntry ? `${managerCompleteness}%` : '待补全' }}</b> · {{ profit.summary.month || profit.month }}</span>
        <strong v-if="managerCostExpenseMissing">成本费用尚未录完整，暂不判断经营状态。</strong>
        <strong v-else-if="managerEntry">本月收入、成本和费用数据已形成经营结果。</strong>
        <strong v-else>当前月份尚未录入经营数据。</strong>
      </div>

      <section v-if="!scope.isStoreManager.value" class="profit-brand-section">
        <div class="profit-section-title">品牌卡片</div>
        <div v-if="profit.error" class="empty-state compact">品牌数据暂时无法读取。</div>
        <div v-else-if="!brandIncomeCards.length" class="empty-state compact">当前月份暂无品牌收入数据。</div>
        <div v-else class="profit-brand-grid">
          <button
            v-for="brand in brandIncomeCards"
            :key="brand.name"
            class="profit-brand-card"
            :class="{ selected: currentBrandName === brand.name, dim: Boolean(currentBrandName && currentBrandName !== brand.name) }"
            :style="{ '--brand-color': brand.color }"
            type="button"
            @click="selectBrandCard(brand.name)"
          >
            <span v-if="currentBrandName === brand.name" class="brand-selected">查看中</span>
            <span class="brand-name">{{ brand.name }}</span>
            <span class="brand-count">{{ brand.stores }} / {{ brand.totalStores || brand.stores }} 家已录</span>
            <span class="brand-row">
              <span>
                <b>{{ money(brand.net).replace('¥', '') }}</b>
                <small>净利</small>
              </span>
              <span class="brand-meta">
                营收<br>
                <strong>{{ money(brand.income).replace('¥', '') }}</strong><br>
                利率 {{ percent(brandMargin(brand)) }}
              </span>
            </span>
          </button>
        </div>
      </section>

      <section v-if="!scope.isStoreManager.value" class="content-card profit-ranking-card">
        <div class="profit-ranking-title">
          {{ currentBrandName ? `${currentBrandName} 各店排名（${profit.summary.month || profit.month}）` : `各店净利率排名（${profit.summary.month || profit.month}）` }}
          <button v-if="currentBrandName" class="show-all-chip" type="button" @click="clearBrand">显示全部品牌</button>
        </div>
        <div v-if="profit.error" class="empty-state compact">门店排行暂时无法读取。</div>
        <div v-else-if="!marginRanking.length" class="empty-state compact">当前筛选条件下暂无门店利润排行。</div>
        <div v-else class="profit-table-wrap">
          <table class="profit-ranking-table">
            <thead>
              <tr>
                <th>#</th>
                <th>门店</th>
                <th>品牌</th>
                <th class="r">营收</th>
                <th class="r">净利</th>
                <th class="r">净利率</th>
                <th class="r"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(entry, index) in marginRanking" :key="`${entry.storeId}-${entry.month}`" @click="openStoreDetail(entry)">
                <td class="rank-no">{{ index + 1 }}</td>
                <td class="store-name">{{ entry.storeName || entry.storeId }}</td>
                <td>
                  <span class="brand-pill" :style="brandPillStyle(entry.brandName || '')">
                    <span class="dotc" />
                    {{ normalizeBrandName(entry.brandName || '-') }}
                  </span>
                </td>
                <td class="r">{{ money(entry.income ?? entry.sales) }}</td>
                <td class="r" :class="{ negative: amount(entry.net) < 0 }">{{ money(entry.net) }}</td>
                <td class="r margin-cell" :class="marginClass(entry.margin)">{{ percent(entry.margin) }}</td>
                <td class="r chev">›</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="scope.isStoreManager.value" class="manager-insight-grid">
        <article class="content-card manager-trend-card">
          <div class="profit-ranking-title">近6个月营业额与净利润趋势</div>
          <div v-if="!managerTrend.length" class="empty-state compact">当前暂无月度经营趋势数据。</div>
          <div v-else class="manager-trend-list">
            <div v-for="point in managerTrend" :key="point.month" class="manager-trend-row">
              <span>{{ trendMonthLabel(point.month) }}</span>
              <div>
                <i class="sales" :style="{ width: managerTrendWidth(point.sales ?? point.income) }" />
                <small>营业额 {{ money(point.sales ?? point.income) }}</small>
              </div>
              <div>
                <i class="net" :class="{ negative: amount(point.net) < 0 }" :style="{ width: managerTrendWidth(point.net) }" />
                <small>净利润 {{ money(point.net) }}</small>
              </div>
            </div>
          </div>
        </article>
        <article class="content-card manager-composition-card">
          <div class="profit-ranking-title">本月成本与费用构成</div>
          <div v-if="!managerComposition.length" class="empty-state compact">成本费用尚未录完整。</div>
          <div v-else class="manager-composition-list">
            <div v-for="item in managerComposition" :key="`${item.group}-${item.label}`">
              <span><b>{{ item.label }}</b><small>{{ item.group }} · {{ money(item.value) }}</small></span>
              <i><em :style="{ width: `${Math.max(4, Math.round(item.value / managerCompositionMax * 100))}%` }" /></i>
            </div>
          </div>
        </article>
      </section>

      <section v-else class="content-card profit-trend-card">
        <div class="profit-ranking-title">月度净利趋势</div>
        <div v-if="profit.error" class="empty-state compact">趋势数据暂时无法读取。</div>
        <div v-else-if="!profit.trend.length" class="empty-state compact">当前暂无月度净利趋势数据。</div>
        <div v-else class="profit-trend-bars">
          <div v-for="point in profit.trend" :key="point.month" class="profit-trend-item">
            <span class="trend-value" :class="{ negative: amount(point.net) < 0 }">{{ compactMoney(point.net) }}</span>
            <div class="trend-track">
              <i :class="{ negative: amount(point.net) < 0 }" :style="{ height: trendBarHeight(point) }" />
            </div>
            <span class="trend-label">{{ trendMonthLabel(point.month) }}</span>
          </div>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.profit-overview-page {
  display: grid;
  height: auto !important;
  min-height: 0 !important;
  max-height: none !important;
  flex: none !important;
  gap: 16px;
  max-width: 1180px;
  padding-top: 4px;
  overflow: visible !important;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.profit-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.profit-overview-page :deep(.business-page-header) {
  align-items: end;
  margin-bottom: 0;
}

.profit-overview-page h2 {
  margin: 0;
  font-size: 24px;
  line-height: 1.2;
}

.profit-filter-bar {
  display: flex;
  align-items: end;
  gap: 14px;
  min-height: 56px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}

.profit-filter-bar label {
  display: grid;
  gap: 5px;
  min-width: 132px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.profit-filter-bar select,
.profit-filter-bar :deep(select) {
  min-width: 128px;
  min-height: 38px;
  padding: 7px 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  font-weight: 700;
  outline: none;
}

.export-report-button {
  width: auto;
  min-height: 38px;
  margin-top: 0;
  padding: 8px 12px;
  border-radius: 9px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  font-weight: 900;
  box-shadow: none;
}

.profit-table-button {
  width: auto;
  min-height: 38px;
  padding: 8px 12px;
  border-radius: 9px;
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  font-weight: 800;
}

.profit-table-button:hover {
  border-color: var(--primary);
  background: var(--primary-dark, #c85f29);
  color: #fff;
}

.data-region-error {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid #f1d2c3;
  border-radius: 8px;
  background: #fff8f4;
  color: #9b4b2d;
  font-size: 13px;
}

.data-region-error button {
  flex: none;
  min-height: 30px;
  padding: 5px 10px;
  border: 1px solid #e8bba6;
  border-radius: 6px;
  background: #fff;
  color: #9b4b2d;
  font-weight: 700;
}

.export-report-button:hover:not(:disabled) {
  border-color: rgba(118, 189, 184, 0.38);
  background: #fff8f2;
  color: var(--primary);
}

.profit-toolbar label {
  display: grid;
  gap: 5px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.profit-toolbar select {
  min-width: 118px;
  min-height: 37px;
  padding: 7px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
}

.profit-toolbar .primary-button {
  min-height: 37px;
  padding: 8px 14px;
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  border: 1px solid var(--line);
  box-shadow: none;
  font-size: 13px;
}

.profit-metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.profit-metric-card {
  min-height: 96px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 16px 38px rgba(24, 28, 35, 0.06);
}

.profit-metric-card.revenue {
  color: #fff;
  border: 0;
  background: var(--ds-primary-hover);
  box-shadow: none;
}

.profit-metric-card span {
  display: block;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.profit-metric-card.revenue span,
.profit-metric-card.revenue small {
  color: rgba(255, 255, 255, 0.82);
}

.profit-metric-card b {
  display: block;
  margin-top: 9px;
  color: var(--ink);
  font-size: 32px;
  line-height: 1;
  letter-spacing: 0;
}

.profit-metric-card.revenue b {
  color: #fff;
  font-size: 33px;
}

.profit-metric-card:nth-child(2) b {
  color: var(--good);
}

.profit-metric-card small {
  display: block;
  margin-top: 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.profit-brand-section {
  min-width: 0;
}

.profit-section-title {
  margin-bottom: 10px;
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
}

.profit-brand-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.profit-brand-card {
  position: relative;
  min-height: 144px;
  overflow: hidden;
  padding: 18px 18px 16px;
  border: 0;
  border-radius: 16px;
  background: var(--brand-color);
  color: #fff;
  text-align: left;
  box-shadow: 0 16px 34px rgba(24, 28, 35, 0.14);
  transition: transform 0.16s ease, box-shadow 0.16s ease, opacity 0.16s ease;
}

.profit-brand-card:hover {
  transform: translateY(-5px) scale(1.012);
  box-shadow: 0 22px 44px rgba(24, 28, 35, 0.22);
}

.profit-brand-card.dim {
  opacity: 0.58;
}

.profit-brand-card.selected {
  outline: 3px solid rgba(255, 255, 255, 0.64);
  outline-offset: -7px;
}

.brand-selected {
  position: absolute;
  right: 12px;
  top: 12px;
  padding: 3px 9px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
  font-size: 12px;
  font-weight: 900;
}

.brand-name {
  font-size: 17px;
  font-weight: 900;
}

.brand-count {
  margin-top: 3px;
  color: rgba(255, 255, 255, 0.86);
  font-size: 13px;
  font-weight: 900;
}

.brand-row {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 12px;
  margin-top: 26px;
}

.brand-row b {
  display: block;
  font-size: 30px;
  line-height: 1;
  letter-spacing: 0;
}

.brand-row small,
.brand-meta {
  color: rgba(255, 255, 255, 0.88);
  font-size: 12px;
  font-weight: 800;
}

.brand-meta {
  text-align: right;
  line-height: 1.55;
}

.brand-meta strong {
  color: #fff;
  font-size: 17px;
}

.profit-ranking-card {
  padding: 18px 20px 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 16px 38px rgba(24, 28, 35, 0.06);
}

.profit-ranking-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 13px;
  color: var(--muted);
  font-size: 14px;
  font-weight: 900;
}

.show-all-chip {
  min-height: 24px;
  padding: 3px 10px;
  border: 1px solid rgba(118, 189, 184, 0.24);
  border-radius: 999px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 12px;
  font-weight: 900;
}

.profit-table-wrap {
  overflow-x: auto;
  overflow-y: hidden;
  max-height: none;
}

.profit-metric-grid.manager-metrics {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.manager-completeness {
  display: flex;
  min-height: 46px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: var(--ds-surface);
  color: var(--ds-secondary);
  font-size: 13px;
}

.manager-completeness strong {
  color: var(--ds-primary-hover);
  text-align: right;
}

.manager-completeness.warning {
  border-color: #efddb9;
  background: var(--ds-warning-soft);
  color: #87500f;
}

.manager-completeness.warning strong {
  color: #87500f;
}

.manager-insight-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(280px, .65fr);
  gap: 14px;
}

.manager-trend-card,
.manager-composition-card {
  min-width: 0;
  padding: 18px;
}

.manager-trend-list,
.manager-composition-list {
  display: grid;
  gap: 12px;
}

.manager-trend-row {
  display: grid;
  grid-template-columns: 42px repeat(2, minmax(0, 1fr));
  align-items: center;
  gap: 12px;
  color: var(--ds-secondary);
  font-size: 13px;
}

.manager-trend-row > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.manager-trend-row i {
  display: block;
  max-width: 100%;
  height: 7px;
  border-radius: 999px;
  background: var(--ds-primary);
}

.manager-trend-row i.net {
  background: var(--ds-success);
}

.manager-trend-row i.net.negative {
  background: var(--ds-danger);
}

.manager-trend-row small {
  overflow: hidden;
  color: var(--ds-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.manager-composition-list > div,
.manager-composition-list span {
  display: grid;
  gap: 4px;
}

.manager-composition-list span {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: baseline;
}

.manager-composition-list small {
  color: var(--ds-muted);
  font-size: 12px;
}

.manager-composition-list i {
  display: block;
  height: 7px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--ds-surface-muted);
}

.manager-composition-list em {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--ds-primary-hover);
}

.profit-ranking-table {
  width: 100%;
  min-width: 860px;
  border-collapse: collapse;
}

.profit-ranking-table th,
.profit-ranking-table td {
  padding: 13px 12px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  vertical-align: middle;
  font-size: 13px;
}

.profit-ranking-table th {
  background: transparent;
  color: var(--muted);
  font-weight: 900;
}

.profit-ranking-table tbody tr {
  cursor: pointer;
}

.profit-ranking-table tbody tr:hover {
  background: #fff8f2;
}

.profit-ranking-table .r {
  text-align: right;
}

.rank-no {
  width: 44px;
  color: var(--muted);
}

.store-name {
  font-weight: 900;
}

.brand-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 24px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--pill-bg);
  color: var(--pill-color);
  font-size: 12px;
  font-weight: 900;
  white-space: nowrap;
}

.brand-pill .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--pill-color);
}

.margin-cell {
  font-weight: 900;
}

.margin-cell.hi {
  color: var(--good);
}

.margin-cell.mid {
  color: var(--warn);
}

.margin-cell.lo {
  color: var(--bad);
}

.negative {
  color: var(--bad);
}

.profit-trend-card {
  min-height: 180px;
  padding: 18px 20px 22px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 16px 38px rgba(24, 28, 35, 0.06);
}

.profit-trend-bars {
  display: flex;
  align-items: end;
  justify-content: space-around;
  gap: 24px;
  min-height: 150px;
  padding: 8px 16px 0;
}

.profit-trend-item {
  display: grid;
  justify-items: center;
  align-items: end;
  gap: 8px;
  min-width: 58px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.trend-value {
  color: var(--ink);
  white-space: nowrap;
}

.trend-track {
  display: flex;
  align-items: end;
  justify-content: center;
  width: 44px;
  height: 128px;
}

.trend-track i {
  display: block;
  width: 34px;
  min-height: 18px;
  border-radius: 10px 10px 5px 5px;
  background: var(--ds-primary);
  box-shadow: none;
}

.trend-track i.negative {
  background: var(--ds-danger);
}

.trend-label {
  color: var(--muted);
}

@media (max-width: 980px) {
  .profit-metric-grid,
  .profit-brand-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .manager-insight-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .profit-overview-page :deep(.business-page-header),
  .profit-toolbar,
  .profit-filter-bar,
  .profit-filter-bar label,
  .profit-filter-bar select,
  .profit-filter-bar :deep(select),
  .profit-toolbar label,
  .profit-toolbar select,
  .profit-toolbar button,
  .profit-metric-grid,
  .profit-brand-grid {
    grid-template-columns: 1fr;
    width: 100%;
  }

  .profit-overview-page :deep(.business-page-header),
  .profit-toolbar,
  .profit-filter-bar {
    align-items: stretch;
  }

  .profit-filter-bar {
    flex-direction: column;
  }

  .manager-completeness {
    align-items: flex-start;
    flex-direction: column;
  }

  .manager-completeness strong {
    text-align: left;
  }

  .manager-trend-row {
    grid-template-columns: 38px minmax(0, 1fr);
  }

  .manager-trend-row > div:last-child {
    grid-column: 2;
  }

  .export-report-button {
    width: 100%;
  }

  .profit-metric-card,
  .profit-brand-card {
    min-height: 120px;
  }

  .profit-trend-bars {
    justify-content: flex-start;
    overflow-x: auto;
  }
}

.profit-overview-page :deep(.business-page-header) {
  align-items: center;
}

.profit-overview-page :deep(.business-page-actions label) {
  display: grid;
  gap: 5px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.profit-overview-page :deep(.business-page-actions select) {
  min-width: 118px;
  min-height: 37px;
  padding: 7px 10px;
  border-radius: 10px;
  font-size: 13px;
}

.profit-overview-page .profit-metric-grid .profit-metric-card:nth-child(2) b {
  color: var(--good);
}

.profit-ranking-card {
  padding: 18px 20px 20px;
}

.profit-ranking-table th {
  padding: 8px 10px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
}

.profit-ranking-table td {
  padding: 11px 10px;
  font-size: 13px;
}

.brand-pill {
  min-height: 22px;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 700;
}

.brand-pill .dotc {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--pill-color);
}

.chev {
  display: inline-block;
  width: 18px;
  color: var(--muted);
  font-size: 18px;
  font-weight: 900;
  opacity: 0;
  transition: opacity 0.12s ease, color 0.12s ease;
}

.profit-ranking-table tbody tr:hover .chev {
  color: var(--primary);
  opacity: 1;
}

.profit-trend-card {
  margin-bottom: 0;
}

.profit-overview-page > :last-child {
  margin-bottom: 0;
}
</style>
