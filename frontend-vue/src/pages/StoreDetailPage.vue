<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Download, Home, RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { getStores, type StoreInfo } from '../api/operations'
import { getProfitDashboard, type ProfitDashboard, type ProfitEntry } from '../api/profit'
import BrandBadge from '../components/common/BrandBadge.vue'
import BrandSelect from '../components/common/BrandSelect.vue'
import { useAuthStore } from '../stores/auth'
import { amount, money, percent, riskStatus } from '../stores/profit'
import { normalizeBrandName } from '../utils/brand'
import { filterStoresByBrand } from '../utils/storeFilter'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const stores = ref<StoreInfo[]>([])
const dashboard = ref<ProfitDashboard | null>(null)
const selectedBrandName = ref('')
const selectedStoreId = ref('')
const selectedMonth = ref('')
const loading = ref(false)
const error = ref('')

const brandOptions = computed(() => Array.from(new Set(stores.value.map((store) => normalizeBrandName(store.brandName) || '未分品牌'))).map((name) => ({ name })))
const visibleStores = computed(() => filterStoresByBrand(stores.value, selectedBrandName.value))
const selectedStore = computed(() => visibleStores.value.find((store) => store.id === selectedStoreId.value) || visibleStores.value[0] || null)
const canSwitchStore = computed(() => auth.role !== 'STORE_MANAGER' && stores.value.length > 1)
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

function applyDefaultStore() {
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
  try {
    const [storeRows, profitData] = await Promise.all([
      getStores(),
      getProfitDashboard({ month: selectedMonth.value || undefined }),
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

function goSalary() {
  const storeId = selectedStore.value?.id
  void router.push({ path: '/salary', query: storeId ? { storeId } : undefined })
}

function exportStoreCsv() {
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

onMounted(() => {
  void loadStoreDetail()
})
</script>

<template>
  <section class="page-panel legacy-page store-detail-page">
    <div class="legacy-page-head page-head">
      <div>
        <h2>门店详情</h2>
      </div>
      <div class="store-actions">
        <label v-if="canSwitchStore">
          品牌
          <BrandSelect v-model="selectedBrandName" :brands="brandOptions" :disabled="loading" />
        </label>
        <label>
          月份
          <select :value="selectedMonth" :disabled="loading" @change="selectMonth(($event.target as HTMLSelectElement).value)">
            <option v-for="month in monthOptions" :key="month" :value="month">{{ month }}</option>
          </select>
        </label>
        <label v-if="canSwitchStore">
          门店
          <select v-model="selectedStoreId" :disabled="loading" aria-label="门店">
            <option v-if="!visibleStores.length" value="">该品牌暂无门店数据</option>
            <option v-for="store in visibleStores" :key="store.id" :value="store.id">{{ normalizeBrandName(store.brandName) }} · {{ store.name }}</option>
          </select>
        </label>
        <div v-else class="fixed-store-chip">
          <span>当前门店</span>
          <b><BrandBadge :brand-name="selectedStore?.brandName || '未分品牌'" /> {{ selectedStore?.name || '暂无门店' }}</b>
        </div>
        <button class="ghost-button" type="button" :disabled="loading" @click="loadStoreDetail">
          <RefreshCw :size="16" />
          刷新
        </button>
        <button class="ghost-button" type="button" @click="goSalary">
          员工工资表
        </button>
        <button class="primary-button submit-inline" type="button" :disabled="!storeProfitRows.length" @click="exportStoreCsv">
          <Download :size="16" />
          导出该店 CSV
        </button>
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
        <div class="profile-grid">
          <div><span>门店名称</span><b>{{ selectedStore.name }}</b></div>
          <div><span>门店编号</span><b>{{ selectedStore.code || selectedStore.id }}</b></div>
          <div><span>所属品牌</span><b><BrandBadge :brand-name="selectedStore.brandName || '-'" /></b></div>
          <div><span>所属区域</span><b>{{ selectedStore.area || '待补充' }}</b></div>
          <div><span>负责人</span><b>{{ selectedStore.manager || '待补充' }}</b></div>
          <div><span>联系方式</span><b>待补充</b></div>
          <div><span>开业日期</span><b>{{ selectedStore.openDate || '待补充' }}</b></div>
          <div><span>门店状态</span><b><span class="status-badge" :class="statusTone(selectedStore.status)">{{ selectedStore.status || '未设置' }}</span></b></div>
        </div>
      </section>

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
  </section>
</template>

<style scoped>
.store-detail-page {
  display: grid;
  gap: 16px;
  max-width: 1180px;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.store-actions {
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.store-actions label {
  display: grid;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.store-actions select,
.store-actions :deep(.brand-select-wrap select) {
  min-width: 150px;
  min-height: 38px;
  padding: 7px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  font-weight: 900;
}

.fixed-store-chip {
  display: grid;
  gap: 4px;
  min-width: 210px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.fixed-store-chip span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.fixed-store-chip b {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 14px;
}

.store-metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.store-metric-card {
  min-height: 96px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 16px 38px rgba(24, 28, 35, 0.06);
}

.store-metric-card.revenue {
  color: #fff;
  border: 0;
  background: linear-gradient(135deg, #ff9844 0%, #ed7d3e 48%, #563426 100%);
  box-shadow: 0 18px 34px rgba(238, 126, 62, 0.24);
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
  font-size: 30px;
  line-height: 1;
  letter-spacing: 0;
}

.store-metric-card.revenue b {
  color: #fff;
  font-size: 32px;
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
  padding: 18px 20px 20px;
  border-radius: 16px;
  box-shadow: 0 16px 38px rgba(24, 28, 35, 0.06);
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

.profile-grid span {
  color: var(--muted);
  font-size: 13px;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  margin-top: 14px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 13px;
  background: #fff;
}

.profile-grid > div {
  min-width: 0;
  padding: 13px 14px;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.profile-grid > div:nth-child(4n) {
  border-right: 0;
}

.profile-grid > div:nth-last-child(-n + 4) {
  border-bottom: 0;
}

.profile-grid b {
  display: block;
  margin-top: 5px;
  overflow-wrap: anywhere;
  font-size: 14px;
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

@media (max-width: 980px) {
  .store-metric-grid,
  .profile-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .profile-grid > div,
  .profile-grid > div:nth-child(4n),
  .profile-grid > div:nth-last-child(-n + 4) {
    border-right: 1px solid var(--line);
    border-bottom: 1px solid var(--line);
  }

  .profile-grid > div:nth-child(2n) {
    border-right: 0;
  }

  .profile-grid > div:nth-last-child(-n + 2) {
    border-bottom: 0;
  }
}

@media (max-width: 720px) {
  .store-actions,
  .store-actions label,
  .store-actions select,
  .store-actions .ghost-button,
  .store-actions .primary-button,
  .fixed-store-chip,
  .store-metric-grid,
  .profile-grid {
    grid-template-columns: 1fr;
    width: 100%;
  }

  .profile-grid > div,
  .profile-grid > div:nth-child(2n),
  .profile-grid > div:nth-last-child(-n + 2) {
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }

  .profile-grid > div:last-child {
    border-bottom: 0;
  }
}
</style>
