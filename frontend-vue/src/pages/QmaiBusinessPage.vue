<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ChevronLeft, ChevronRight, Download, RefreshCw, Search, Settings2 } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import UiButton from '../components/ui/UiButton.vue'
import { ApiError } from '../api/http'
import { downloadQmaiExport, getQmaiSummary, startQmaiSync, type QmaiSummary } from '../api/qmai'
import { PERMISSIONS } from '../permissions/permissions'
import { isBossRole, normalizeRoleCode } from '../permissions/roles'
import { useAuthStore } from '../stores/auth'
import { formatCny, formatDecimal, formatPercent } from '../utils/currency'

const auth = useAuthStore()
const summary = ref<QmaiSummary | null>(null)
const month = ref(currentMonth())
const tab = ref<'stores' | 'products'>('stores')
const selectedStore = ref('')
const keyword = ref('')
const storeSort = ref<'received' | 'profit' | 'margin-risk'>('received')
const productSort = ref<'quantity' | 'received' | 'refund'>('quantity')
const loading = ref(false)
const syncing = ref(false)
const exporting = ref(false)
const error = ref('')
let pollTimer: number | undefined

const canSync = computed(() => auth.hasPermission(PERMISSIONS.FINANCE_PROFIT_WRITE)
  && (isBossRole(auth.role) || normalizeRoleCode(auth.role) === 'FINANCE'))
const canExport = computed(() => auth.hasPermission(PERMISSIONS.FINANCE_EXPORT))
const batch = computed(() => summary.value?.latestBatch || null)
const batchRunning = computed(() => ['QUEUED', 'RUNNING'].includes(batch.value?.status || ''))
const progress = computed(() => {
  if (!batch.value?.totalTasks) return 0
  return Math.min(100, Math.round((batch.value.completedTasks / batch.value.totalTasks) * 100))
})
const storeRows = computed(() => {
  const rows = summary.value?.stores || []
  const visible = selectedStore.value ? rows.filter((row) => row.storeId === selectedStore.value) : rows
  return [...visible].sort((left, right) => {
    if (storeSort.value === 'profit') return Number(right.grossProfit) - Number(left.grossProfit)
    if (storeSort.value === 'margin-risk') return Number(left.grossMargin) - Number(right.grossMargin)
    return Number(right.received) - Number(left.received)
  })
})
const productRows = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  const visible = (summary.value?.products || []).filter((row) => {
    if (selectedStore.value && row.storeId !== selectedStore.value) return false
    if (!query) return true
    return `${row.itemName} ${row.categoryName || ''} ${row.storeName}`.toLowerCase().includes(query)
  })
  return [...visible].sort((left, right) => {
    if (productSort.value === 'received') return Number(right.received) - Number(left.received)
    if (productSort.value === 'refund') return Number(right.refundQuantity) - Number(left.refundQuantity)
    return Number(right.quantity) - Number(left.quantity)
  })
})
const monthIsCurrent = computed(() => month.value >= currentMonth())

onMounted(load)
onBeforeUnmount(stopPolling)

async function load(silent = false) {
  if (!silent) loading.value = true
  error.value = ''
  try {
    summary.value = await getQmaiSummary(month.value)
    syncing.value = batchRunning.value
    if (batchRunning.value) startPolling()
    else stopPolling()
  } catch (reason) {
    error.value = messageOf(reason)
    stopPolling()
  } finally {
    loading.value = false
  }
}

async function sync() {
  syncing.value = true
  error.value = ''
  try {
    await startQmaiSync(month.value)
    await load(true)
    startPolling()
  } catch (reason) {
    error.value = messageOf(reason)
    syncing.value = false
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = window.setInterval(() => void load(true), 2500)
}

function stopPolling() {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = undefined
}

function changeMonth(delta: number) {
  const [year, value] = month.value.split('-').map(Number)
  const date = new Date(year, value - 1 + delta, 1)
  month.value = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
  selectedStore.value = ''
  keyword.value = ''
  void load()
}

async function exportData() {
  exporting.value = true
  error.value = ''
  try {
    await downloadQmaiExport(month.value, tab.value, selectedStore.value)
  } catch (reason) {
    error.value = messageOf(reason)
  } finally {
    exporting.value = false
  }
}

function currentMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

function batchLabel(status?: string) {
  return ({ QUEUED: '等待同步', RUNNING: '正在同步', SUCCEEDED: '同步完成', PARTIAL: '部分完成', FAILED: '同步失败' } as Record<string, string>)[status || ''] || '尚未同步'
}

function messageOf(reason: unknown) {
  return reason instanceof ApiError ? reason.message : '企迈经营数据加载失败，请稍后重试'
}
</script>

<template>
  <section class="page-panel qmai-page">
    <PageHeader title="企迈经营数据" subtitle="营业额与商品销售均来自已落库的同步结果">
      <template #actions>
        <UiButton v-if="canExport" :loading="exporting" :disabled="!summary?.stores.length" @click="exportData">
          <template #icon><Download :size="16" /></template>导出当前视图
        </UiButton>
        <UiButton v-if="canSync" variant="primary" :loading="syncing || batchRunning" @click="sync">
          <template #icon><RefreshCw :size="16" /></template>{{ batchRunning ? '同步进行中' : '同步本月数据' }}
        </UiButton>
      </template>
    </PageHeader>

    <div class="control-deck">
      <div class="month-switcher" aria-label="月份选择">
        <button type="button" aria-label="上一个月" @click="changeMonth(-1)"><ChevronLeft :size="18" /></button>
        <strong>{{ month }}</strong>
        <button type="button" aria-label="下一个月" :disabled="monthIsCurrent" @click="changeMonth(1)"><ChevronRight :size="18" /></button>
      </div>
      <label class="store-filter"><span>门店</span><select v-model="selectedStore"><option value="">全部可见门店</option><option v-for="store in summary?.stores || []" :key="store.storeId" :value="store.storeId">{{ store.storeName }}</option></select></label>
      <span class="data-timestamp">最近落库：{{ summary?.lastSyncedAt || '暂无数据' }}</span>
    </div>

    <p v-if="error" class="feedback">{{ error }}</p>

    <section class="sync-strip" :class="`sync-strip--${(batch?.status || 'empty').toLowerCase()}`">
      <div class="sync-strip__lead"><Settings2 :size="18" /><div><strong>{{ batchLabel(batch?.status) }}</strong><span>{{ batch ? `${batch.month} · ${batch.requestedByName || '系统任务'}` : '选择月份后启动首次同步' }}</span></div></div>
      <div v-if="batch" class="sync-strip__progress"><div><span :style="{ width: `${progress}%` }" /></div><small>{{ batch.completedTasks }}/{{ batch.totalTasks }} 个门店日任务<span v-if="batch.failedTasks"> · {{ batch.failedTasks }} 个失败</span></small></div>
      <p v-if="batch?.errorSummary" class="sync-error">{{ batch.errorSummary }}</p>
    </section>

    <section class="metric-ledger" aria-label="经营指标">
      <article><span>实收营业额</span><strong>{{ formatCny(summary?.received) }}</strong><small>企迈 receivedAmount</small></article>
      <article><span>成本</span><strong>{{ formatCny(summary?.cost) }}</strong><small>企迈成本口径</small></article>
      <article><span>毛利</span><strong>{{ formatCny(summary?.grossProfit) }}</strong><small>实收减成本</small></article>
      <article><span>毛利率</span><strong class="positive">{{ formatPercent(summary?.grossMargin) }}</strong><small>按实收计算</small></article>
      <article><span>退款</span><strong>{{ formatCny(summary?.refund) }}</strong><small>同步月份累计</small></article>
    </section>

    <section class="data-panel">
      <header class="data-panel__header">
        <div class="view-tabs"><button :class="{ active: tab === 'stores' }" @click="tab = 'stores'">营业额</button><button :class="{ active: tab === 'products' }" @click="tab = 'products'">商品销售</button></div>
        <div class="data-tools">
          <label class="sort-box">
            <span>排序</span>
            <select v-if="tab === 'stores'" v-model="storeSort" aria-label="营业额排序">
              <option value="received">实收从高到低</option>
              <option value="profit">毛利从高到低</option>
              <option value="margin-risk">低毛利优先</option>
            </select>
            <select v-else v-model="productSort" aria-label="商品销售排序">
              <option value="quantity">销量从高到低</option>
              <option value="received">实收从高到低</option>
              <option value="refund">退款数量从高到低</option>
            </select>
          </label>
          <label v-if="tab === 'products'" class="search-box"><Search :size="16" /><input v-model="keyword" placeholder="搜索商品、分类或门店" /></label>
        </div>
      </header>

      <div v-if="loading" class="empty-state">正在读取已同步数据…</div>
      <div v-else-if="tab === 'stores'" class="table-wrap">
        <table>
          <thead><tr><th>门店</th><th class="num">有效天数</th><th class="num">实收</th><th class="num">成本</th><th class="num">毛利</th><th class="num">毛利率</th><th class="num">退款</th></tr></thead>
          <tbody>
            <tr v-for="row in storeRows" :key="row.storeId" :class="{ 'low-margin': row.lowMargin }">
              <td><strong>{{ row.storeName }}</strong><small>{{ row.sourceRows }} 条商品日数据</small></td><td class="num">{{ row.activeDays }}</td><td class="num">{{ formatCny(row.received) }}</td><td class="num">{{ formatCny(row.cost) }}</td><td class="num">{{ formatCny(row.grossProfit) }}</td><td class="num"><span class="margin-chip">{{ formatPercent(row.grossMargin) }}</span></td><td class="num">{{ formatCny(row.refund) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!storeRows.length" class="empty-state">该月份暂无营业额数据。完成配置后启动同步。</div>
      </div>

      <div v-else class="table-wrap">
        <table>
          <thead><tr><th>商品</th><th>门店</th><th>企迈分类</th><th class="num">销量</th><th class="num">实收</th><th class="num">成本</th><th class="num">退款数量</th></tr></thead>
          <tbody>
            <tr v-for="row in productRows" :key="`${row.storeId}-${row.productKey}`"><td><strong>{{ row.itemName }}</strong><small v-if="row.skuId">SKU {{ row.skuId }}</small></td><td>{{ row.storeName }}</td><td>{{ row.categoryName || '未分类' }}</td><td class="num">{{ formatDecimal(row.quantity, 1) }}</td><td class="num">{{ formatCny(row.received) }}</td><td class="num">{{ formatCny(row.cost) }}</td><td class="num">{{ formatDecimal(row.refundQuantity, 1) }}</td></tr>
          </tbody>
        </table>
        <div v-if="!productRows.length" class="empty-state">没有符合当前筛选条件的商品销售数据。</div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.qmai-page { display: grid; gap: 16px; min-width: 0; }
.control-deck { display: flex; align-items: end; gap: 14px; padding: 14px 16px; border: 1px solid var(--ds-line); border-radius: 10px; background: var(--ds-surface); }
.month-switcher { display: flex; align-items: center; border: 1px solid var(--ds-line-strong); border-radius: 8px; overflow: hidden; }
.month-switcher button { display: grid; width: 40px; height: 40px; place-items: center; border: 0; background: #fff; color: var(--ds-primary-hover); cursor: pointer; }
.month-switcher button:disabled { color: #b9c5c3; cursor: not-allowed; }
.month-switcher strong { min-width: 94px; text-align: center; font-variant-numeric: tabular-nums; }
.store-filter { display: grid; gap: 5px; color: var(--ds-muted); font-size: 12px; }
.store-filter select { min-width: 190px; height: 40px; padding: 0 10px; border: 1px solid var(--ds-line-strong); border-radius: 8px; background: #fff; color: var(--ds-ink); }
.data-timestamp { margin-left: auto; color: var(--ds-muted); font-size: 12px; }
.feedback { margin: 0; padding: 11px 13px; border-radius: 8px; background: #fff0f1; color: #a62f3d; }
.sync-strip { display: grid; grid-template-columns: minmax(220px, .8fr) minmax(260px, 1.2fr); align-items: center; gap: 18px; padding: 15px 17px; border-left: 4px solid #9db3b0; border-radius: 8px; background: #f3f7f6; }
.sync-strip--running, .sync-strip--queued { border-color: #2d7770; background: #ecf7f5; }
.sync-strip--succeeded { border-color: #2b8a58; background: #edf8f1; }
.sync-strip--partial, .sync-strip--failed { border-color: #ce8b28; background: #fff7e8; }
.sync-strip__lead { display: flex; align-items: center; gap: 11px; color: var(--ds-primary-hover); }
.sync-strip__lead div { display: grid; gap: 3px; }
.sync-strip__lead span, .sync-strip__progress small { color: var(--ds-muted); font-size: 12px; }
.sync-strip__progress { display: grid; gap: 5px; }
.sync-strip__progress > div { height: 6px; overflow: hidden; border-radius: 999px; background: rgba(39,107,101,.14); }
.sync-strip__progress > div span { display: block; height: 100%; border-radius: inherit; background: var(--ds-primary-hover); transition: width .25s ease; }
.sync-error { grid-column: 1 / -1; margin: 0; color: #965e16; font-size: 12px; }
.metric-ledger { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border: 1px solid var(--ds-line); border-radius: 10px; background: var(--ds-surface); }
.metric-ledger article { display: grid; gap: 4px; padding: 17px; border-right: 1px solid var(--ds-line); }
.metric-ledger article:last-child { border: 0; }
.metric-ledger span, .metric-ledger small { color: var(--ds-muted); font-size: 12px; }
.metric-ledger strong { color: var(--ds-ink); font-size: clamp(18px, 2vw, 24px); font-variant-numeric: tabular-nums; }
.metric-ledger .positive { color: #177348; }
.data-panel { min-width: 0; overflow: hidden; border: 1px solid var(--ds-line); border-radius: 10px; background: var(--ds-surface); }
.data-panel__header { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 12px 14px; border-bottom: 1px solid var(--ds-line); }
.data-tools { display: flex; align-items: center; justify-content: flex-end; gap: 10px; }
.sort-box { display: flex; align-items: center; gap: 7px; color: var(--ds-muted); font-size: 12px; }
.sort-box select { height: 38px; padding: 0 30px 0 10px; border: 1px solid var(--ds-line-strong); border-radius: 8px; background: #fff; color: var(--ds-ink); }
.view-tabs { display: inline-flex; padding: 3px; border-radius: 8px; background: var(--ds-surface-muted); }
.view-tabs button { min-width: 100px; height: 36px; border: 0; border-radius: 6px; background: transparent; color: var(--ds-muted); font-weight: 700; cursor: pointer; }
.view-tabs button.active { background: #fff; color: var(--ds-primary-hover); box-shadow: 0 1px 4px rgba(24,57,54,.1); }
.search-box { display: flex; width: min(320px, 100%); align-items: center; gap: 8px; padding: 0 10px; border: 1px solid var(--ds-line-strong); border-radius: 8px; color: var(--ds-muted); }
.search-box input { width: 100%; height: 38px; border: 0; outline: 0; background: transparent; color: var(--ds-ink); }
.table-wrap { max-width: 100%; overflow: auto; }
table { width: 100%; min-width: 820px; border-collapse: collapse; }
th, td { padding: 12px 14px; border-bottom: 1px solid var(--ds-line); text-align: left; white-space: nowrap; }
th { background: #fbfcfc; color: var(--ds-muted); font-size: 12px; font-weight: 700; }
td { color: var(--ds-ink); font-size: 13px; }
td strong, td small { display: block; }
td small { margin-top: 3px; color: var(--ds-muted); font-size: 11px; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
tr.low-margin { background: #fff7f7; }
tr.low-margin .margin-chip { color: #b93543; font-weight: 800; }
.empty-state { padding: 52px 18px; color: var(--ds-muted); text-align: center; }
@media (max-width: 1000px) { .metric-ledger { grid-template-columns: repeat(3, 1fr); } .metric-ledger article:nth-child(3) { border-right: 0; } .metric-ledger article:nth-child(n+4) { border-top: 1px solid var(--ds-line); } }
@media (max-width: 700px) { .control-deck, .data-panel__header, .data-tools { align-items: stretch; flex-direction: column; } .sort-box { justify-content: space-between; } .sort-box select { flex: 1; } .data-timestamp { margin-left: 0; } .store-filter select { width: 100%; } .sync-strip { grid-template-columns: 1fr; } .metric-ledger { grid-template-columns: 1fr 1fr; } .metric-ledger article:nth-child(odd) { border-right: 1px solid var(--ds-line); } .metric-ledger article:nth-child(even) { border-right: 0; } .metric-ledger article:nth-child(n+3) { border-top: 1px solid var(--ds-line); } .search-box { width: auto; } }
@media (prefers-reduced-motion: reduce) { .sync-strip__progress > div span { transition: none; } }
</style>
