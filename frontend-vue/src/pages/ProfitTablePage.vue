<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Download } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import type { ProfitEntry } from '../api/profit'
import BrandBadge from '../components/common/BrandBadge.vue'
import BrandSelect from '../components/common/BrandSelect.vue'
import { amount, money, percent, useProfitStore } from '../stores/profit'
import { getBrandIdLike, normalizeBrandName, STANDARD_BRANDS } from '../utils/brand'

type ReportMode = 'single' | 'summary'
type BrandOption = { value: string; name: string; sortOrder: number }
type StoreOption = ProfitEntry & { brandKey: string; normalizedBrandName: string }

const canonicalBrandNames = STANDARD_BRANDS.map((brand) => brand.name)

const route = useRoute()
const router = useRouter()
const profit = useProfitStore()
const reportMode = ref<ReportMode>(isSummaryMode(route.query.mode) ? 'summary' : 'single')

const selectedMonth = computed(() => String(route.query.month || profit.month || profit.summary.month || ''))
const monthOptions = computed(() => profit.months.length ? profit.months : Array.from(new Set(profit.allEntries.map((entry) => entry.month).filter(Boolean))))
const allStoreOptions = computed<StoreOption[]>(() => {
  const seen = new Set<string>()
  return profit.allEntries
    .filter((entry) => {
      if (!entry.storeId || seen.has(entry.storeId)) return false
      seen.add(entry.storeId)
      return true
    })
    .map((entry) => ({
      ...entry,
      brandKey: entryBrandKey(entry),
      normalizedBrandName: normalizeBrandName(entry.brandName || ''),
    }))
})
const brandOptions = computed<BrandOption[]>(() => {
  const byName = new Map<string, BrandOption>()
  for (const brand of [...profit.brands].sort((a, b) => (a.sortOrder ?? 99) - (b.sortOrder ?? 99))) {
    const name = normalizeBrandName(brand.name)
    if (!name) continue
    byName.set(name, { value: String(brand.id), name, sortOrder: brand.sortOrder ?? canonicalBrandNames.indexOf(name) })
  }
  for (const entry of allStoreOptions.value) {
    if (!entry.normalizedBrandName || byName.has(entry.normalizedBrandName)) continue
    byName.set(entry.normalizedBrandName, {
      value: entry.brandKey,
      name: entry.normalizedBrandName,
      sortOrder: canonicalBrandNames.indexOf(entry.normalizedBrandName),
    })
  }
  for (const name of canonicalBrandNames) {
    if (!byName.has(name)) byName.set(name, { value: `name:${name}`, name, sortOrder: canonicalBrandNames.indexOf(name) })
  }
  return Array.from(byName.values()).sort((a, b) => {
    const aOrder = a.sortOrder >= 0 ? a.sortOrder : 99
    const bOrder = b.sortOrder >= 0 ? b.sortOrder : 99
    return aOrder - bOrder || a.name.localeCompare(b.name, 'zh-CN')
  })
})
const selectedBrandId = computed(() => {
  const requestedBrandId = String(route.query.brandId || '')
  if (requestedBrandId && brandOptions.value.some((brand) => brand.value === requestedBrandId)) return requestedBrandId
  if (reportMode.value === 'single') {
    const requestedStoreId = String(route.query.storeId || '')
    const requestedStore = allStoreOptions.value.find((store) => store.storeId === requestedStoreId)
    return requestedStore?.brandKey || allStoreOptions.value[0]?.brandKey || brandOptions.value[0]?.value || ''
  }
  return ''
})
const storeOptions = computed(() => storesForBrand(selectedBrandId.value))
const selectedStoreId = computed(() => {
  const requestedStoreId = String(route.query.storeId || '')
  if (requestedStoreId && storeOptions.value.some((store) => store.storeId === requestedStoreId)) return requestedStoreId
  return reportMode.value === 'single' ? storeOptions.value[0]?.storeId || '' : requestedStoreId
})
const selectedStore = computed(() => storeOptions.value.find((store) => store.storeId === selectedStoreId.value) || storeOptions.value[0])
const monthRows = computed(() => profit.allEntries.filter((entry) => !selectedMonth.value || entry.month === selectedMonth.value))
const brandRows = computed(() => monthRows.value.filter((entry) => matchesBrand(entry, selectedBrandId.value)))
const singleEntry = computed(() => brandRows.value.find((entry) => entry.storeId === selectedStoreId.value) || null)
const allRows = computed(() => [...brandRows.value].sort((a, b) => amount(b.net) - amount(a.net)))
const totalRow = computed(() => summarize(allRows.value))
const visibleRows = computed(() => (reportMode.value === 'single' ? (singleEntry.value ? [singleEntry.value] : []) : allRows.value))

async function refresh() {
  await profit.load()
}

function updateMonth(value: string) {
  const query = { ...route.query }
  if (value) query.month = value
  else delete query.month
  void router.push({ path: '/profit-table', query })
}

function updateBrand(value: string) {
  const query = { ...route.query }
  if (value) query.brandId = value
  else delete query.brandId
  if (reportMode.value === 'single') {
    const nextStore = storesForBrand(value)[0]
    if (nextStore) query.storeId = nextStore.storeId
    else delete query.storeId
  } else {
    delete query.storeId
  }
  void router.push({ path: '/profit-table', query })
}

function updateStore(value: string) {
  const query = { ...route.query }
  if (value) query.storeId = value
  else delete query.storeId
  const store = allStoreOptions.value.find((item) => item.storeId === value)
  if (store?.brandKey) query.brandId = store.brandKey
  void router.push({ path: '/profit-table', query })
}

function selectValue(event: Event) {
  return (event.target as HTMLSelectElement).value
}

function setMode(mode: ReportMode) {
  reportMode.value = mode
  const query = { ...route.query, mode } as Record<string, string | undefined>
  if (mode === 'summary') {
    delete query.storeId
  } else {
    const brandId = selectedBrandId.value || brandOptions.value[0]?.value || ''
    if (brandId) query.brandId = brandId
    const nextStore = storesForBrand(brandId)[0]
    if (nextStore) query.storeId = nextStore.storeId
  }
  void router.push({ path: '/profit-table', query })
}

function showSingle(entry: ProfitEntry) {
  reportMode.value = 'single'
  void router.push({ path: '/profit-table', query: { ...route.query, mode: 'single', brandId: entryBrandKey(entry), storeId: entry.storeId } })
}

function field(entry: ProfitEntry | null, key: keyof ProfitEntry) {
  return amount(entry?.[key])
}

function displayMoney(value: unknown, dashWhenZero = false) {
  const n = amount(value)
  if (dashWhenZero && n === 0) return '—'
  return money(n)
}

function gross(entry: ProfitEntry | null) {
  if (!entry) return 0
  if (entry.gross !== undefined) return amount(entry.gross)
  return amount(entry.income ?? entry.sales) - amount(entry.costSum)
}

function grossMargin(entry: ProfitEntry | null) {
  const income = amount(entry?.income ?? entry?.sales)
  return income === 0 ? 0 : gross(entry) / income
}

function otherExpense(entry: ProfitEntry | null) {
  if (!entry) return 0
  if (entry.expOther !== undefined) return amount(entry.expOther)
  const known = amount(entry.rent) + amount(entry.labor) + amount(entry.utility) + amount(entry.property) + amount(entry.commission) + amount(entry.promo) + amount(entry.repair) + amount(entry.equip)
  return Math.max(0, amount(entry.expenseSum) - known)
}

function summarize(rows: ProfitEntry[]) {
  const total = rows.reduce(
    (sum, entry) => ({
      sales: sum.sales + amount(entry.sales),
      refund: sum.refund + amount(entry.refund),
      discount: sum.discount + amount(entry.discount),
      income: sum.income + amount(entry.income ?? entry.sales),
      costSum: sum.costSum + amount(entry.costSum),
      gross: sum.gross + gross(entry),
      expenseSum: sum.expenseSum + amount(entry.expenseSum),
      net: sum.net + amount(entry.net),
    }),
    { sales: 0, refund: 0, discount: 0, income: 0, costSum: 0, gross: 0, expenseSum: 0, net: 0 },
  )
  return { ...total, margin: total.income === 0 ? 0 : total.net / total.income }
}

function isSummaryMode(value: unknown) {
  return value === 'summary' || value === 'all'
}

function entryBrandKey(entry: Pick<ProfitEntry, 'brandId' | 'brandName'>) {
  return getBrandIdLike(entry)
}

function brandNameByValue(value: string) {
  if (!value) return ''
  return brandOptions.value.find((brand) => brand.value === value)?.name || (value.startsWith('name:') ? value.slice(5) : '')
}

function matchesBrand(entry: ProfitEntry, brandId: string) {
  if (!brandId) return true
  if (String(entry.brandId || '') === brandId) return true
  const brandName = brandNameByValue(brandId)
  return Boolean(brandName && normalizeBrandName(entry.brandName || '') === brandName)
}

function storesForBrand(brandId: string) {
  return allStoreOptions.value.filter((store) => matchesBrand(store, brandId))
}

function exportProfitTable() {
  const headers = reportMode.value === 'single'
    ? ['项目', '金额']
    : ['门店', '品牌', '营业额', '成本合计', '费用合计', '净利润', '净利率']
  const rows = reportMode.value === 'single' && singleEntry.value
    ? [
        ['营业总收入', field(singleEntry.value, 'sales')],
        ['退款金额', field(singleEntry.value, 'refund')],
        ['优惠金额', field(singleEntry.value, 'discount')],
        ['实收收入', amount(singleEntry.value.income ?? singleEntry.value.sales)],
        ['原材料成本', field(singleEntry.value, 'material')],
        ['包材成本', field(singleEntry.value, 'packaging')],
        ['损耗成本', field(singleEntry.value, 'loss')],
        ['成本合计', field(singleEntry.value, 'costSum')],
        ['毛利润', gross(singleEntry.value)],
        ['费用合计', field(singleEntry.value, 'expenseSum')],
        ['净利润', field(singleEntry.value, 'net')],
        ['净利率', percent(singleEntry.value.margin)],
      ]
    : allRows.value.map((entry) => [
        entry.storeName || entry.storeId,
        normalizeBrandName(entry.brandName || ''),
        amount(entry.income ?? entry.sales).toFixed(2),
        amount(entry.costSum).toFixed(2),
        amount(entry.expenseSum).toFixed(2),
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
  link.download = reportMode.value === 'single'
    ? `${selectedStore.value?.storeName || selectedStoreId.value}-${selectedMonth.value}-利润表.csv`
    : `${selectedMonth.value}-${brandNameByValue(selectedBrandId.value) || '全部品牌'}-利润汇总.csv`
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="legacy-report-page">
    <div class="page-head">
      <h2>利润表</h2>
      <div class="report-mode-seg" role="tablist" aria-label="利润表模式">
        <button class="seg-button" :class="{ active: reportMode === 'single' }" type="button" @click="setMode('single')">单店利润表</button>
        <button class="seg-button" :class="{ active: reportMode === 'summary' }" type="button" @click="setMode('summary')">全部门店汇总</button>
      </div>
    </div>

    <div v-if="profit.error" class="error-box">{{ profit.error }}</div>

    <section class="report-filter-card">
      <div class="filter-left">
        <BrandSelect :model-value="selectedBrandId" :brands="brandOptions" :allow-all="reportMode === 'summary'" @change="updateBrand" />
        <select v-if="reportMode === 'single'" :value="selectedStoreId" aria-label="门店" @change="updateStore(selectValue($event))">
          <option v-if="!storeOptions.length" value="">暂无门店</option>
          <option v-for="store in storeOptions" :key="store.storeId" :value="store.storeId">
            {{ store.normalizedBrandName }} · {{ store.storeName || store.storeCode || store.storeId }}
          </option>
        </select>
        <select :value="selectedMonth" aria-label="月份" @change="updateMonth(selectValue($event))">
          <option v-for="month in monthOptions" :key="month" :value="month">{{ month }}</option>
        </select>
      </div>
      <button class="legacy-export-button" type="button" :disabled="!visibleRows.length" @click="exportProfitTable">
        <Download :size="16" />
        {{ reportMode === 'single' ? '导出利润表' : '导出汇总' }}
      </button>
    </section>

    <div v-if="profit.loading && !profit.dashboard" class="empty-state">正在读取利润表...</div>

    <template v-else-if="reportMode === 'single'">
      <section v-if="!singleEntry" class="legacy-report-card">
        <div class="empty-state compact">
          <b>{{ selectedStore?.storeName || selectedStoreId }} {{ selectedMonth }} 暂无数据</b>
          去「数据录入」录入这家店这个月的数据。
        </div>
      </section>
      <section v-else class="legacy-report-card">
        <div class="statement-head">
          <b>{{ singleEntry.storeName || singleEntry.storeId }} · {{ selectedMonth }} 利润表</b>
          <span>单位：元</span>
        </div>
        <table class="pstmt">
          <tbody>
            <tr class="hd"><td>收入</td><td class="amt">金额</td></tr>
            <tr><td class="lbl">营业总收入</td><td class="amt">{{ money(singleEntry.sales) }}</td></tr>
            <tr class="sub"><td class="lbl">退款金额</td><td class="amt">{{ displayMoney(singleEntry.refund, true) }}</td></tr>
            <tr class="sub"><td class="lbl">优惠金额</td><td class="amt">{{ displayMoney(singleEntry.discount, true) }}</td></tr>
            <tr class="tot"><td class="lbl">实收收入</td><td class="amt">{{ money(singleEntry.income ?? singleEntry.sales) }}</td></tr>

            <tr class="hd"><td>成本</td><td /></tr>
            <tr class="sub"><td class="lbl">原材料成本</td><td class="amt">{{ displayMoney(singleEntry.material, true) }}</td></tr>
            <tr class="sub"><td class="lbl">包材成本</td><td class="amt">{{ displayMoney(singleEntry.packaging, true) }}</td></tr>
            <tr class="sub"><td class="lbl">损耗成本</td><td class="amt">{{ displayMoney(singleEntry.loss, true) }}</td></tr>
            <tr class="sub"><td class="lbl">其他成本</td><td class="amt">{{ displayMoney(singleEntry.costOther, true) }}</td></tr>
            <tr class="tot"><td class="lbl">成本合计</td><td class="amt">{{ money(singleEntry.costSum) }}</td></tr>
            <tr class="big"><td class="lbl">毛利润</td><td class="amt" :class="{ pos: gross(singleEntry) >= 0, neg: gross(singleEntry) < 0 }">{{ money(gross(singleEntry)) }}</td></tr>
            <tr class="sub"><td class="lbl">毛利率</td><td class="amt">{{ percent(grossMargin(singleEntry)) }}</td></tr>

            <tr class="hd"><td>费用</td><td /></tr>
            <tr class="sub"><td class="lbl">房租</td><td class="amt">{{ displayMoney(singleEntry.rent, true) }}</td></tr>
            <tr class="sub"><td class="lbl">人工工资</td><td class="amt">{{ displayMoney(singleEntry.labor, true) }}</td></tr>
            <tr class="sub"><td class="lbl">水电费</td><td class="amt">{{ displayMoney(singleEntry.utility, true) }}</td></tr>
            <tr class="sub"><td class="lbl">物业费</td><td class="amt">{{ displayMoney(singleEntry.property, true) }}</td></tr>
            <tr class="sub"><td class="lbl">平台手续费</td><td class="amt">{{ displayMoney(singleEntry.commission, true) }}</td></tr>
            <tr class="sub"><td class="lbl">推广费</td><td class="amt">{{ displayMoney(singleEntry.promo, true) }}</td></tr>
            <tr class="sub"><td class="lbl">维修费</td><td class="amt">{{ displayMoney(singleEntry.repair, true) }}</td></tr>
            <tr class="sub"><td class="lbl">设备费</td><td class="amt">{{ displayMoney(singleEntry.equip, true) }}</td></tr>
            <tr class="sub"><td class="lbl">其他费用</td><td class="amt">{{ displayMoney(otherExpense(singleEntry), true) }}</td></tr>
            <tr class="tot"><td class="lbl">费用合计</td><td class="amt">{{ money(singleEntry.expenseSum) }}</td></tr>
            <tr class="big"><td class="lbl">净利润</td><td class="amt" :class="{ pos: amount(singleEntry.net) >= 0, neg: amount(singleEntry.net) < 0 }">{{ money(singleEntry.net) }}</td></tr>
            <tr class="sub"><td class="lbl">净利率</td><td class="amt">{{ percent(singleEntry.margin) }}</td></tr>
          </tbody>
        </table>
      </section>
    </template>

    <section v-else class="legacy-report-card all-report-card">
      <div class="statement-head">
        <b>{{ selectedMonth }} · 全部门店利润汇总</b>
        <span>单位：元</span>
      </div>
      <div v-if="!allRows.length" class="empty-state compact">{{ selectedMonth }} 暂无数据，换个月份或先录入数据。</div>
      <div v-else class="legacy-table-wrap">
        <table class="legacy-summary-table">
          <thead>
            <tr>
              <th>门店</th>
              <th>品牌</th>
              <th class="r">营业额</th>
              <th class="r">成本合计</th>
              <th class="r">费用合计</th>
              <th class="r">净利润</th>
              <th class="r">净利率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in allRows" :key="`${entry.storeId}-${entry.month}`" class="click-row" @click="showSingle(entry)">
              <td class="store-name">{{ entry.storeName || entry.storeId }}</td>
              <td><BrandBadge :brand-name="entry.brandName || '-'" /></td>
              <td class="r">{{ money(entry.income ?? entry.sales) }}</td>
              <td class="r">{{ money(entry.costSum) }}</td>
              <td class="r">{{ money(entry.expenseSum) }}</td>
              <td class="r" :class="{ neg: amount(entry.net) < 0, pos: amount(entry.net) >= 0 }">{{ money(entry.net) }}</td>
              <td class="r">{{ percent(entry.margin) }}</td>
            </tr>
            <tr class="tot">
              <td>合计</td>
              <td>{{ allRows.length }} 家门店</td>
              <td class="r">{{ money(totalRow.income) }}</td>
              <td class="r">{{ money(totalRow.costSum) }}</td>
              <td class="r">{{ money(totalRow.expenseSum) }}</td>
              <td class="r" :class="{ neg: totalRow.net < 0, pos: totalRow.net >= 0 }">{{ money(totalRow.net) }}</td>
              <td class="r">{{ percent(totalRow.margin) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style scoped>
.legacy-report-page {
  display: grid;
  gap: 16px;
  max-width: 1120px;
}

.legacy-report-page .page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin: 0;
}

.legacy-report-page h2 {
  margin: 0;
  font-size: 24px;
  line-height: 1.2;
}

.report-mode-seg {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  border: 1px solid rgba(17, 24, 39, 0.06);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 10px 26px rgba(24, 28, 35, 0.06);
}

.seg-button {
  min-height: 32px;
  padding: 0 16px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--muted);
  font-size: 14px;
  font-weight: 900;
}

.seg-button.active {
  background: linear-gradient(135deg, #ff9743 0%, #8a4b2e 100%);
  color: #fff;
  box-shadow: 0 10px 20px rgba(238, 126, 62, 0.18);
}

.report-filter-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 74px;
  padding: 16px 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 14px 34px rgba(24, 28, 35, 0.05);
}

.filter-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex-wrap: wrap;
}

.report-filter-card select {
  min-height: 40px;
  min-width: 126px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  font-size: 14px;
  font-weight: 900;
}

.report-filter-card :deep(.brand-select-wrap select) {
  min-height: 40px;
  min-width: 126px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  font-size: 14px;
  font-weight: 900;
}

.report-filter-card select[aria-label="门店"] {
  min-width: 220px;
}

.brand-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 30px;
  padding: 5px 11px;
  border-radius: 999px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 13px;
  font-weight: 900;
}

.brand-tag .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary);
}

.legacy-export-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 40px;
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  font-size: 14px;
  font-weight: 900;
  white-space: nowrap;
}

.legacy-export-button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.legacy-report-card {
  overflow-x: auto;
  padding: 18px 20px 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 16px 38px rgba(24, 28, 35, 0.06);
}

.statement-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 9px;
}

.statement-head b {
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
}

.statement-head span {
  color: var(--muted);
  font-size: 12px;
}

.pstmt {
  width: 100%;
  min-width: 620px;
  border-collapse: collapse;
  font-size: 13.5px;
}

.pstmt td {
  padding: 9px 4px;
  border-bottom: 1px solid var(--line);
}

.pstmt td.lbl {
  color: var(--ink);
}

.pstmt td.amt {
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-weight: 700;
}

.pstmt tr.sub td {
  color: var(--muted);
  font-weight: 500;
}

.pstmt tr.hd td {
  padding-top: 16px;
  color: var(--primary);
  font-size: 13px;
  font-weight: 900;
}

.pstmt tr.tot td {
  border-top: 1px solid var(--line);
  font-weight: 900;
}

.pstmt tr.big td {
  padding-top: 12px;
  font-size: 15px;
  font-weight: 900;
}

.legacy-table-wrap {
  overflow-x: auto;
}

.legacy-summary-table {
  width: 100%;
  min-width: 840px;
  border-collapse: collapse;
  font-size: 13.5px;
}

.legacy-summary-table th,
.legacy-summary-table td {
  padding: 11px 10px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  vertical-align: middle;
}

.legacy-summary-table th {
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.legacy-summary-table .r {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.legacy-summary-table .store-name {
  font-weight: 900;
}

.brand-pill {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 12px;
  font-weight: 900;
  white-space: nowrap;
}

.click-row {
  cursor: pointer;
}

.click-row:hover {
  background: #fff8f2;
}

.tot {
  border-top: 2px solid var(--line);
  font-weight: 900;
}

.pos {
  color: var(--good);
}

.neg {
  color: var(--bad);
}

@media (max-width: 720px) {
  .legacy-report-page .page-head,
  .report-filter-card,
  .filter-left,
  .report-filter-card select,
  .legacy-export-button,
  .report-mode-seg {
    align-items: stretch;
    width: 100%;
  }

  .legacy-report-page .page-head,
  .report-filter-card,
  .filter-left {
    flex-direction: column;
  }

  .seg-button {
    flex: 1;
  }
}
</style>
