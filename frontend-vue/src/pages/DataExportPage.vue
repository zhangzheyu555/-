<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Download, ReceiptText, RefreshCw, WalletCards } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { getStores, type StoreInfo } from '../api/operations'
import { downloadExpenseCsv, downloadProfitRankingCsv, downloadSalaryCsv } from '../api/reports'
import BrandSelect from '../components/common/BrandSelect.vue'
import PageHeader from '../components/common/PageHeader.vue'
import { useBusinessScope } from '../composables/useBusinessScope'
import { useProfitStore } from '../stores/profit'

const route = useRoute()
const profit = useProfitStore()
const scope = useBusinessScope()

const selectedMonth = ref(profit.month)
const selectedBrandId = ref(scope.scopedBrandId())
const scopedStores = ref<StoreInfo[]>([])
const storesLoaded = ref(false)
const downloading = ref('')
const loadingScope = ref(false)
const message = ref('')
const error = ref('')
const rangeNotice = ref('')
const initialized = ref(false)
let loadSerial = 0

const monthOptions = computed(() => profit.months)
const brandOptions = computed(() => profit.brands)
const exportMonth = computed(() => selectedMonth.value || profit.summary.month || profit.month || '')

function queryValue(value: unknown) {
  return Array.isArray(value) ? String(value[0] || '') : String(value || '')
}

// The route is the single source of truth for a non-manager's selected store.
// scopedStoreId deliberately replaces any URL value for a store manager.
const selectedStoreId = computed(() => scope.scopedStoreId(queryValue(route.query.storeId)))
const selectedStore = computed(() => {
  const storeId = selectedStoreId.value
  if (!storeId) return null
  return scopedStores.value.find((store) => store.id === storeId)
    || profit.allEntries.find((entry) => entry.storeId === storeId)
    || null
})
const selectedStoreBrandId = computed(() => {
  if (scope.isStoreManager.value) return scope.scopedBrandId()
  const brandId = selectedStore.value?.brandId
  return brandId == null ? '' : String(brandId)
})
const selectedStoreBrandName = computed(() => {
  if (scope.isStoreManager.value) return scope.brandName.value || '当前品牌'
  const name = selectedStore.value?.brandName
  if (name) return name
  return brandOptions.value.find((brand) => String(brand.id) === selectedStoreBrandId.value)?.name || '当前门店品牌'
})
const effectiveBrandId = computed(() => {
  if (scope.isStoreManager.value) return scope.scopedBrandId()
  return selectedStoreId.value ? selectedStoreBrandId.value : selectedBrandId.value
})
const selectedStoreName = computed(() => {
  if (scope.isStoreManager.value) return scope.boundStoreName.value || scope.boundStoreId.value
  if (!selectedStoreId.value) return '全部门店'
  const store = selectedStore.value
  if (!store) return selectedStoreId.value
  return 'name' in store ? store.name : store.storeName || store.storeCode || store.storeId
})
const selectedBrandName = computed(() => {
  if (selectedStoreId.value) return selectedStoreBrandName.value
  if (!effectiveBrandId.value) return '全部品牌'
  return brandOptions.value.find((brand) => String(brand.id) === effectiveBrandId.value)?.name || '当前品牌'
})
const exportRange = computed(() => `${selectedStoreName.value} · ${selectedBrandName.value} · ${exportMonth.value || '当前月份'}`)
const exportFileScope = computed(() => [selectedStoreName.value, selectedBrandName.value, exportMonth.value || '当前月份']
  .map((value) => value.replace(/[\\/:*?"<>|]/g, '-').trim())
  .filter(Boolean)
  .join('_'))
const hasProfitData = computed(() => profit.entries.length > 0)
const exportDisabled = computed(() => loadingScope.value || profit.loading || !exportMonth.value)
const profitTaskTitle = computed(() => selectedStoreId.value ? `${selectedStoreName.value}月度利润明细` : '全部门店月度利润汇总')

async function ensureStoresLoaded(force = false) {
  if ((storesLoaded.value && !force) || scope.isStoreManager.value) return
  try {
    scopedStores.value = await getStores()
    storesLoaded.value = true
  } catch {
    // Range text can still fall back to the dashboard entry or the selected store ID.
  }
}

function announceRangeUpdated() {
  rangeNotice.value = `导出范围已更新：${exportRange.value}`
}

function clearPageMessages() {
  message.value = ''
  error.value = ''
}

async function reloadExportScope(options: { announce?: boolean; refreshed?: boolean } = {}) {
  const serial = ++loadSerial
  loadingScope.value = true
  clearPageMessages()

  const storeId = scope.scopedStoreId(selectedStoreId.value)
  // A selected store determines its own brand. Never carry a previous page's brand into it.
  const brandIdBeforeLoad = scope.isStoreManager.value
    ? scope.scopedBrandId()
    : storeId ? '' : selectedBrandId.value
  const month = selectedMonth.value || profit.month || ''

  profit.setFilters({ month, brandId: brandIdBeforeLoad, storeId })
  try {
    await Promise.all([profit.load(), ensureStoresLoaded(Boolean(options.refreshed))])
    if (serial !== loadSerial) return
    if (profit.error) throw new Error(profit.error)

    selectedMonth.value = profit.month || profit.months[0] || month
    const resolvedBrandId = scope.isStoreManager.value
      ? scope.scopedBrandId()
      : storeId ? selectedStoreBrandId.value : selectedBrandId.value
    if (storeId) selectedBrandId.value = resolvedBrandId
    profit.setFilters({ month: selectedMonth.value, brandId: resolvedBrandId, storeId })

    if (options.announce) announceRangeUpdated()
    if (options.refreshed) message.value = '已刷新当前导出范围。'
  } catch (loadError) {
    if (serial !== loadSerial) return
    error.value = loadError instanceof Error && loadError.message
      ? loadError.message
      : '导出筛选项加载失败，请稍后重试。'
  } finally {
    if (serial === loadSerial) loadingScope.value = false
  }
}

async function handleMonthChange() {
  await reloadExportScope({ announce: true })
}

async function handleBrandChange(brandId: string) {
  selectedBrandId.value = brandId
  await reloadExportScope({ announce: true })
}

async function refreshExportScope() {
  await reloadExportScope({ announce: true, refreshed: true })
}

function exportParams() {
  return {
    month: exportMonth.value || undefined,
    brandId: effectiveBrandId.value || undefined,
    // Always pass the currently routed store through the scope resolver.
    storeId: scope.scopedStoreId(selectedStoreId.value) || undefined,
  }
}

function exportFilename(prefix: string) {
  return `${prefix}_${exportFileScope.value || '当前范围'}.csv`
}

async function runExport(key: 'profit' | 'expenses' | 'salary') {
  downloading.value = key
  clearPageMessages()
  try {
    const params = exportParams()
    if (key === 'profit') await downloadProfitRankingCsv(params, exportFilename('门店利润'))
    if (key === 'expenses') await downloadExpenseCsv(params, exportFilename('报销记录'))
    if (key === 'salary') await downloadSalaryCsv(params, exportFilename('员工工资'))
    message.value = key === 'profit'
      ? '门店利润 CSV 已开始下载。'
      : key === 'expenses'
        ? '报销记录 CSV 已开始下载。'
        : '员工工资 CSV 已开始下载。'
  } catch (downloadError) {
    error.value = downloadError instanceof Error && downloadError.message
      ? downloadError.message
      : 'CSV 导出失败，请稍后重试。'
  } finally {
    downloading.value = ''
  }
}

onMounted(() => {
  initialized.value = true
  selectedBrandId.value = scope.isStoreManager.value ? scope.scopedBrandId() : ''
  void reloadExportScope()
})

watch(
  () => queryValue(route.query.storeId),
  () => {
    if (!initialized.value) return
    // Switching the global store must not leave either another store's or another page's brand behind.
    selectedBrandId.value = scope.isStoreManager.value ? scope.scopedBrandId() : ''
    void reloadExportScope({ announce: true })
  },
)
</script>

<template>
  <section class="page-panel export-page">
    <PageHeader :title="scope.isStoreManager.value ? '本店数据导出' : undefined">
      <template #actions>
        <button class="ghost-button" type="button" :disabled="loadingScope || profit.loading" @click="refreshExportScope">
          <RefreshCw :size="16" />{{ loadingScope || profit.loading ? '正在刷新...' : '刷新' }}
        </button>
      </template>
    </PageHeader>

    <aside class="desktop-workflow-notice" role="note">
      <strong>请在电脑端完成</strong>
      <span>大批量数据导出涉及门店范围核对和敏感经营数据，请使用电脑端完成。</span>
    </aside>

    <p v-if="rangeNotice" class="export-range-notice" role="status" aria-live="polite">{{ rangeNotice }}</p>
    <div v-if="message" class="success-box" role="status" aria-live="polite">{{ message }}</div>
    <div v-if="error" class="error-box" role="alert">{{ error }}</div>

    <section class="content-card export-toolbar" aria-label="导出筛选">
      <div class="export-range-summary">
        <span>当前导出范围</span>
        <strong>{{ exportRange }}</strong>
        <p>三个 CSV 下载将使用同一门店、品牌和月份范围。</p>
      </div>

      <div class="export-toolbar-controls">
        <label>
          <span>月份</span>
          <select v-model="selectedMonth" :disabled="exportDisabled" aria-label="月份" @change="handleMonthChange">
            <option v-if="!monthOptions.length && exportMonth" :value="exportMonth">{{ exportMonth }}</option>
            <option v-for="month in monthOptions" :key="month" :value="month">{{ month }}</option>
          </select>
        </label>

        <label v-if="!selectedStoreId && !scope.isStoreManager.value">
          <span>品牌</span>
          <BrandSelect
            v-model="selectedBrandId"
            :brands="brandOptions"
            :disabled="exportDisabled"
            aria-label="品牌"
            @change="handleBrandChange"
          />
        </label>

        <div v-else class="readonly-brand" aria-label="当前品牌">
          <span>品牌</span>
          <strong>{{ selectedBrandName }}</strong>
        </div>
      </div>
    </section>

    <p v-if="loadingScope || profit.loading" class="export-state" role="status">正在加载当前导出范围…</p>
    <p v-else-if="!hasProfitData" class="export-state export-empty-state">当前范围暂无利润汇总；报销和工资仍可按该范围尝试下载。</p>

    <ul class="export-task-list" aria-label="导出任务">
      <li class="export-task">
        <div class="export-task-icon"><Download :size="20" /></div>
        <div class="export-task-content">
          <h3>{{ profitTaskTitle }}</h3>
          <p>{{ exportRange }}</p>
          <small>CSV · UTF-8 · 当前利润范围</small>
        </div>
        <button class="primary-button export-download-button" type="button" :disabled="exportDisabled || downloading === 'profit'" @click="runExport('profit')">
          {{ downloading === 'profit' ? '正在导出...' : '下载利润 CSV' }}
        </button>
      </li>

      <li class="export-task">
        <div class="export-task-icon"><ReceiptText :size="20" /></div>
        <div class="export-task-content">
          <h3>报销记录</h3>
          <p>{{ exportRange }}</p>
          <small>CSV · UTF-8 · 当前报销范围</small>
        </div>
        <button class="primary-button export-download-button" type="button" :disabled="exportDisabled || downloading === 'expenses'" @click="runExport('expenses')">
          {{ downloading === 'expenses' ? '正在导出...' : '下载报销 CSV' }}
        </button>
      </li>

      <li class="export-task">
        <div class="export-task-icon"><WalletCards :size="20" /></div>
        <div class="export-task-content">
          <h3>工资报表</h3>
          <p>{{ exportRange }}</p>
          <small>CSV · UTF-8 · 当前工资范围</small>
        </div>
        <button class="primary-button export-download-button" type="button" :disabled="exportDisabled || downloading === 'salary'" @click="runExport('salary')">
          {{ downloading === 'salary' ? '正在导出...' : '下载工资 CSV' }}
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.export-page {
  display: grid;
  gap: var(--space-4);
}

.desktop-workflow-notice {
  display: none;
}

.export-range-notice,
.export-state {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--muted);
  font-size: 13px;
}

.export-range-notice {
  border-color: rgba(30, 158, 106, 0.28);
  background: #f5fcf8;
  color: #216348;
}

.export-empty-state {
  border-color: #efddb9;
  background: #fffaf0;
  color: #87500f;
}

.export-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
}

.export-range-summary {
  display: grid;
  min-width: 0;
  gap: var(--space-1);
}

.export-range-summary > span,
.export-toolbar-controls > label > span,
.readonly-brand > span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.export-range-summary strong {
  min-width: 0;
  color: var(--ink);
  font-size: 15px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.export-range-summary p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}

.export-toolbar-controls {
  display: flex;
  min-width: 0;
  align-items: end;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.export-toolbar-controls > label,
.readonly-brand {
  display: grid;
  min-width: 148px;
  gap: var(--space-1);
}

.export-toolbar-controls select,
.export-toolbar-controls :deep(.brand-select-wrap select) {
  min-width: 148px;
  min-height: 38px;
  padding: var(--space-2) 30px var(--space-2) var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.readonly-brand strong {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-size: 13px;
}

.export-task-list {
  display: grid;
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--line);
  border-radius: 14px;
  overflow: hidden;
  background: var(--surface);
}

.export-task {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
}

.export-task + .export-task {
  border-top: 1px solid var(--line);
}

.export-task-icon {
  display: inline-flex;
  width: 38px;
  height: 38px;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.export-task-content {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.export-task-content h3,
.export-task-content p,
.export-task-content small {
  margin: 0;
}

.export-task-content h3 {
  color: var(--ink);
  font-size: 15px;
}

.export-task-content p {
  color: var(--muted);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.export-task-content small {
  color: var(--muted);
  font-size: 12px;
}

.export-download-button {
  min-width: 132px;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .desktop-workflow-notice {
    display: grid;
    gap: var(--space-1);
    padding: var(--space-3);
    border: 1px solid #efd19f;
    border-radius: var(--radius-md);
    background: #fff8ed;
    color: #73450f;
    font-size: 13px;
    line-height: 1.5;
  }

  .desktop-workflow-notice strong {
    color: #73450f;
    font-size: 14px;
  }
}

@media (max-width: 820px) {
  .export-toolbar {
    grid-template-columns: 1fr;
    align-items: stretch;
  }

  .export-toolbar-controls {
    align-items: stretch;
  }
}

@media (max-width: 560px) {
  .export-toolbar {
    padding: var(--space-3);
  }

  .export-toolbar-controls,
  .export-toolbar-controls > label,
  .readonly-brand,
  .export-toolbar-controls :deep(.brand-select-wrap),
  .export-toolbar-controls select,
  .export-toolbar-controls :deep(.brand-select-wrap select) {
    width: 100%;
  }

  .export-task {
    grid-template-columns: auto minmax(0, 1fr);
    align-items: start;
    padding: var(--space-3);
  }

  .export-download-button {
    grid-column: 1 / -1;
    width: 100%;
  }
}
</style>
