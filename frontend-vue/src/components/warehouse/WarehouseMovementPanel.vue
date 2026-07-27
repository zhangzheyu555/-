<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { Download } from 'lucide-vue-next'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import SearchableMultiSelect from '../common/SearchableMultiSelect.vue'
import {
  getMovementFilterOptions,
  queryMovements,
  downloadMovementExport,
  type MovementFilterOptions,
  type MovementQueryResponse,
  type WarehouseStockMovement,
} from '../../api/warehouse'

const props = defineProps<{
  warehouseId?: string | number
  warehouseName?: string
  downloadingId: string
}>()

const emit = defineEmits<{
  downloadMovement: [movementId: number, itemName: string, movementType: string]
  downloadDelivery: [requisitionId: string]
}>()

// ─── Filter state ───────────────────────────────────────────────────────
function localDateValue(date: Date) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

const today = new Date()
const thirtyDaysAgo = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 30)
const startDate = ref(localDateValue(thirtyDaysAgo))
const endDate = ref(localDateValue(today))
const selectedStoreIds = ref<string[]>([])
const selectedItemIds = ref<number[]>([])
const selectedDirections = ref<string[]>([])
const page = ref(1)
const pageSize = ref(50)

// ─── Data state ─────────────────────────────────────────────────────────
const filterOptions = ref<MovementFilterOptions | null>(null)
const queryResult = ref<MovementQueryResponse | null>(null)
const loading = ref(false)
const exporting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const lastQueryTime = ref(0)

// ─── Computed ───────────────────────────────────────────────────────────
const movements = computed(() => queryResult.value?.rows || [])
const total = computed(() => queryResult.value?.total || 0)
const totalPages = computed(() => Math.ceil(total.value / pageSize.value))

const storeOptions = computed(() => (filterOptions.value?.stores || []).map(s => ({
  value: s.id,
  label: s.name || s.code || s.id,
  description: [s.code, s.area, s.status].filter(Boolean).join(' · '),
  searchText: [s.name, s.code, s.id, s.area, s.status].filter(Boolean).join(' '),
})))

const itemOptions = computed(() => (filterOptions.value?.items || []).map(i => ({
  value: i.id,
  label: i.name || i.code || String(i.id),
  description: [i.code, i.category, i.unit, i.active ? '' : '已停用'].filter(Boolean).join(' · '),
  searchText: [i.name, i.code, i.category, i.unit].filter(Boolean).join(' '),
})))

// ─── Date shortcuts ─────────────────────────────────────────────────────
function setDateRange(preset: string) {
  const now = new Date()
  endDate.value = localDateValue(now)
  if (preset === 'today') {
    startDate.value = localDateValue(now)
  } else if (preset === 'week') {
    const weekStart = new Date(now)
    weekStart.setDate(now.getDate() - now.getDay() + (now.getDay() === 0 ? -6 : 1))
    startDate.value = localDateValue(weekStart)
  } else if (preset === 'month') {
    startDate.value = localDateValue(new Date(now.getFullYear(), now.getMonth(), 1))
  } else {
    startDate.value = localDateValue(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 30))
  }
}

// ─── Query logic ────────────────────────────────────────────────────────
let debounceTimer: ReturnType<typeof setTimeout> | null = null

async function loadFilterOptions() {
  if (!props.warehouseId) return
  try {
    filterOptions.value = await getMovementFilterOptions(props.warehouseId)
  } catch {
    filterOptions.value = { stores: [], items: [] }
  }
}

async function executeQuery() {
  if (!props.warehouseId || !startDate.value || !endDate.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    queryResult.value = await queryMovements({
      warehouseId: props.warehouseId,
      startDate: startDate.value,
      endDate: endDate.value,
      storeIds: selectedStoreIds.value.length ? selectedStoreIds.value : undefined,
      itemIds: selectedItemIds.value.length ? selectedItemIds.value : undefined,
      directions: selectedDirections.value.length ? selectedDirections.value : undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    lastQueryTime.value = Date.now()
  } catch (err) {
    errorMessage.value = err instanceof Error ? err.message : '查询失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function debouncedQuery() {
  page.value = 1
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(executeQuery, 400)
}

function goToPage(p: number) {
  if (p < 1 || p > totalPages.value) return
  page.value = p
  executeQuery()
}

// ─── Export ─────────────────────────────────────────────────────────────
async function exportExcel() {
  if (!props.warehouseId || !startDate.value || !endDate.value) return
  exporting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const filename = await downloadMovementExport({
      warehouseId: props.warehouseId,
      startDate: startDate.value,
      endDate: endDate.value,
      storeIds: selectedStoreIds.value.length ? selectedStoreIds.value : undefined,
      itemIds: selectedItemIds.value.length ? selectedItemIds.value : undefined,
      directions: selectedDirections.value.length ? selectedDirections.value : undefined,
    }, `出入库流水_${startDate.value}_${endDate.value}.xlsx`)
    successMessage.value = `流水已导出：${filename}`
  } catch (err) {
    errorMessage.value = err instanceof Error ? err.message : '导出失败，请稍后重试'
  } finally {
    exporting.value = false
  }
}

// ─── Focus reload ───────────────────────────────────────────────────────
let focusReloadLocked = false

function onVisibilityChange() {
  if (document.hidden || focusReloadLocked) return
  if (Date.now() - lastQueryTime.value > 60_000) {
    focusReloadLocked = true
    executeQuery().finally(() => { focusReloadLocked = false })
  }
}

// ─── Public refresh (called after business actions) ─────────────────────
function refresh() {
  executeQuery()
}
defineExpose({ refresh })

// ─── Watchers ───────────────────────────────────────────────────────────
watch(() => props.warehouseId, async (newId, oldId) => {
  if (newId === oldId) return
  selectedStoreIds.value = []
  selectedItemIds.value = []
  await loadFilterOptions()
  debouncedQuery()
}, { immediate: false })

watch([startDate, endDate, selectedStoreIds, selectedItemIds, selectedDirections], debouncedQuery)

// ─── Lifecycle ──────────────────────────────────────────────────────────
onMounted(async () => {
  await loadFilterOptions()
  executeQuery()
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (debounceTimer) clearTimeout(debounceTimer)
})

// ─── Helpers ────────────────────────────────────────────────────────────
function qty(value: number | undefined) {
  return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })
}

function sourceLabel(row: WarehouseStockMovement) {
  if (row.sourceType === 'REQUISITION') return '门店叫货发货'
  if (row.sourceType === 'RETURN_RECEIVE') return '配送退货入库'
  if (row.sourceType?.includes('TRANSFER')) return '仓间调拨'
  if (row.sourceType?.includes('RECEIVE') || row.sourceType === 'PURCHASE_ORDER') return '采购入库'
  return row.sourceType || '库存流水'
}

function documentLabel(row: WarehouseStockMovement) {
  if (row.sourceType === 'REQUISITION' && row.movementType === 'OUT') return '下载出库单'
  if (row.movementType === 'IN') return '下载入库单'
  return '下载流水单'
}
</script>

<template>
  <div class="content-card movement-report">
    <!-- Filter area -->
    <div class="table-heading">
      <div>
        <h3>出入库查询</h3>
        <span>按时间、门店、物料查询实际出入库流水并导出</span>
      </div>
    </div>

    <div class="filter-area">
      <div class="filter-row date-row">
        <label class="filter-field">
          <span>开始日期</span>
          <input v-model="startDate" type="date" />
        </label>
        <label class="filter-field">
          <span>结束日期</span>
          <input v-model="endDate" type="date" />
        </label>
        <div class="date-shortcuts">
          <button type="button" @click="setDateRange('today')">今天</button>
          <button type="button" @click="setDateRange('week')">本周</button>
          <button type="button" @click="setDateRange('month')">本月</button>
          <button type="button" @click="setDateRange('30days')">近30天</button>
        </div>
      </div>

      <div class="filter-row scope-row">
        <label class="filter-field">
          <span>门店（可多选）</span>
          <SearchableMultiSelect
            :model-value="selectedStoreIds"
            :options="storeOptions"
            selected-noun="家门店"
            search-placeholder="搜索门店名称、编号或区域"
            aria-label="流水查询门店"
            @update:model-value="selectedStoreIds = $event.map(String)"
          />
        </label>
        <label class="filter-field">
          <span>物料（可多选）</span>
          <SearchableMultiSelect
            :model-value="selectedItemIds"
            :options="itemOptions"
            selected-noun="项物料"
            search-placeholder="搜索物料名称、编号、分类"
            aria-label="流水查询物料"
            @update:model-value="selectedItemIds = $event.map(Number)"
          />
        </label>
      </div>

      <div class="filter-row direction-row">
        <label class="filter-field">
          <span>方向</span>
          <div class="direction-buttons">
            <button type="button" :class="{ active: !selectedDirections.length }" @click="selectedDirections = []">全部</button>
            <button type="button" :class="{ active: selectedDirections.includes('IN') }" @click="selectedDirections = ['IN']">入库</button>
            <button type="button" :class="{ active: selectedDirections.includes('OUT') }" @click="selectedDirections = ['OUT']">出库</button>
            <button type="button" :class="{ active: selectedDirections.includes('ADJUST') }" @click="selectedDirections = ['ADJUST']">调整</button>
          </div>
        </label>
        <div class="export-action">
          <button class="primary-action" type="button" :disabled="exporting || loading" @click="exportExcel">
            <Download :size="16" aria-hidden="true" />
            {{ exporting ? '正在导出…' : '导出当前筛选流水（Excel）' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Summary bar -->
    <div v-if="queryResult" class="summary-bar">
      <span>共 <strong>{{ total }}</strong> 条流水</span>
      <span>入库 <strong class="positive">{{ qty(queryResult.totalIn) }}</strong></span>
      <span>出库 <strong class="negative">{{ qty(queryResult.totalOut) }}</strong></span>
      <span>净变化 <strong>{{ qty(queryResult.netChange) }}</strong></span>
    </div>

    <!-- Messages -->
    <p v-if="errorMessage" class="form-message error" role="alert">{{ errorMessage }}</p>
    <p v-else-if="successMessage" class="form-message success" role="status">{{ successMessage }}</p>

    <!-- Table -->
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>商品</th>
            <th>数量变化</th>
            <th>来源仓</th>
            <th>目标仓</th>
            <th>门店</th>
            <th>来源</th>
            <th>批次号</th>
            <th>操作人</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="10" class="empty-cell">正在查询…</td>
          </tr>
          <tr v-else-if="!movements.length">
            <td colspan="10" class="empty-cell">当前条件无匹配流水。</td>
          </tr>
          <tr v-for="row in movements" :key="row.id">
            <td>{{ row.createdAt || '-' }}</td>
            <td>{{ row.itemName }}</td>
            <td :class="Number(row.quantityDelta) < 0 ? 'negative' : 'positive'">{{ qty(row.quantityDelta) }}</td>
            <td>{{ row.sourceWarehouseName || (Number(row.quantityDelta) < 0 ? row.warehouseName : '') || '-' }}</td>
            <td>{{ row.targetWarehouseName || (Number(row.quantityDelta) > 0 ? row.warehouseName : '') || '-' }}</td>
            <td>{{ row.storeName || row.storeId || '-' }}</td>
            <td>{{ sourceLabel(row) }}<small v-if="row.sourceId"> {{ row.sourceId }}</small></td>
            <td>{{ row.batchNo || '-' }}</td>
            <td>{{ row.operatorName || '-' }}</td>
            <td>
              <WarehousePrintButtons
                :label="documentLabel(row)"
                :disabled="downloadingId.includes(`/movements/${row.id}`)"
                @download="emit('downloadMovement', row.id, row.itemName, row.movementType)"
              />
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Pagination -->
    <div v-if="totalPages > 1" class="pagination">
      <button type="button" :disabled="page <= 1" @click="goToPage(page - 1)">上一页</button>
      <span>第 {{ page }} / {{ totalPages }} 页</span>
      <button type="button" :disabled="page >= totalPages" @click="goToPage(page + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.movement-report {
  display: grid;
  gap: 16px;
}

.filter-area {
  display: grid;
  gap: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--ds-line, #e5ecea);
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}

.scope-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.filter-field {
  display: grid;
  min-width: 0;
  gap: 5px;
  color: var(--ds-secondary, #526765);
  font-size: 13px;
  font-weight: 700;
}

.filter-field input[type="date"] {
  min-width: 130px;
}

.date-shortcuts {
  display: flex;
  gap: 6px;
}

.date-shortcuts button {
  padding: 5px 10px;
  border: 1px solid var(--ds-line, #d7e2e0);
  border-radius: 5px;
  background: #fff;
  font-size: 12px;
  font-weight: 700;
  color: var(--ds-primary, #276b65);
  cursor: pointer;
}

.date-shortcuts button:hover {
  border-color: var(--ds-primary, #276b65);
  background: var(--ds-surface-muted, #eef4f3);
}

.direction-buttons {
  display: flex;
  gap: 4px;
}

.direction-buttons button {
  padding: 5px 12px;
  border: 1px solid var(--ds-line, #d7e2e0);
  border-radius: 5px;
  background: #fff;
  font-size: 12px;
  font-weight: 700;
  color: var(--ds-secondary, #526765);
  cursor: pointer;
}

.direction-buttons button.active {
  border-color: var(--ds-primary, #276b65);
  background: var(--ds-primary, #276b65);
  color: #fff;
}

.direction-row {
  justify-content: space-between;
}

.export-action {
  display: flex;
  align-items: flex-end;
}

.primary-action {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 38px;
  padding: 0 14px;
  border: 1px solid var(--ds-primary-hover, #1d524d);
  border-radius: 7px;
  background: var(--ds-primary-hover, #1d524d);
  color: #fff;
  font-weight: 800;
  font-size: 13px;
  cursor: pointer;
}

.primary-action:disabled {
  opacity: 0.6;
  cursor: wait;
}

.summary-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 10px 14px;
  border-radius: 7px;
  background: var(--ds-surface-muted, #f3f7f6);
  font-size: 13px;
  color: var(--ds-secondary, #526765);
}

.summary-bar strong {
  color: var(--ds-ink, #182424);
}

.positive { color: var(--good, #1a7a3a); font-weight: 900; }
.negative { color: var(--bad, #c0392b); font-weight: 900; }

.form-message {
  margin: 0;
  padding: 10px 12px;
  border-radius: 7px;
  font-size: 13px;
  font-weight: 700;
}

.form-message.error {
  background: var(--ds-danger-soft, #fde8e8);
  color: var(--ds-danger, #c0392b);
}

.form-message.success {
  background: var(--ds-success-soft, #e8f5e9);
  color: var(--ds-success, #1a7a3a);
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding-top: 8px;
}

.pagination button {
  padding: 6px 12px;
  border: 1px solid var(--ds-line, #d7e2e0);
  border-radius: 5px;
  background: #fff;
  font-size: 13px;
  font-weight: 700;
  color: var(--ds-primary, #276b65);
  cursor: pointer;
}

.pagination button:disabled {
  color: var(--ds-muted, #97a5a2);
  cursor: not-allowed;
}

.pagination span {
  font-size: 13px;
  color: var(--ds-secondary, #526765);
}

@media (max-width: 760px) {
  .scope-row {
    grid-template-columns: 1fr;
  }

  .filter-row {
    flex-direction: column;
    align-items: stretch;
  }

  .direction-row {
    gap: 10px;
  }

  .primary-action {
    width: 100%;
    justify-content: center;
  }
}
</style>
