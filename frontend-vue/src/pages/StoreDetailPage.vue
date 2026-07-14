<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Download, Home, RefreshCw, X } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { getStores, type StoreInfo } from '../api/operations'
import { getProfitDashboard, type ProfitDashboard, type ProfitEntry } from '../api/profit'
import BrandBadge from '../components/common/BrandBadge.vue'
import BrandSelect from '../components/common/BrandSelect.vue'
import PageHeader from '../components/common/PageHeader.vue'
import StoreLatestInspection from '../components/inspection/StoreLatestInspection.vue'
import StoreSalaryWorkbench from '../components/salary/StoreSalaryWorkbench.vue'
import UiButton from '../components/ui/UiButton.vue'
import { useBusinessScope } from '../composables/useBusinessScope'
import { PERMISSIONS } from '../permissions/permissions'
import { useAuthStore } from '../stores/auth'
import { amount, money, percent, riskStatus } from '../stores/profit'
import { normalizeBrandName } from '../utils/brand'
import { filterStoresByBrand } from '../utils/storeFilter'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const businessScope = useBusinessScope()

const stores = ref<StoreInfo[]>([])
const dashboard = ref<ProfitDashboard | null>(null)
const selectedBrandName = ref('')
const selectedStoreId = ref('')
const selectedMonth = ref('')
const loading = ref(false)
const error = ref('')
const salaryOpen = ref(false)

const brandOptions = computed(() => Array.from(new Set(stores.value.map((store) => normalizeBrandName(store.brandName) || '未分品牌'))).map((name) => ({ name })))
const visibleStores = computed(() => filterStoresByBrand(stores.value, selectedBrandName.value))
const selectedStore = computed(() => {
  if (businessScope.isStoreManager.value) {
    return stores.value.find((store) => store.id === businessScope.boundStoreId.value) || null
  }
  return visibleStores.value.find((store) => store.id === selectedStoreId.value) || visibleStores.value[0] || null
})
const canSwitchStore = computed(() => (
  !businessScope.isStoreManager.value
  && auth.dataScope('STORE')?.mode !== 'OWN_STORE'
  && stores.value.length > 1
))
const canReadSalary = computed(() => auth.hasPermission(PERMISSIONS.SALARY_READ))
const canExportFinance = computed(() => auth.hasPermission(PERMISSIONS.FINANCE_EXPORT))
const detailTitle = computed(() => businessScope.isStoreManager.value
  ? `${businessScope.boundStoreName.value || selectedStore.value?.name || '本店'}详情`
  : '门店详情')
const detailSubtitle = computed(() => {
  if (!businessScope.isStoreManager.value) return ''
  const brandName = businessScope.brandName.value || selectedStore.value?.brandName || '品牌待配置'
  const storeCode = selectedStore.value?.code || businessScope.boundStoreId.value || '待配置'
  return `${brandName} · 门店编号 ${storeCode} · ${statusText(selectedStore.value?.status)}`
})
const monthOptions = computed(() => dashboard.value?.months || [])
const profitRows = computed(() => dashboard.value?.entries || [])
const storeProfitRows = computed(() => {
  const storeId = selectedStore.value?.id
  if (!storeId) return []
  return profitRows.value
    .filter((entry) => entry.storeId === storeId)
    .sort((a, b) => String(b.month || '').localeCompare(String(a.month || '')))
})
const currentProfit = computed(() => {
  const storeId = selectedStore.value?.id
  if (!storeId) return null
  return profitRows.value.find((entry) => entry.storeId === storeId && (!selectedMonth.value || entry.month === selectedMonth.value)) || null
})
const cumulativeIncome = computed(() => storeProfitRows.value.reduce((total, entry) => total + amount(entry.income ?? entry.sales), 0))
const cumulativeNet = computed(() => storeProfitRows.value.reduce((total, entry) => total + amount(entry.net), 0))
const averageMargin = computed(() => (cumulativeIncome.value === 0 ? 0 : cumulativeNet.value / cumulativeIncome.value))
const latestProfit = computed(() => storeProfitRows.value[0] || null)

function gross(entry: ProfitEntry | null) {
  if (!entry) return 0
  if (entry.gross !== undefined) return amount(entry.gross)
  return amount(entry.income ?? entry.sales) - amount(entry.costSum)
}

function statusTone(value?: string) {
  if (!value) return 'info'
  if (['营业中', '正常', 'ACTIVE'].includes(value)) return 'ok'
  if (['停业', '停用', 'DISABLED'].includes(value)) return 'bad'
  return 'warn'
}

function statusText(value?: string) {
  if (!value || ['营业中', '正常', 'ACTIVE'].includes(value)) return '营业中'
  if (['停业', '停用', 'DISABLED'].includes(value)) return '停业'
  return value
}

function applyDefaultStore() {
  if (businessScope.isStoreManager.value) {
    selectedStoreId.value = businessScope.boundStoreId.value
    selectedBrandName.value = businessScope.brandName.value
    return
  }
  const requestedStoreId = typeof route.query.storeId === 'string' ? route.query.storeId : ''
  if (requestedStoreId && visibleStores.value.some((store) => store.id === requestedStoreId)) {
    selectedStoreId.value = requestedStoreId
    return
  }
  if (!selectedStoreId.value || !visibleStores.value.some((store) => store.id === selectedStoreId.value)) {
    selectedStoreId.value = visibleStores.value[0]?.id || ''
  }
}

async function loadStoreDetail() {
  loading.value = true
  error.value = ''
  if (businessScope.configurationError.value) {
    error.value = businessScope.configurationError.value
    loading.value = false
    return
  }
  try {
    const storeId = businessScope.scopedStoreId('')
    const brandId = businessScope.scopedBrandId('')
    const [storeRows, profitData] = await Promise.all([
      getStores(),
      getProfitDashboard({
        month: selectedMonth.value || undefined,
        storeId: storeId || undefined,
        brandId: brandId || undefined,
      }),
    ])
    stores.value = storeRows
    dashboard.value = profitData
    selectedMonth.value = profitData.summary?.month || selectedMonth.value || profitData.months?.[0] || ''
    applyDefaultStore()
  } catch (loadError) {
    error.value = loadError instanceof Error ? loadError.message : '门店详情加载失败'
  } finally {
    loading.value = false
  }
}

function selectMonth(month: string) {
  selectedMonth.value = month
  void loadStoreDetail()
}

function goProfit(entry: ProfitEntry | null) {
  const storeId = entry?.storeId || selectedStore.value?.id
  const month = entry?.month || selectedMonth.value
  const query = new URLSearchParams()
  query.set('mode', 'single')
  if (storeId) query.set('storeId', storeId)
  if (month) query.set('month', month)
  void router.push(`/profit-table?${query.toString()}`)
}

function openSalary() { salaryOpen.value = true }

function exportStoreCsv() {
  if (!canExportFinance.value) {
    error.value = '当前账号没有导出经营数据的权限。'
    return
  }
  const storeName = selectedStore.value?.name || '门店'
  const headers = ['月份', '门店', '实收收入', '成本合计', '毛利润', '费用合计', '净利润', '净利率']
  const rows = storeProfitRows.value.map((entry) => [
    entry.month,
    storeName,
    amount(entry.income ?? entry.sales).toFixed(2),
    amount(entry.costSum).toFixed(2),
    gross(entry).toFixed(2),
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
  link.download = `门店详情-${storeName}-${selectedMonth.value || '全部月份'}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

watch(
  () => route.query.storeId,
  () => {
    applyDefaultStore()
  },
)

watch(selectedBrandName, () => {
  applyDefaultStore()
})

function handleEscape(event: KeyboardEvent) {
  if (event.key === 'Escape' && salaryOpen.value) salaryOpen.value = false
}

onMounted(() => {
  document.addEventListener('keydown', handleEscape)
  void loadStoreDetail()
})
onBeforeUnmount(() => document.removeEventListener('keydown', handleEscape))
</script>

<template>
  <section class="page-panel store-detail-page">
    <PageHeader class="store-detail-header" :title="detailTitle" :subtitle="detailSubtitle">
      <template #actions>
        <div class="store-header-actions">
          <label v-if="businessScope.isStoreManager.value" class="manager-month-field">
            <span>月份</span>
            <select :value="selectedMonth" :disabled="loading" @change="selectMonth(($event.target as HTMLSelectElement).value)">
              <option v-for="month in monthOptions" :key="month" :value="month">{{ month }}</option>
            </select>
          </label>
          <button class="ghost-button" type="button" :disabled="loading" @click="loadStoreDetail">
            <RefreshCw :size="16" />刷新
          </button>
          <button v-if="canReadSalary" class="ghost-button" type="button" :disabled="!selectedStore" @click="openSalary">员工工资表</button>
          <button v-if="canExportFinance" class="primary-button submit-inline" type="button" :disabled="!storeProfitRows.length" @click="exportStoreCsv">
            <Download :size="16" />导出该店 CSV
          </button>
        </div>
      </template>
    </PageHeader>

    <div
      v-if="!businessScope.isStoreManager.value"
      class="store-actions"
      :class="{ 'store-actions--fixed': !canSwitchStore }"
      aria-label="门店详情筛选条件"
    >
        <label v-if="canSwitchStore" class="store-filter-field store-filter-field--brand">
          品牌
          <BrandSelect v-model="selectedBrandName" :brands="brandOptions" :disabled="loading" />
        </label>
        <label class="store-filter-field store-filter-field--month">
          月份
          <select :value="selectedMonth" :disabled="loading" @change="selectMonth(($event.target as HTMLSelectElement).value)">
            <option v-for="month in monthOptions" :key="month" :value="month">{{ month }}</option>
          </select>
        </label>
        <label v-if="canSwitchStore" class="store-filter-field store-filter-field--store">
          门店
          <select v-model="selectedStoreId" :disabled="loading" aria-label="门店">
            <option v-if="!visibleStores.length" value="">该品牌暂无门店数据</option>
            <option v-for="store in visibleStores" :key="store.id" :value="store.id">{{ normalizeBrandName(store.brandName) }} · {{ store.name }}</option>
          </select>
        </label>
        <div v-else class="fixed-store-chip store-filter-field--store">
          <span>当前门店</span>
          <b><BrandBadge :brand-name="selectedStore?.brandName || '未分品牌'" /> {{ selectedStore?.name || '暂无门店' }}</b>
        </div>
    </div>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-if="loading && !stores.length" class="empty-state">正在读取门店详情...</div>

    <template v-else-if="selectedStore">
      <div class="store-metric-grid">
        <article class="store-metric-card revenue">
          <span>累计营收</span>
          <b>{{ money(cumulativeIncome) }}</b>
          <small>{{ storeProfitRows.length }} 个月经营数据</small>
        </article>
        <article class="store-metric-card">
          <span>累计净利</span>
          <b :class="{ negative: cumulativeNet < 0 }">{{ money(cumulativeNet) }}</b>
          <small>{{ currentProfit ? riskStatus(currentProfit) : '暂无利润记录' }}</small>
        </article>
        <article class="store-metric-card">
          <span>平均净利率</span>
          <b>{{ percent(averageMargin) }}</b>
          <small>按该店已录入月份计算</small>
        </article>
        <article class="store-metric-card">
          <span>最近月净利</span>
          <b :class="{ negative: amount(latestProfit?.net) < 0 }">{{ money(latestProfit?.net) }}</b>
          <small>{{ latestProfit?.month || '暂无月份' }}</small>
        </article>
      </div>

      <section class="content-card compact-profile-card">
        <div class="detail-title">
          <Home :size="19" />
          <div>
            <h3>门店基础资料</h3>
          </div>
        </div>
        <dl class="profile-grid">
          <div><dt>门店名称</dt><dd>{{ selectedStore.name }}</dd></div>
          <div><dt>门店编号</dt><dd>{{ selectedStore.code || selectedStore.id }}</dd></div>
          <div><dt>所属品牌</dt><dd><BrandBadge :brand-name="selectedStore.brandName || '-'" /></dd></div>
          <div><dt>所属区域</dt><dd :class="{ 'is-placeholder': !selectedStore.area }">{{ selectedStore.area || '待补充' }}</dd></div>
          <div><dt>负责人</dt><dd :class="{ 'is-placeholder': !selectedStore.manager }">{{ selectedStore.manager || '待补充' }}</dd></div>
          <div><dt>联系方式</dt><dd class="is-placeholder">待补充</dd></div>
          <div><dt>开业日期</dt><dd :class="{ 'is-placeholder': !selectedStore.openDate }">{{ selectedStore.openDate || '待补充' }}</dd></div>
          <div><dt>门店状态</dt><dd><span class="status-badge" :class="statusTone(selectedStore.status)">{{ statusText(selectedStore.status) }}</span></dd></div>
        </dl>
      </section>

      <StoreLatestInspection :store-id="selectedStore.id" />

      <section class="content-card monthly-card">
        <div class="table-heading">
          <div>
            <h3>逐月经营明细</h3>
          </div>
        </div>
        <div v-if="!storeProfitRows.length" class="empty-state compact">当前门店暂无逐月经营数据。</div>
        <div v-else class="table-wrap">
          <table class="monthly-table">
            <thead>
              <tr>
                <th>月份</th>
                <th class="r">实收收入</th>
                <th class="r">成本合计</th>
                <th class="r">毛利润</th>
                <th class="r">费用合计</th>
                <th class="r">净利润</th>
                <th class="r">净利率</th>
                <th class="r">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in storeProfitRows" :key="`${entry.storeId}-${entry.month}`">
                <td>{{ entry.month }}</td>
                <td class="r">{{ money(entry.income ?? entry.sales) }}</td>
                <td class="r">{{ money(entry.costSum) }}</td>
                <td class="r" :class="{ negative: gross(entry) < 0, positive: gross(entry) >= 0 }">{{ money(gross(entry)) }}</td>
                <td class="r">{{ money(entry.expenseSum) }}</td>
                <td class="r" :class="{ negative: amount(entry.net) < 0, positive: amount(entry.net) >= 0 }">{{ money(entry.net) }}</td>
                <td class="r">{{ percent(entry.margin) }}</td>
                <td class="r"><button class="mini-button" type="button" @click="goProfit(entry)">查看利润表</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <div v-else class="empty-state">
      <b>暂无可查看门店</b>
      <span>请确认当前账号已绑定门店，或由老板在用户权限中配置门店范围。</span>
    </div>

    <Teleport to="body">
      <div v-if="salaryOpen && canReadSalary && selectedStore" class="salary-modal-backdrop" role="presentation" @click.self="salaryOpen = false">
        <section class="salary-modal" role="dialog" aria-modal="true" aria-label="员工工资表">
          <header><div><h2>{{ selectedStore.name }}员工工资表</h2><span>{{ selectedMonth }}</span></div><UiButton variant="ghost" icon-only title="关闭" aria-label="关闭员工工资表" @click="salaryOpen = false"><template #icon><X :size="20" /></template></UiButton></header>
          <div class="salary-modal-body"><StoreSalaryWorkbench :key="`${selectedStore.id}-${selectedMonth}`" embedded :initial-store-id="selectedStore.id" :initial-month="selectedMonth" /></div>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.store-detail-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  width: 100%;
  max-width: 1440px;
  min-width: 0;
  margin-inline: auto;
  gap: 16px;
  container: store-detail / inline-size;
  box-sizing: border-box;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.store-detail-page > * {
  max-width: 100%;
  min-width: 0;
}

.store-detail-header {
  min-height: 64px;
}

.store-actions {
  display: grid;
  grid-template-columns: minmax(160px, 180px) minmax(150px, 170px) minmax(240px, 1fr);
  width: 100%;
  min-width: 0;
  align-items: flex-end;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
}

.store-actions--fixed {
  grid-template-columns: minmax(150px, 170px) minmax(240px, 1fr);
}

.store-header-actions {
  display: flex;
  max-width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.store-header-actions > * {
  width: auto;
  flex: none;
}

.manager-month-field {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.manager-month-field select {
  width: 132px;
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--ink);
}

.store-filter-field {
  display: grid;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.store-actions select,
.store-actions :deep(.brand-select-wrap select) {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  height: 42px;
  min-height: 42px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  font-weight: 900;
  text-overflow: ellipsis;
}

.store-actions :deep(.brand-select-wrap) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

.fixed-store-chip {
  display: grid;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  gap: 4px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
}

.fixed-store-chip span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.fixed-store-chip b {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  overflow-wrap: anywhere;
  font-size: 14px;
}

.store-metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr));
  min-width: 0;
  gap: 12px;
}

.store-metric-card {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  min-height: 110px;
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(24, 28, 35, 0.03);
}

.store-metric-card.revenue {
  color: #fff;
  border: 0;
  background: var(--ds-primary-hover);
  box-shadow: none;
}

.store-metric-card span {
  display: block;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.store-metric-card.revenue span,
.store-metric-card.revenue small {
  color: rgba(255, 255, 255, 0.82);
}

.store-metric-card b {
  display: block;
  margin-top: 9px;
  color: var(--ink);
  font-size: clamp(26px, 2.2vw, 30px);
  line-height: 1.12;
  letter-spacing: 0;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}

.store-metric-card.revenue b {
  color: #fff;
  font-size: clamp(27px, 2.3vw, 30px);
}

.store-metric-card small {
  display: block;
  margin-top: 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.compact-profile-card,
.monthly-card {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  padding: 16px 18px 18px;
  border-radius: 8px;
  box-shadow: none;
}

.detail-title {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}

.detail-title h3 {
  margin: 0 0 3px;
  font-size: 18px;
}

.profile-grid dt {
  color: var(--muted);
  font-size: 13px;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  min-width: 0;
  margin: 14px 0 0;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--line);
}

.profile-grid > div {
  min-width: 0;
  padding: 13px 14px;
  background: #fff;
}

.profile-grid dd {
  display: block;
  margin-top: 5px;
  margin-inline: 0;
  overflow-wrap: anywhere;
  font-size: 14px;
  font-weight: 700;
}

.profile-grid dd.is-placeholder {
  color: var(--muted);
  font-weight: 500;
}

.monthly-card .table-wrap {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-x: contain;
}

.monthly-table {
  width: 100%;
  min-width: 880px;
  border-collapse: collapse;
  font-size: 13.5px;
}

.monthly-table th,
.monthly-table td {
  padding: 11px 10px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  vertical-align: middle;
}

.monthly-table th {
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.monthly-table .r {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.positive {
  color: var(--good);
}

.negative {
  color: var(--bad);
}

.salary-modal-backdrop { position: fixed; inset: 0; z-index: var(--ds-z-modal, 1400); display: grid; place-items: center; padding: 24px; background: rgba(15, 23, 42, .48); }
.salary-modal { display: flex; flex-direction: column; width: min(1500px, calc(100vw - 48px)); height: min(900px, calc(100vh - 48px)); overflow: hidden; border-radius: 8px; background: #f4f5f7; box-shadow: 0 24px 70px rgba(15, 23, 42, .28); }
.salary-modal > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 15px 18px; border-bottom: 1px solid var(--line); background: #fff; }
.salary-modal h2 { margin: 0; font-size: 19px; }.salary-modal header span { color: var(--muted); font-size: 12px; }
.salary-modal-body { flex: 1; min-height: 0; overflow: auto; padding: 18px; }
.icon-close { display: inline-grid; place-items: center; width: 36px; height: 36px; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: var(--muted); cursor: pointer; }

@container store-detail (max-width: 900px) {
  .profile-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@container store-detail (max-width: 700px) {
  .store-actions {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .store-filter-field--store {
    grid-column: 1 / -1;
  }
}

@container store-detail (max-width: 480px) {
  .store-actions,
  .profile-grid {
    grid-template-columns: 1fr;
  }

  .store-filter-field--store {
    grid-column: auto;
  }

  .store-actions select,
  .store-actions :deep(.brand-select-wrap select) {
    height: 44px;
    min-height: 44px;
  }
}
</style>
