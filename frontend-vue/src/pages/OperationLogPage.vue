<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { RotateCcw, Search } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'
import {
  getStores,
  queryAuditLogs,
  type AuditStoreScope,
  type OperationLog,
  type OperationLogQuery,
  type StoreInfo,
} from '../api/operations'
import { useForegroundReload } from '../composables/useForegroundReload'
import { formatAuditAction, formatAuditReason, formatAuditTarget, rawAuditTarget } from '../utils/auditLogDisplay'

const ALL_STORES = '__ALL__'
const GLOBAL_LOGS = '__GLOBAL__'

interface AuditFilterDraft {
  keyword: string
  operatorName: string
  action: string
  storeValue: string
  startDate: string
  endDate: string
  pageSize: number
}

function localDateValue(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function defaultDateRange() {
  const end = new Date()
  const start = new Date(end.getFullYear(), end.getMonth(), end.getDate() - 29)
  return { startDate: localDateValue(start), endDate: localDateValue(end) }
}

function dateDistanceInDays(start: string, end: string) {
  const startParts = start.split('-').map(Number)
  const endParts = end.split('-').map(Number)
  if (startParts.length !== 3 || endParts.length !== 3) return Number.NaN
  const startTime = Date.UTC(startParts[0]!, startParts[1]! - 1, startParts[2]!)
  const endTime = Date.UTC(endParts[0]!, endParts[1]! - 1, endParts[2]!)
  return Math.floor((endTime - startTime) / 86_400_000)
}

function cloneFilters(filters: AuditFilterDraft): AuditFilterDraft {
  return { ...filters }
}

const route = useRoute()
const initialRange = defaultDateRange()
const routeStoreId = () => {
  const value = route.query.storeId
  return Array.isArray(value) ? String(value[0] || '') : String(value || '')
}
const routeStoreValue = () => routeStoreId() || ALL_STORES

const draft = reactive<AuditFilterDraft>({
  keyword: '',
  operatorName: '',
  action: '',
  storeValue: routeStoreValue(),
  startDate: initialRange.startDate,
  endDate: initialRange.endDate,
  pageSize: 30,
})
const appliedFilters = ref<AuditFilterDraft | null>(null)
const logs = ref<OperationLog[]>([])
const stores = ref<StoreInfo[]>([])
const operatorOptions = ref<string[]>([])
const actionOptions = ref<string[]>([])
const total = ref(0)
const page = ref(1)
const totalPages = ref(1)
const loading = ref(false)
const storesLoading = ref(false)
const hasLoaded = ref(false)
const error = ref('')
const filterError = ref('')
const storesError = ref('')
const startDateInput = ref<HTMLInputElement | null>(null)
const endDateInput = ref<HTMLInputElement | null>(null)
let queryController: AbortController | null = null
let storesController: AbortController | null = null
let queryRequestId = 0

const storeMap = computed(() => new Map(stores.value.map((store) => [
  store.id,
  store.name || store.code || store.id,
])))

const selectableStores = computed(() => {
  const rows = stores.value.map((store) => ({
    id: store.id,
    label: store.name || store.code || store.id,
    description: [store.code, store.area || store.regionCode, store.status].filter(Boolean).join(' · '),
  }))
  const selectedId = draft.storeValue !== ALL_STORES && draft.storeValue !== GLOBAL_LOGS
    ? draft.storeValue
    : ''
  if (selectedId && !rows.some((store) => store.id === selectedId)) {
    rows.unshift({ id: selectedId, label: `门店 ${selectedId}`, description: '当前链接门店' })
  }
  return rows
})

const sortedOperatorOptions = computed(() => {
  const values = new Set(operatorOptions.value)
  if (draft.operatorName) values.add(draft.operatorName)
  return Array.from(values).sort((left, right) => left.localeCompare(right, 'zh-CN'))
})

const sortedActionOptions = computed(() => {
  const values = new Set(actionOptions.value)
  if (draft.action) values.add(draft.action)
  return Array.from(values).sort((left, right) =>
    formatAuditAction(left).localeCompare(formatAuditAction(right), 'zh-CN'))
})

const hasUnappliedChanges = computed(() =>
  Boolean(appliedFilters.value)
  && JSON.stringify(draft) !== JSON.stringify(appliedFilters.value))

const appliedSummary = computed(() => {
  const filters = appliedFilters.value
  if (!filters) return null
  return {
    date: `${filters.startDate} 至 ${filters.endDate}`,
    store: storeFilterLabel(filters.storeValue),
    operator: filters.operatorName || '全部操作人',
    action: filters.action ? formatAuditAction(filters.action) : '全部动作',
    keyword: filters.keyword || '无关键词',
  }
})

function storeFilterLabel(value: string) {
  if (value === ALL_STORES) return '全部门店及全局操作'
  if (value === GLOBAL_LOGS) return '仅全局操作'
  return storeMap.value.get(value) || `门店 ${value}`
}

function displayStore(storeId?: string) {
  if (!storeId) return '全局操作'
  return storeMap.value.get(storeId) || storeId
}

function validateFilters(filters: AuditFilterDraft) {
  if (!filters.startDate || !filters.endDate) return '请选择完整的开始日期和结束日期'
  const distance = dateDistanceInDays(filters.startDate, filters.endDate)
  if (!Number.isFinite(distance)) return '日期格式不正确，请重新选择'
  if (distance < 0) return '开始日期不能晚于结束日期'
  if (distance > 365) return '单次最多查询 366 天的操作日志，请缩小日期范围'
  if (filters.keyword.trim().length > 100) return '关键词不能超过 100 个字符'
  return ''
}

function snapshotFilters(): AuditFilterDraft {
  return {
    keyword: draft.keyword.trim(),
    operatorName: draft.operatorName,
    action: draft.action,
    storeValue: draft.storeValue,
    startDate: draft.startDate,
    endDate: draft.endDate,
    pageSize: Number(draft.pageSize) || 30,
  }
}

function toQuery(filters: AuditFilterDraft, targetPage: number): OperationLogQuery {
  let storeScope: AuditStoreScope = 'ALL'
  let storeId: string | undefined
  if (filters.storeValue === GLOBAL_LOGS) {
    storeScope = 'GLOBAL'
  } else if (filters.storeValue !== ALL_STORES) {
    storeScope = 'STORE'
    storeId = filters.storeValue
  }
  return {
    keyword: filters.keyword,
    operatorName: filters.operatorName,
    action: filters.action,
    storeScope,
    storeId,
    startDate: filters.startDate,
    endDate: filters.endDate,
    page: targetPage,
    pageSize: filters.pageSize,
  }
}

function isCancelled(loadError: unknown) {
  return Boolean(loadError && typeof loadError === 'object'
    && 'code' in loadError
    && (loadError as { code?: string }).code === 'REQUEST_CANCELLED')
}

async function executeQuery(filters: AuditFilterDraft, targetPage = 1, commitFilters = true) {
  const validationMessage = validateFilters(filters)
  if (validationMessage) {
    filterError.value = validationMessage
    return false
  }

  queryController?.abort()
  const controller = new AbortController()
  queryController = controller
  const requestId = ++queryRequestId
  loading.value = true
  error.value = ''
  filterError.value = ''
  try {
    const result = await queryAuditLogs(toQuery(filters, targetPage), controller.signal)
    if (requestId !== queryRequestId) return false
    logs.value = result.rows
    total.value = result.total
    page.value = result.page
    totalPages.value = Math.max(1, result.totalPages)
    operatorOptions.value = result.operators || []
    actionOptions.value = result.actions || []
    hasLoaded.value = true
    if (commitFilters) appliedFilters.value = cloneFilters(filters)
    markFresh()
    return true
  } catch (loadError) {
    if (requestId !== queryRequestId || isCancelled(loadError)) return false
    error.value = loadError instanceof Error ? loadError.message : '操作日志查询失败，请稍后重试'
    return false
  } finally {
    if (requestId === queryRequestId) loading.value = false
  }
}

const { markFresh } = useForegroundReload(async () => {
  const filters = appliedFilters.value || snapshotFilters()
  const loaded = await executeQuery(filters, page.value, false)
  if (!loaded && error.value) throw new Error(error.value)
}, {
  canReload: () => !loading.value,
})

async function loadStores() {
  storesController?.abort()
  const controller = new AbortController()
  storesController = controller
  storesLoading.value = true
  storesError.value = ''
  try {
    stores.value = await getStores({ signal: controller.signal })
  } catch (loadError) {
    if (isCancelled(loadError)) return
    storesError.value = loadError instanceof Error
      ? `门店选项加载失败：${loadError.message}`
      : '门店选项加载失败，请重试'
  } finally {
    if (storesController === controller) storesLoading.value = false
  }
}

async function requestQuery() {
  const filters = snapshotFilters()
  const validationMessage = validateFilters(filters)
  if (validationMessage) {
    filterError.value = validationMessage
    await nextTick()
    if (!filters.startDate || dateDistanceInDays(filters.startDate, filters.endDate) < 0) {
      startDateInput.value?.focus()
    } else {
      endDateInput.value?.focus()
    }
    return
  }
  await executeQuery(filters, 1, true)
}

async function resetAndQuery() {
  const range = defaultDateRange()
  Object.assign(draft, {
    keyword: '',
    operatorName: '',
    action: '',
    storeValue: routeStoreValue(),
    startDate: range.startDate,
    endDate: range.endDate,
    pageSize: 30,
  })
  filterError.value = ''
  await requestQuery()
}

async function retryQuery() {
  await executeQuery(appliedFilters.value || snapshotFilters(), page.value, false)
}

async function goToPage(targetPage: number) {
  if (!appliedFilters.value || loading.value || targetPage < 1 || targetPage > totalPages.value) return
  await executeQuery(appliedFilters.value, targetPage, false)
}

function setDateRange(mode: 'today' | 'week' | 'month' | '30days') {
  const end = new Date()
  const start = new Date(end)
  if (mode === 'week') {
    const day = (end.getDay() + 6) % 7
    start.setDate(end.getDate() - day)
  } else if (mode === 'month') {
    start.setDate(1)
  } else if (mode === '30days') {
    start.setDate(end.getDate() - 29)
  }
  draft.startDate = localDateValue(start)
  draft.endDate = localDateValue(end)
  filterError.value = ''
}

watch(
  () => routeStoreId(),
  async () => {
    const selected = routeStoreValue()
    draft.storeValue = selected
    const filters = appliedFilters.value
      ? { ...appliedFilters.value, storeValue: selected }
      : snapshotFilters()
    await executeQuery(filters, 1, true)
  },
)

onMounted(() => {
  void loadStores()
  void executeQuery(snapshotFilters(), 1, true)
})

onBeforeUnmount(() => {
  queryController?.abort()
  storesController?.abort()
})
</script>

<template>
  <section class="page-panel logs-page">
    <PageHeader />

    <section class="content-card query-card" aria-labelledby="audit-query-title">
      <div class="query-heading">
        <div>
          <h2 id="audit-query-title">日志查询</h2>
          <p>按操作人、动作、门店和日期组合查询；所有条件同时生效。</p>
        </div>
        <span v-if="appliedFilters" class="result-count" aria-live="polite">共 {{ total }} 条</span>
      </div>

      <form class="query-form" aria-label="操作日志查询条件" novalidate @submit.prevent="requestQuery">
        <div class="filter-grid">
          <label class="filter-field keyword-field">
            <span>关键词</span>
            <input
              v-model="draft.keyword"
              type="search"
              maxlength="100"
              placeholder="对象、编号、月份或说明"
              aria-label="日志关键词"
            />
          </label>

          <label class="filter-field">
            <span>操作人</span>
            <select v-model="draft.operatorName" aria-label="操作人">
              <option value="">全部操作人</option>
              <option v-for="operator in sortedOperatorOptions" :key="operator" :value="operator">
                {{ operator }}
              </option>
            </select>
          </label>

          <label class="filter-field">
            <span>动作</span>
            <select v-model="draft.action" aria-label="日志动作">
              <option value="">全部动作</option>
              <option v-for="action in sortedActionOptions" :key="action" :value="action">
                {{ formatAuditAction(action) }}
              </option>
            </select>
          </label>

          <label class="filter-field store-field">
            <span>门店范围</span>
            <select v-model="draft.storeValue" aria-label="日志门店">
              <option :value="ALL_STORES">全部门店及全局操作</option>
              <option :value="GLOBAL_LOGS">仅全局操作</option>
              <option v-for="store in selectableStores" :key="store.id" :value="store.id">
                {{ store.label }}{{ store.description ? ` · ${store.description}` : '' }}
              </option>
            </select>
          </label>

          <label class="filter-field">
            <span>开始日期</span>
            <input
              ref="startDateInput"
              v-model="draft.startDate"
              type="date"
              :max="draft.endDate || undefined"
              :aria-invalid="filterError ? 'true' : 'false'"
              aria-label="开始日期"
            />
          </label>

          <label class="filter-field">
            <span>结束日期</span>
            <input
              ref="endDateInput"
              v-model="draft.endDate"
              type="date"
              :min="draft.startDate || undefined"
              :max="localDateValue(new Date())"
              :aria-invalid="filterError ? 'true' : 'false'"
              aria-label="结束日期"
            />
          </label>

          <label class="filter-field page-size-field">
            <span>每页显示</span>
            <select v-model.number="draft.pageSize" aria-label="每页条数">
              <option :value="20">20 条</option>
              <option :value="30">30 条</option>
              <option :value="50">50 条</option>
              <option :value="100">100 条</option>
            </select>
          </label>

          <div class="date-shortcuts" aria-label="快捷日期">
            <button type="button" @click="setDateRange('today')">今天</button>
            <button type="button" @click="setDateRange('week')">本周</button>
            <button type="button" @click="setDateRange('month')">本月</button>
            <button type="button" @click="setDateRange('30days')">近30天</button>
          </div>
        </div>

        <div v-if="storesError" class="option-error" role="alert">
          <span>{{ storesError }}</span>
          <button type="button" :disabled="storesLoading" @click="loadStores">
            {{ storesLoading ? '正在加载…' : '重试门店选项' }}
          </button>
        </div>
        <p v-if="filterError" class="filter-error" role="alert">{{ filterError }}</p>

        <div class="query-actions">
          <span v-if="hasUnappliedChanges" class="pending-hint" role="status">
            筛选条件已修改，点击“查询日志”后更新结果。
          </span>
          <button class="secondary-button" type="button" :disabled="loading" @click="resetAndQuery">
            <RotateCcw :size="16" aria-hidden="true" />
            重置条件
          </button>
          <button class="primary-button query-button" type="submit" :disabled="loading">
            <Search :size="16" aria-hidden="true" />
            {{ loading ? '正在查询…' : '查询日志' }}
          </button>
        </div>
      </form>
    </section>

    <div v-if="error" class="error-box logs-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" :disabled="loading" @click="retryQuery">重新查询</button>
    </div>

    <section v-if="appliedSummary" class="applied-summary" aria-label="当前查询条件">
      <span><b>日期</b>{{ appliedSummary.date }}</span>
      <span><b>门店</b>{{ appliedSummary.store }}</span>
      <span><b>操作人</b>{{ appliedSummary.operator }}</span>
      <span><b>动作</b>{{ appliedSummary.action }}</span>
      <span><b>关键词</b>{{ appliedSummary.keyword }}</span>
    </section>

    <section class="content-card result-card" :aria-busy="loading ? 'true' : 'false'">
      <div v-if="loading && !hasLoaded" class="empty-state compact" role="status">
        正在按查询条件读取操作日志…
      </div>
      <div v-else-if="hasLoaded && !logs.length" class="empty-state compact">
        <b>没有匹配的操作日志</b>
        <span>请调整操作人、动作、门店、关键词或日期范围后重新查询。</span>
        <button class="secondary-button" type="button" :disabled="loading" @click="resetAndQuery">清空筛选并查询</button>
      </div>
      <div v-else class="table-wrap">
        <table class="logs-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>操作人</th>
              <th>动作</th>
              <th>对象</th>
              <th>门店</th>
              <th>月份</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in logs" :key="log.id" :class="{ refreshing: loading }">
              <td data-label="时间">{{ log.createdAt || '-' }}</td>
              <td data-label="操作人">{{ log.operatorName || log.operatorId || '-' }}</td>
              <td data-label="动作"><span class="action-badge">{{ formatAuditAction(log.action) }}</span></td>
              <td data-label="对象" :title="rawAuditTarget(log)">{{ formatAuditTarget(log) }}</td>
              <td data-label="门店">{{ displayStore(log.storeId) }}</td>
              <td data-label="月份">{{ log.month || '-' }}</td>
              <td data-label="说明">{{ formatAuditReason(log.reason) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <nav v-if="hasLoaded && totalPages > 1" class="pagination" aria-label="操作日志分页">
        <button class="secondary-button" type="button" :disabled="loading || page <= 1" @click="goToPage(page - 1)">
          上一页
        </button>
        <span>第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
        <button class="secondary-button" type="button" :disabled="loading || page >= totalPages" @click="goToPage(page + 1)">
          下一页
        </button>
      </nav>
    </section>
  </section>
</template>

<style scoped>
.logs-page {
  display: grid;
  min-width: 0;
  gap: 16px;
}

.query-card,
.result-card {
  min-width: 0;
}

.query-heading {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.query-heading h2 {
  margin: 0;
  color: var(--ds-ink);
  font-size: 17px;
}

.query-heading p {
  margin: 5px 0 0;
  color: var(--ds-muted);
  font-size: 13px;
}

.result-count {
  flex: none;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--ds-primary-soft, #e7f5f3);
  color: var(--ds-primary-hover, #286d68);
  font-size: 13px;
  font-weight: 800;
}

.query-form {
  display: grid;
  gap: 12px;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 12px;
  align-items: end;
}

.filter-field {
  display: grid;
  min-width: 0;
  gap: 6px;
  color: var(--ds-secondary);
  font-size: 13px;
  font-weight: 700;
}

.filter-field input,
.filter-field select {
  width: 100%;
  min-width: 0;
}

.date-shortcuts {
  display: flex;
  min-height: 36px;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.date-shortcuts button {
  min-height: 30px;
  padding: 0 9px;
  border: 1px solid var(--ds-line-strong);
  border-radius: 6px;
  background: #fff;
  color: var(--ds-primary-hover);
  font-size: 12px;
  font-weight: 700;
}

.filter-error {
  margin: 0;
  color: var(--ds-danger, #b4232f);
  font-size: 13px;
  font-weight: 700;
}

.option-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 11px;
  border: 1px solid #efddb9;
  border-radius: 6px;
  background: var(--ds-warning-soft, #fff8ea);
  color: #87500f;
  font-size: 13px;
}

.option-error button,
.logs-error button {
  flex: none;
  border: 0;
  background: transparent;
  color: currentColor;
  font-weight: 800;
}

.query-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 2px;
}

.query-actions .primary-button,
.query-actions .secondary-button {
  width: auto;
  min-height: 36px;
  margin: 0;
}

.pending-hint {
  margin-right: auto;
  color: #87500f;
  font-size: 13px;
}

.logs-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
}

.applied-summary {
  display: flex;
  min-width: 0;
  gap: 8px;
  flex-wrap: wrap;
}

.applied-summary span {
  display: inline-flex;
  max-width: 100%;
  min-height: 28px;
  align-items: center;
  gap: 6px;
  padding: 4px 9px;
  border: 1px solid var(--ds-line);
  border-radius: 999px;
  background: #fff;
  color: var(--ds-secondary);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.applied-summary b {
  color: var(--ds-ink);
}

.result-card {
  padding: 0;
  overflow: hidden;
}

.table-wrap {
  max-width: 100%;
  overflow-x: auto;
}

.logs-table {
  min-width: 900px;
}

.logs-table td:first-child {
  white-space: nowrap;
}

.logs-table td:nth-child(2),
.logs-table td:nth-child(3),
.logs-table td:nth-child(5),
.logs-table td:nth-child(6) {
  white-space: nowrap;
}

.logs-table td:last-child {
  min-width: 180px;
  overflow-wrap: anywhere;
}

.logs-table tr.refreshing {
  opacity: .58;
}

.action-badge {
  display: inline-flex;
  min-height: 24px;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--ds-primary-soft, #e7f5f3);
  color: var(--ds-primary-hover, #286d68);
  font-size: 12px;
  font-weight: 700;
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 14px 16px;
  border-top: 1px solid var(--ds-line);
}

.pagination .secondary-button {
  width: auto;
  min-height: 34px;
  margin: 0;
}

.pagination span {
  color: var(--ds-secondary);
  font-size: 13px;
}

@media (max-width: 1080px) {
  .filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .query-card {
    padding: 14px;
  }

  .query-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .result-count {
    align-self: flex-start;
  }

  .filter-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .query-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .pending-hint {
    grid-column: 1 / -1;
    margin: 0;
  }

  .query-actions .primary-button,
  .query-actions .secondary-button {
    width: 100%;
  }

  .option-error,
  .logs-error {
    align-items: stretch;
    flex-direction: column;
  }

  .option-error button,
  .logs-error button {
    align-self: flex-start;
  }

  .result-card {
    border: 0;
    background: transparent;
    overflow: visible;
  }

  .table-wrap {
    overflow: visible;
  }

  .logs-table,
  .logs-table tbody {
    display: block;
    min-width: 0;
    background: transparent;
  }

  .logs-table thead {
    display: none;
  }

  .logs-table tr {
    display: grid;
    gap: 0;
    margin-bottom: 10px;
    border: 1px solid var(--ds-line);
    border-radius: 10px;
    background: #fff;
    overflow: hidden;
  }

  .logs-table td {
    display: grid;
    min-height: 0;
    grid-template-columns: 70px minmax(0, 1fr);
    gap: 10px;
    padding: 9px 11px;
    white-space: normal !important;
    overflow-wrap: anywhere;
  }

  .logs-table td::before {
    content: attr(data-label);
    color: var(--ds-muted);
    font-size: 12px;
    font-weight: 700;
  }

  .pagination {
    padding: 10px 0 0;
    border: 0;
    flex-wrap: wrap;
  }

  .pagination span {
    width: 100%;
    order: -1;
    text-align: center;
  }
}
</style>
