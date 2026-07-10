<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { Download } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import LegacyBrandCard from '../components/legacy/LegacyBrandCard.vue'
import LegacyPageHeader from '../components/legacy/LegacyPageHeader.vue'
import LegacyShell from '../components/legacy/LegacyShell.vue'
import LegacyStatCard from '../components/legacy/LegacyStatCard.vue'
import LegacyTableCard from '../components/legacy/LegacyTableCard.vue'
import BrandSelect from '../components/common/BrandSelect.vue'
import { amount, money, percent, useProfitStore } from '../stores/profit'
import type { ProfitEntry, ProfitTrendPoint } from '../api/profit'
import { getBrandTheme, normalizeBrandName, STANDARD_BRANDS } from '../utils/brand'

const router = useRouter()
const profit = useProfitStore()

const legacyBrandNames = STANDARD_BRANDS.map((brand) => brand.name)

const brandIncomeCards = computed(() => {
  const groups = new Map<string, { name: string; income: number; net: number; stores: number; totalStores: number; color: string; soft: string }>()
  for (const brand of profit.brands) {
    const name = normalizeBrandName(brand.name)
    const theme = getBrandTheme(name, brand.color)
    groups.set(name, {
      name,
      income: 0,
      net: 0,
      stores: 0,
      totalStores: 0,
      color: theme.main,
      soft: theme.soft,
    })
  }
  for (const name of legacyBrandNames) {
    const theme = getBrandTheme(name)
    if (!groups.has(name)) groups.set(name, { name, income: 0, net: 0, stores: 0, totalStores: 0, color: theme.main, soft: theme.soft })
  }
  for (const entry of profit.entries) {
    const key = normalizeBrandName(entry.brandName || '未分品牌')
    const theme = getBrandTheme(key)
    const current = groups.get(key) || { name: key, income: 0, net: 0, stores: 0, totalStores: 0, color: theme.main, soft: theme.soft }
    current.income += amount(entry.income ?? entry.sales)
    current.net += amount(entry.net)
    current.stores += 1
    current.totalStores += 1
    groups.set(key, current)
  }
  return Array.from(groups.values()).sort((a, b) => {
    const aIndex = legacyBrandNames.indexOf(a.name)
    const bIndex = legacyBrandNames.indexOf(b.name)
    if (aIndex !== -1 || bIndex !== -1) return (aIndex === -1 ? 99 : aIndex) - (bIndex === -1 ? 99 : bIndex)
    return b.income - a.income
  })
})

const currentBrandName = computed(() => normalizeBrandName(profit.brands.find((item) => String(item.id) === profit.brandId)?.name || ''))

const marginRanking = computed(() => {
  return [...profit.entries].sort((a, b) => amount(b.margin) - amount(a.margin))
})

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

function openBrandProfit(brandName: string) {
  const brand = profit.brands.find((item) => normalizeBrandName(item.name) === brandName)
  if (brand) {
    void profit.setBrand(String(brand.id))
    return
  }
  void router.push('/profit-table')
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

onMounted(() => {
  void profit.load()
})
</script>

<template>
  <LegacyShell class="legacy-profit-page">
    <LegacyPageHeader title="利润概览">
      <template #actions>
        <button class="ghost-button export-report-button" type="button" title="导出当前筛选结果为 CSV" :disabled="profit.loading" @click="exportCsv">
          <Download :size="16" />
          导出报表
        </button>
      </template>
    </LegacyPageHeader>

    <section class="profit-filter-bar" aria-label="利润筛选条件">
      <label>
        月份
        <select :value="profit.month" :disabled="profit.loading" @change="profit.setMonth(($event.target as HTMLSelectElement).value)">
          <option v-for="month in profit.months" :key="month" :value="month">{{ month }}</option>
        </select>
      </label>
      <label>
        品牌
        <BrandSelect :model-value="profit.brandId" :brands="profit.brands" :disabled="profit.loading" @change="profit.setBrand" />
      </label>
    </section>

    <div v-if="profit.error" class="error-box">{{ profit.error }}</div>
    <div v-if="profit.loading && !profit.dashboard" class="empty-state">正在读取利润概览...</div>

    <template v-else>
      <div class="legacy-kpis">
        <LegacyStatCard label="合并营业收入" :value="money(profit.summary.income || profit.summary.sales)" />
        <LegacyStatCard label="合并净利润" :value="money(profit.summary.net)" :hint="`${profit.lossStoreCount} 家亏损门店`" />
        <LegacyStatCard label="整体净利率" :value="percent(profit.summary.margin)" :hint="`${profit.summary.riskStoreCount} 家需要关注`" />
        <LegacyStatCard label="已录入门店" :value="profit.summary.storeCount" :hint="`${profit.summary.entryCount} 条利润记录`" />
      </div>

      <section class="legacy-brand-section">
        <div class="legacy-section-t">品牌卡片</div>
        <div v-if="!brandIncomeCards.length" class="empty-state compact">当前月份暂无品牌收入数据。</div>
        <div v-else class="legacy-brand-cards">
          <LegacyBrandCard
            v-for="brand in brandIncomeCards"
            :key="brand.name"
            :name="brand.name"
            :count="`${brand.stores} / ${brand.totalStores || brand.stores} 家已录`"
            :net="money(brand.net).replace('¥', '')"
            :revenue="money(brand.income).replace('¥', '')"
            :rate="percent(brandMargin(brand))"
            :color="brand.color"
            :selected="currentBrandName === brand.name"
            :dim="Boolean(currentBrandName && currentBrandName !== brand.name)"
            @click="openBrandProfit(brand.name)"
          />
        </div>
      </section>

      <LegacyTableCard>
        <div class="legacy-rank-title">
          {{ currentBrandName ? `${currentBrandName} 各店排名（${profit.summary.month || profit.month}）` : `各店净利率排名（${profit.summary.month || profit.month}）` }}
          <button v-if="currentBrandName" class="show-all-chip" type="button" @click="profit.setBrand('')">显示全部品牌</button>
        </div>
        <div v-if="!marginRanking.length" class="empty-state compact">当前月份暂无门店利润排行。</div>
        <div v-else class="legacy-table-wrap">
          <table class="legacy-table legacy-rank-table">
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
                  <span class="legacy-tag brand-pill" :style="brandPillStyle(entry.brandName || '')">
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
      </LegacyTableCard>

      <section class="legacy-card legacy-trend-card">
        <div class="legacy-rank-title">月度净利趋势</div>
        <div v-if="!profit.trend.length" class="empty-state compact">当前暂无月度净利趋势数据。</div>
        <div v-else class="legacy-trend-bars">
          <div v-for="point in profit.trend" :key="point.month" class="legacy-trend-item">
            <span class="trend-value" :class="{ negative: amount(point.net) < 0 }">{{ compactMoney(point.net) }}</span>
            <div class="trend-track">
              <i :class="{ negative: amount(point.net) < 0 }" :style="{ height: trendBarHeight(point) }" />
            </div>
            <span class="trend-label">{{ trendMonthLabel(point.month) }}</span>
          </div>
        </div>
      </section>
    </template>
  </LegacyShell>
</template>

<style scoped>
.legacy-profit-page {
  display: grid;
  gap: 16px;
  max-width: 1180px;
  padding-top: 14px;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.legacy-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.legacy-profit-page :deep(.page-head),
.legacy-profit-page .page-head {
  align-items: end;
  margin-bottom: 0;
}

.legacy-profit-page h2 {
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

.export-report-button:hover:not(:disabled) {
  border-color: rgba(238, 126, 62, 0.38);
  background: #fff8f2;
  color: var(--primary);
}

.legacy-toolbar label {
  display: grid;
  gap: 5px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.legacy-toolbar select {
  min-width: 118px;
  min-height: 37px;
  padding: 7px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
}

.legacy-toolbar .primary-button {
  min-height: 37px;
  padding: 8px 14px;
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  border: 1px solid var(--line);
  box-shadow: none;
  font-size: 13px;
}

.legacy-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.legacy-stat-card {
  min-height: 96px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 16px 38px rgba(24, 28, 35, 0.06);
}

.legacy-stat-card.revenue {
  color: #fff;
  border: 0;
  background: linear-gradient(135deg, #ff9844 0%, #ed7d3e 48%, #563426 100%);
  box-shadow: 0 18px 34px rgba(238, 126, 62, 0.24);
}

.legacy-stat-card span {
  display: block;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.legacy-stat-card.revenue span,
.legacy-stat-card.revenue small {
  color: rgba(255, 255, 255, 0.82);
}

.legacy-stat-card b {
  display: block;
  margin-top: 9px;
  color: var(--ink);
  font-size: 32px;
  line-height: 1;
  letter-spacing: 0;
}

.legacy-stat-card.revenue b {
  color: #fff;
  font-size: 33px;
}

.legacy-stat-card:nth-child(2) b {
  color: var(--good);
}

.legacy-stat-card small {
  display: block;
  margin-top: 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.legacy-brand-section {
  min-width: 0;
}

.legacy-section-title {
  margin-bottom: 10px;
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
}

.legacy-brand-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.legacy-brand-card {
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

.legacy-brand-card:hover {
  transform: translateY(-5px) scale(1.012);
  box-shadow: 0 22px 44px rgba(24, 28, 35, 0.22);
}

.legacy-brand-card.dim {
  opacity: 0.58;
}

.legacy-brand-card.selected {
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

.legacy-rank-card {
  padding: 18px 20px 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 16px 38px rgba(24, 28, 35, 0.06);
}

.legacy-rank-title {
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
  border: 1px solid rgba(238, 126, 62, 0.24);
  border-radius: 999px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 12px;
  font-weight: 900;
}

.legacy-table-wrap {
  overflow-x: auto;
}

.legacy-rank-table {
  width: 100%;
  min-width: 860px;
  border-collapse: collapse;
}

.legacy-rank-table th,
.legacy-rank-table td {
  padding: 13px 12px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  vertical-align: middle;
  font-size: 13px;
}

.legacy-rank-table th {
  background: transparent;
  color: var(--muted);
  font-weight: 900;
}

.legacy-rank-table tbody tr {
  cursor: pointer;
}

.legacy-rank-table tbody tr:hover {
  background: #fff8f2;
}

.legacy-rank-table .r {
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

.legacy-trend-card {
  min-height: 180px;
  padding: 18px 20px 22px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 16px 38px rgba(24, 28, 35, 0.06);
}

.legacy-trend-bars {
  display: flex;
  align-items: end;
  justify-content: space-around;
  gap: 24px;
  min-height: 150px;
  padding: 8px 16px 0;
}

.legacy-trend-item {
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
  background: linear-gradient(180deg, #ff9945 0%, #ee7e3e 100%);
  box-shadow: 0 10px 20px rgba(238, 126, 62, 0.18);
}

.trend-track i.negative {
  background: linear-gradient(180deg, #f26d5b 0%, #d94b3d 100%);
}

.trend-label {
  color: var(--muted);
}

@media (max-width: 980px) {
  .legacy-stat-grid,
  .legacy-brand-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .legacy-profit-page .page-head,
  .legacy-toolbar,
  .profit-filter-bar,
  .profit-filter-bar label,
  .profit-filter-bar select,
  .profit-filter-bar :deep(select),
  .legacy-toolbar label,
  .legacy-toolbar select,
  .legacy-toolbar button,
  .legacy-stat-grid,
  .legacy-brand-grid {
    grid-template-columns: 1fr;
    width: 100%;
  }

  .legacy-profit-page .page-head,
  .legacy-toolbar,
  .profit-filter-bar {
    align-items: stretch;
  }

  .profit-filter-bar {
    flex-direction: column;
  }

  .export-report-button {
    width: 100%;
  }

  .legacy-stat-card,
  .legacy-brand-card {
    min-height: 120px;
  }

  .legacy-trend-bars {
    justify-content: flex-start;
    overflow-x: auto;
  }
}

.legacy-profit-page :deep(.legacy-page-head) {
  align-items: center;
}

.legacy-profit-page :deep(.legacy-head-actions label) {
  display: grid;
  gap: 5px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.legacy-profit-page :deep(.legacy-head-actions select) {
  min-width: 118px;
  min-height: 37px;
  padding: 7px 10px;
  border-radius: 10px;
  font-size: 13px;
}

.legacy-profit-page :deep(.legacy-kpis .legacy-kpi:nth-child(2) .val) {
  color: var(--good);
}

.legacy-rank-card {
  padding: 18px 20px 20px;
}

.legacy-rank-table th {
  padding: 8px 10px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
}

.legacy-rank-table td {
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

.legacy-rank-table tbody tr:hover .chev {
  color: var(--primary);
  opacity: 1;
}

.legacy-trend-card {
  margin-bottom: 0;
}
</style>
