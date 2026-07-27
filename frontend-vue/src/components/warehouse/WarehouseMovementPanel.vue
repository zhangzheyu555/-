<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Download, RotateCcw, Search } from 'lucide-vue-next'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import SearchableMultiSelect from '../common/SearchableMultiSelect.vue'
import {
  downloadMovementExport,
  getMovementFilterOptions,
  queryMovements,
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
}>()

interface MovementFilters {
  warehouseId: string | number
  startDate: string
  endDate: string
  storeIds: string[]
  itemIds: number[]
  directions: string[]
}

function localDateValue(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function defaultDateRange() {
  const today = new Date()
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 29)
  return { startDate: localDateValue(start), endDate: localDateValue(today) }
}

function dateDistanceInDays(start: string, end: string) {
  const startParts = start.split('-').map(Number)
  const endParts = end.split('-').map(Number)
  if (startParts.length !== 3 || endParts.length !== 3) return Number.NaN
  const startTime = Date.UTC(startParts[0]!, startParts[1]! - 1, startParts[2]!)
  const endTime = Date.UTC(endParts[0]!, endParts[1]! - 1, endParts[2]!)
  return Math.floor((endTime - startTime) / 86_400_000)
}

function validateDates(start: string, end: string) {
  if (!start || !end) return '请选择完整的开始日期和结束日期'
  const distance = dateDistanceInDays(start, end)
  if (!Number.isFinite(distance)) return '日期格式不正确，请重新选择'
  if (distance < 0) return '开始日期不能晚于结束日期'
  if (distance > 366) return '查询范围不能超过 366 天，请缩小日期范围'
  return ''
}

const initialRange = defaultDateRange()
const startDate = ref(initialRange.startDate)
const endDate = ref(initialRange.endDate)
const selectedStoreIds = ref<string[]>([])
const selectedItemIds = ref<number[]>([])
const selectedDirections = ref<string[]>([])
const page = ref(1)
const pageSize = 50
const startDateInput = ref<HTMLInputElement | null>(null)
const endDateInput = ref<HTMLInputElement | null>(null)
const storeSelect = ref<InstanceType<typeof SearchableMultiSelect> | null>(null)
const itemSelect = ref<InstanceType<typeof SearchableMultiSelect> | null>(null)

const filterOptions = ref<MovementFilterOptions>({ stores: [], items: [] })
const appliedFilters = ref<MovementFilters | null>(null)
const queryResult = ref<MovementQueryResponse | null>(null)
const optionsLoading = ref(false)
const loading = ref(false)
const exporting = ref(false)
const optionsError = ref('')
const errorMessage = ref('')
const successMessage = ref('')
const lastQueryTime = ref(0)
let queryController: AbortController | null = null
let optionsController: AbortController | null = null
let queryRequestId = 0
let optionsRequestId = 0
let warehouseChangeId = 0

const dateError = computed(() => validateDates(startDate.value, endDate.value))
const movements = computed(() => queryResult.value?.rows || [])
const total = computed(() => queryResult.value?.total || 0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / (queryResult.value?.pageSize || pageSize))))

const storeOptions = computed(() => filterOptions.value.stores.map((store) => ({
  value: store.id,
  label: store.name || store.code || store.id,
  description: [store.code, store.area, store.status].filter(Boolean).join(' · '),
  searchText: [store.name, store.code, store.id, store.area, store.status].filter(Boolean).join(' '),
})))

const itemOptions = computed(() => filterOptions.value.items.map((item) => ({
  value: item.id,
  label: item.name || item.code || String(item.id),
  description: [item.code, item.category, item.unit, item.active ? '' : '已停用'].filter(Boolean).join(' · '),
  searchText: [item.name, item.code, item.category, item.unit].filter(Boolean).join(' '),
})))

function snapshotFilters(warehouseId = props.warehouseId): MovementFilters | null {
  if (warehouseId === undefined || warehouseId === null || warehouseId === '') return null
  return {
    warehouseId,
    startDate: startDate.value,
    endDate: endDate.value,
    storeIds: [...selectedStoreIds.value],
    itemIds: [...selectedItemIds.value],
    directions: [...selectedDirections.value],
  }
}

function filterKey(filters: MovementFilters | null) {
  if (!filters) return ''
  return JSON.stringify({
    warehouseId: String(filters.warehouseId),
    startDate: filters.startDate,
    endDate: filters.endDate,
    storeIds: [...filters.storeIds].sort(),
    itemIds: [...filters.itemIds].sort((left, right) => left - right),
    directions: [...filters.directions].sort(),
  })
}

const hasUnappliedChanges = computed(() => {
  if (!appliedFilters.value) return false
  return filterKey(snapshotFilters()) !== filterKey(appliedFilters.value)
})

function optionSummary(
  ids: Array<string | number>,
  options: Array<{ value: string | number; label: string }>,
  allLabel: string,
  noun: string,
) {
  if (!ids.length) return allLabel
  const labels = ids
    .map((id) => options.find((option) => String(option.value) === String(id))?.label)
    .filter((label): label is string => Boolean(label))
  if (ids.length === 1) return labels[0] || `1 ${noun}`
  return `${labels[0] || ids[0]} 等 ${ids.length} ${noun}`
}

const appliedSummary = computed(() => {
  const filters = appliedFilters.value
  if (!filters) return null
  const direction = !filters.directions.length
    ? '全部方向'
    : filters.directions[0] === 'IN' ? '入库'
      : filters.directions[0] === 'OUT' ? '出库' : '调整'
  return {
    date: `${filters.startDate} 至 ${filters.endDate}`,
    stores: optionSummary(filters.storeIds, storeOptions.value, '全部门店', '家门店'),
    items: optionSummary(filters.itemIds, itemOptions.value, '全部物料', '项物料'),
    direction,
  }
})

const exportDisabledReason = computed(() => {
  if (!appliedFilters.value) return '请先完成一次查询'
  if (hasUnappliedChanges.value) return '筛选条件已修改，请先点击查询'
  if (loading.value) return '正在查询，请稍候'
  return ''
})

function isCancelled(error: unknown) {
  return Boolean(error && typeof error === 'object'
    && 'code' in error
    && (error as { code?: string }).code === 'REQUEST_CANCELLED')
}

async function loadFilterOptions(warehouseId: string | number) {
  optionsController?.abort()
  const controller = new AbortController()
  optionsController = controller
  const requestId = ++optionsRequestId
  optionsLoading.value = true
  optionsError.value = ''
  try {
    const result = await getMovementFilterOptions(warehouseId, controller.signal)
    if (requestId !== optionsRequestId) return
    filterOptions.value = result
  } catch (error) {
    if (requestId !== optionsRequestId || isCancelled(error)) return
    filterOptions.value = { stores: [], items: [] }
    optionsError.value = error instanceof Error
      ? `门店和物料选项加载失败：${error.message}`
      : '门店和物料选项加载失败，请重试'
  } finally {
    if (requestId === optionsRequestId) optionsLoading.value = false
  }
}

async function executeQuery(filters: MovementFilters | null, targetPage = 1) {
  if (!filters) return
  const validationMessage = validateDates(filters.startDate, filters.endDate)
  if (validationMessage) {
    errorMessage.value = validationMessage
    return
  }

  queryController?.abort()
  const controller = new AbortController()
  queryController = controller
  const requestId = ++queryRequestId
  loading.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await queryMovements({
      warehouseId: filters.warehouseId,
      startDate: filters.startDate,
      endDate: filters.endDate,
      storeIds: filters.storeIds.length ? filters.storeIds : undefined,
      itemIds: filters.itemIds.length ? filters.itemIds : undefined,
      directions: filters.directions.length ? filters.directions : undefined,
      page: targetPage,
      pageSize,
    }, controller.signal)
    if (requestId !== queryRequestId) return
    queryResult.value = result
    appliedFilters.value = {
      ...filters,
      storeIds: [...filters.storeIds],
      itemIds: [...filters.itemIds],
      directions: [...filters.directions],
    }
    page.value = result.page
    lastQueryTime.value = Date.now()
  } catch (error) {
    if (requestId !== queryRequestId || isCancelled(error)) return
    errorMessage.value = error instanceof Error ? error.message : '查询失败，请稍后重试'
  } finally {
    if (requestId === queryRequestId) loading.value = false
  }
}

async function requestQuery() {
  if (dateError.value) {
    errorMessage.value = dateError.value
    await nextTick()
    if (!startDate.value || dateDistanceInDays(startDate.value, endDate.value) < 0) startDateInput.value?.focus()
    else endDateInput.value?.focus()
    return
  }
  await executeQuery(snapshotFilters(), 1)
}

async function goToPage(targetPage: number) {
  if (!appliedFilters.value || loading.value || targetPage < 1 || targetPage > totalPages.value) return
  await executeQuery(appliedFilters.value, targetPage)
}

function resetDraftFilters() {
  const range = defaultDateRange()
  startDate.value = range.startDate
  endDate.value = range.endDate
  selectedStoreIds.value = []
  selectedItemIds.value = []
  selectedDirections.value = []
  storeSelect.value?.close()
  itemSelect.value?.close()
}

async function resetAndQuery() {
  resetDraftFilters()
  await nextTick()
  await executeQuery(snapshotFilters(), 1)
}

function setDateRange(preset: 'today' | 'week' | 'month' | '30days') {
  const now = new Date()
  endDate.value = localDateValue(now)
  if (preset === 'today') {
    startDate.value = localDateValue(now)
  } else if (preset === 'week') {
    const weekStart = new Date(now)
    const day = now.getDay()
    weekStart.setDate(now.getDate() - (day === 0 ? 6 : day - 1))
    startDate.value = localDateValue(weekStart)
  } else if (preset === 'month') {
    startDate.value = localDateValue(new Date(now.getFullYear(), now.getMonth(), 1))
  } else {
    startDate.value = localDateValue(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 29))
  }
}

async function exportExcel() {
  const filters = appliedFilters.value
  if (!filters || exportDisabledReason.value) {
    errorMessage.value = exportDisabledReason.value || '请先完成一次查询'
    return
  }
  exporting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const filename = await downloadMovementExport({
      warehouseId: filters.warehouseId,
      startDate: filters.startDate,
      endDate: filters.endDate,
      storeIds: filters.storeIds.length ? filters.storeIds : undefined,
      itemIds: filters.itemIds.length ? filters.itemIds : undefined,
      directions: filters.directions.length ? filters.directions : undefined,
    }, `出入库流水_${filters.startDate}_${filters.endDate}.xlsx`)
    successMessage.value = `流水已导出：${filename}`
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '导出失败，请稍后重试'
  } finally {
    exporting.value = false
  }
}

async function retryFilterOptions() {
  if (props.warehouseId === undefined || props.warehouseId === null || props.warehouseId === '') return
  await loadFilterOptions(props.warehouseId)
}

let focusReloadLocked = false
function onVisibilityChange() {
  if (document.hidden || focusReloadLocked || loading.value || !appliedFilters.value) return
  if (Date.now() - lastQueryTime.value <= 60_000) return
  focusReloadLocked = true
  executeQuery(appliedFilters.value, page.value).finally(() => {
    focusReloadLocked = false
  })
}

function refresh() {
  return executeQuery(appliedFilters.value || snapshotFilters(), page.value)
}
defineExpose({ refresh })

watch(
  () => props.warehouseId,
  async (warehouseId) => {
    const changeId = ++warehouseChangeId
    queryController?.abort()
    optionsController?.abort()
    resetDraftFilters()
    filterOptions.value = { stores: [], items: [] }
    appliedFilters.value = null
    queryResult.value = null
    errorMessage.value = ''
    successMessage.value = ''
    if (warehouseId === undefined || warehouseId === null || warehouseId === '') return
    await loadFilterOptions(warehouseId)
    if (changeId !== warehouseChangeId) return
    await executeQuery(snapshotFilters(warehouseId), 1)
  },
  { immediate: true },
)

watch(
  [startDate, endDate, selectedStoreIds, selectedItemIds, selectedDirections],
  () => {
    successMessage.value = ''
    if (!dateError.value && errorMessage.value === '开始日期不能晚于结束日期') errorMessage.value = ''
  },
  { deep: true },
)

onMounted(() => document.addEventListener('visibilitychange', onVisibilityChange))
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  queryController?.abort()
  optionsController?.abort()
})

function qty(value: number | undefined) {
  return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function sourceLabel(row: WarehouseStockMovement) {
  if (row.sourceType === 'REQUISITION') return '门店叫货发货'
  if (row.sourceType === 'RETURN_RECEIVE' || row.sourceType === 'STORE_RETURN') return '配送退货入库'
  if (row.sourceType?.includes('TRANSFER')) return '仓间调拨'
  if (row.sourceType?.includes('RECEIVE') || row.sourceType === 'PURCHASE_ORDER') return '采购入库'
  if (row.sourceType?.includes('LOSS')) return '报损出库'
  return row.sourceType || '库存流水'
}

function movementDirection(row: WarehouseStockMovement) {
  if (row.movementType?.startsWith('ADJUST')) return '调整'
  if (Number(row.quantityDelta) > 0) return '入库'
  if (Number(row.quantityDelta) < 0) return '出库'
  return '状态变更'
}

function documentLabel(row: WarehouseStockMovement) {
  if (row.sourceType === 'REQUISITION' && Number(row.quantityDelta) < 0) return '下载出库单'
  if (Number(row.quantityDelta) > 0) return '下载入库单'
  return '下载流水单'
}
</script>

<template>
  <div class="content-card movement-report">
    <div class="table-heading">
      <div>
        <h3>出入库查询</h3>
        <span>先选择门店、日期和物料，再点击查询；导出内容始终与当前查询结果一致</span>
      </div>
    </div>

    <section class="filter-area" aria-label="出入库查询条件">
      <div class="filter-grid">
        <div class="filter-field">
          <span id="movement-store-label" class="filter-label">门店</span>
          <SearchableMultiSelect
            ref="storeSelect"
            :model-value="selectedStoreIds"
            :options="storeOptions"
            :loading="optionsLoading"
            :disabled="optionsLoading || Boolean(optionsError)"
            compact
            placeholder="全部门店"
            selected-noun="家门店"
            search-placeholder="搜索门店名称、编号或区域"
            aria-label="选择查询门店"
            empty-message="没有匹配的门店"
            @update:model-value="selectedStoreIds = $event.map(String)"
          />
        </div>

        <div
          class="filter-field date-field"
          :class="{ invalid: Boolean(dateError) }"
          role="group"
          aria-labelledby="movement-date-label"
        >
          <span id="movement-date-label" class="filter-label">日期范围</span>
          <div class="date-inputs">
            <label class="date-control">
              <span class="date-input-label">开始</span>
              <input
                ref="startDateInput"
                v-model="startDate"
                type="date"
                :max="endDate || undefined"
                :aria-invalid="dateError ? 'true' : 'false'"
                :aria-describedby="dateError ? 'movement-date-error' : undefined"
              />
            </label>
            <span class="date-separator" aria-hidden="true">至</span>
            <label class="date-control">
              <span class="date-input-label">结束</span>
              <input
                ref="endDateInput"
                v-model="endDate"
                type="date"
                :min="startDate || undefined"
                :max="localDateValue(new Date())"
                :aria-invalid="dateError ? 'true' : 'false'"
                :aria-describedby="dateError ? 'movement-date-error' : undefined"
              />
            </label>
          </div>
          <div class="date-shortcuts" aria-label="快捷日期">
            <button type="button" @click="setDateRange('today')">今天</button>
            <button type="button" @click="setDateRange('week')">本周</button>
            <button type="button" @click="setDateRange('month')">本月</button>
            <button type="button" @click="setDateRange('30days')">近30天</button>
          </div>
          <small v-if="dateError" id="movement-date-error" class="field-error" role="alert">
            {{ dateError }}
          </small>
        </div>

        <div class="filter-field">
          <span id="movement-item-label" class="filter-label">物料</span>
          <SearchableMultiSelect
            ref="itemSelect"
            :model-value="selectedItemIds"
            :options="itemOptions"
            :loading="optionsLoading"
            :disabled="optionsLoading || Boolean(optionsError)"
            compact
            placeholder="全部物料"
            selected-noun="项物料"
            search-placeholder="搜索物料名称、编号、分类"
            aria-label="选择查询物料"
            empty-message="没有匹配的物料"
            @update:model-value="selectedItemIds = $event.map(Number)"
          />
        </div>
      </div>

      <div v-if="optionsError" class="filter-options-error" role="alert">
        <span>{{ optionsError }}</span>
        <button type="button" :disabled="optionsLoading" @click="retryFilterOptions">
          {{ optionsLoading ? '正在重试…' : '重新加载选项' }}
        </button>
      </div>

      <div class="filter-footer">
        <div class="filter-field direction-field">
          <span>出入库方向</span>
          <div class="direction-buttons">
            <button type="button" :class="{ active: !selectedDirections.length }" @click="selectedDirections = []">全部流水</button>
            <button type="button" :class="{ active: selectedDirections.includes('IN') }" @click="selectedDirections = ['IN']">入库</button>
            <button type="button" :class="{ active: selectedDirections.includes('OUT') }" @click="selectedDirections = ['OUT']">出库</button>
            <button type="button" :class="{ active: selectedDirections.includes('ADJUST') }" @click="selectedDirections = ['ADJUST']">调整</button>
          </div>
        </div>

        <div class="filter-actions">
          <button class="secondary-action" type="button" :disabled="loading || optionsLoading" @click="resetAndQuery">
            <RotateCcw :size="16" aria-hidden="true" />
            重置
          </button>
          <button
            class="primary-action"
            type="button"
            :disabled="loading || optionsLoading || Boolean(dateError) || !warehouseId"
            @click="requestQuery"
          >
            <Search :size="16" aria-hidden="true" />
            {{ loading ? '正在查询…' : '查询' }}
          </button>
          <button
            class="export-button"
            type="button"
            :disabled="exporting || Boolean(exportDisabledReason)"
            :title="exportDisabledReason || '导出当前查询结果'"
            @click="exportExcel"
          >
            <Download :size="16" aria-hidden="true" />
            {{ exporting ? '正在导出…' : '导出查询结果' }}
          </button>
        </div>
      </div>
    </section>

    <p v-if="hasUnappliedChanges" class="pending-filter-message" role="status">
      筛选条件已修改，点击“查询”后更新下方结果。
    </p>

    <div v-if="queryResult && appliedSummary" class="query-result-header">
      <div class="applied-filters" aria-label="当前查询条件">
        <span><b>日期</b>{{ appliedSummary.date }}</span>
        <span><b>门店</b>{{ appliedSummary.stores }}</span>
        <span><b>物料</b>{{ appliedSummary.items }}</span>
        <span><b>方向</b>{{ appliedSummary.direction }}</span>
      </div>
      <div class="summary-bar">
        <span>共 <strong>{{ total }}</strong> 条</span>
        <span>入库 <strong class="positive">{{ qty(queryResult.totalIn) }}</strong></span>
        <span>出库 <strong class="negative">{{ qty(queryResult.totalOut) }}</strong></span>
        <span>净变化 <strong>{{ qty(queryResult.netChange) }}</strong></span>
      </div>
    </div>

    <p v-if="errorMessage" class="form-message error" role="alert">{{ errorMessage }}</p>
    <p v-else-if="successMessage" class="form-message success" role="status">{{ successMessage }}</p>
    <p v-if="loading" class="query-progress" role="status">正在按所选门店、日期和物料查询，请稍候…</p>

    <div class="table-wrap" :aria-busy="loading ? 'true' : 'false'">
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
          <tr v-if="loading && !queryResult">
            <td colspan="10" class="empty-cell">正在查询…</td>
          </tr>
          <tr v-else-if="!loading && !queryResult">
            <td colspan="10" class="empty-cell">请选择门店、日期和物料后点击查询。</td>
          </tr>
          <tr v-else-if="!loading && !movements.length">
            <td colspan="10" class="empty-cell">当前查询条件没有出入库记录，请调整门店、日期或物料。</td>
          </tr>
          <tr v-for="row in movements" :key="row.id" :class="{ 'is-refreshing': loading }">
            <td>{{ row.createdAt || '-' }}</td>
            <td>{{ row.itemName }}</td>
            <td>
              <span :class="Number(row.quantityDelta) < 0 ? 'negative' : 'positive'">{{ qty(row.quantityDelta) }}</span>
              <small class="direction-label">{{ movementDirection(row) }}</small>
            </td>
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

    <div v-if="totalPages > 1" class="pagination">
      <button type="button" :disabled="loading || page <= 1" @click="goToPage(page - 1)">上一页</button>
      <span>第 {{ page }} / {{ totalPages }} 页</span>
      <button type="button" :disabled="loading || page >= totalPages" @click="goToPage(page + 1)">下一页</button>
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
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--ds-line, #dce6e4);
  border-radius: 10px;
  background: var(--ds-surface-muted, #f7faf9);
}

.filter-grid {
  display: grid;
  grid-template-columns: minmax(180px, .8fr) minmax(360px, 1.45fr) minmax(180px, .8fr);
  gap: 14px;
  align-items: start;
}

.filter-field {
  display: grid;
  min-width: 0;
  align-content: start;
  gap: 6px;
  color: var(--ds-secondary, #526765);
  font-size: 13px;
  font-weight: 800;
}

.filter-label {
  display: flex;
  min-height: 18px;
  align-items: center;
  line-height: 18px;
}

.filter-grid > .filter-field:last-child :deep(.searchable-multi-select__panel) {
  right: 0;
  left: auto;
}

.date-field {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.date-inputs {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
}

.date-control {
  display: block;
  min-width: 0;
}

.date-input-label {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.date-inputs input {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  height: 40px;
  padding: 0 9px;
  border: 1px solid var(--ds-line, #d7e2e0);
  border-radius: 6px;
  background: #fff;
  color: var(--ds-ink, #182424);
  font: inherit;
  font-size: 13px;
}

.date-inputs input:focus {
  border-color: var(--ds-primary, #276b65);
  outline: 0;
  box-shadow: 0 0 0 2px rgba(39, 107, 101, .12);
}

.date-field.invalid .date-inputs input {
  border-color: var(--ds-danger, #c33f4d);
}

.date-separator {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  color: var(--ds-muted, #6f817f);
  font-size: 12px;
}

.date-shortcuts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.date-shortcuts button {
  min-height: 28px;
  padding: 3px 9px;
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

.field-error {
  color: var(--ds-danger, #b83243);
  font-size: 12px;
  font-weight: 700;
}

.filter-options-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid #efb5b5;
  border-radius: 7px;
  background: #fff5f5;
  color: #9b2c2c;
  font-size: 13px;
  font-weight: 700;
}

.filter-options-error button {
  flex: 0 0 auto;
  min-height: 32px;
  padding: 4px 10px;
  border: 1px solid #c33f4d;
  border-radius: 5px;
  background: #fff;
  color: #a52f3e;
  font: inherit;
  font-weight: 800;
  cursor: pointer;
}

.filter-footer {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--ds-line, #dce6e4);
}

.direction-field {
  flex: 1 1 auto;
}

.direction-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.direction-buttons button {
  min-height: 34px;
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

.direction-buttons button:focus-visible,
.date-shortcuts button:focus-visible,
.filter-actions button:focus-visible,
.pagination button:focus-visible {
  outline: 3px solid rgba(39, 107, 101, .2);
  outline-offset: 1px;
}

.filter-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.primary-action,
.secondary-action,
.export-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 38px;
  padding: 0 14px;
  border-radius: 7px;
  font: inherit;
  font-weight: 800;
  font-size: 13px;
  cursor: pointer;
}

.primary-action {
  border: 1px solid var(--ds-primary-hover, #1d524d);
  background: var(--ds-primary-hover, #1d524d);
  color: #fff;
}

.secondary-action,
.export-button {
  border: 1px solid var(--ds-line, #cfdcda);
  background: #fff;
  color: var(--ds-primary, #276b65);
}

.export-button {
  border-color: var(--ds-primary, #276b65);
}

.primary-action:disabled,
.secondary-action:disabled,
.export-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.query-result-header {
  display: grid;
  gap: 10px;
}

.applied-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.applied-filters span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  padding: 6px 9px;
  border: 1px solid var(--ds-line, #dce6e4);
  border-radius: 999px;
  background: #fff;
  color: var(--ds-secondary, #526765);
  font-size: 12px;
}

.applied-filters b {
  color: var(--ds-ink, #182424);
}

.pending-filter-message,
.query-progress {
  margin: 0;
  padding: 9px 12px;
  border-radius: 7px;
  font-size: 13px;
  font-weight: 700;
}

.pending-filter-message {
  border: 1px solid #e7cf93;
  background: #fff9e8;
  color: #805b16;
}

.query-progress {
  background: var(--ds-surface-muted, #eef4f3);
  color: var(--ds-primary, #276b65);
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

.positive {
  color: var(--good, #1a7a3a);
  font-weight: 900;
}

.negative {
  color: var(--bad, #c0392b);
  font-weight: 900;
}

.direction-label {
  display: block;
  margin-top: 2px;
  color: var(--ds-muted, #6f817f);
  font-size: 11px;
}

tr.is-refreshing {
  opacity: .55;
}

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

@media (max-width: 1080px) {
  .filter-grid {
    grid-template-columns: 1fr;
  }

  .filter-grid > .filter-field:last-child :deep(.searchable-multi-select__panel) {
    right: auto;
    left: 0;
  }

  .filter-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .filter-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 560px) {
  .movement-report {
    gap: 12px;
  }

  .filter-area {
    padding: 12px;
  }

  .date-inputs {
    grid-template-columns: 1fr;
  }

  .date-control {
    display: grid;
    gap: 4px;
  }

  .date-input-label {
    position: static;
    width: auto;
    height: auto;
    margin: 0;
    overflow: visible;
    clip: auto;
    white-space: normal;
    color: var(--ds-muted, #6f817f);
    font-size: 11px;
  }

  .date-separator {
    display: none;
  }

  .date-shortcuts {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .filter-options-error {
    align-items: stretch;
    flex-direction: column;
  }

  .direction-buttons {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .filter-actions {
    display: grid;
    grid-template-columns: 1fr;
  }

  .primary-action,
  .secondary-action,
  .export-button {
    width: 100%;
  }

  .applied-filters {
    display: grid;
  }

  .applied-filters span {
    border-radius: 7px;
  }
}

@media (prefers-reduced-motion: reduce) {
  tr.is-refreshing {
    opacity: 1;
  }
}
</style>
