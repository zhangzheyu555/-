<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { Check, ChevronDown, Download, Eye, Filter, UserPlus } from 'lucide-vue-next'
import {
  approveSalaryRecord, assignSalaryEmployee, getSalaryAssignmentCandidates, getSalaryBusinessMetrics,
  getSalaryRecords, saveSalaryAttendance, saveSalaryRecord,
  type SalaryAssignmentCandidate, type SalaryBusinessMetrics, type SalaryRecord, type SalaryRecordPayload,
} from '../../api/finance'
import { ApiError } from '../../api/http'
import { useForegroundReload } from '../../composables/useForegroundReload'
import { isHourlySalaryRecord, useSalaryPage, money, userError, wholeNumber } from '../../composables/useSalaryPage'
import { useSalaryWorkflow } from '../../composables/useSalaryWorkflow'
import SearchInput from '../common/SearchInput.vue'
import SearchableSingleSelect from '../common/SearchableSingleSelect.vue'
import SalarySummary from './SalarySummary.vue'
import SalaryTable from './SalaryTable.vue'
import SalaryGenerationDialog from './SalaryGenerationDialog.vue'
import SalaryDetailPanel from './SalaryDetailPanel.vue'
import SalaryAddEmployeeDialog from './SalaryAddEmployeeDialog.vue'
import ActionConfirmDialog from '../ui/ActionConfirmDialog.vue'

const props = withDefaults(defineProps<{
  initialStoreId?: string
  initialMonth?: string
  embedded?: boolean
}>(), { initialStoreId: '', initialMonth: '', embedded: false })
const emit = defineEmits<{
  dirtyChange: [dirty: boolean]
}>()

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '全部' },
  { value: 'PENDING_GENERATION', label: '待生成' },
  { value: 'PENDING_REVIEW', label: '待审核' },
  { value: 'PENDING_PAYMENT', label: '待发放' },
]

const page = useSalaryPage()
const actionError = ref('')
const businessMetrics = ref<SalaryBusinessMetrics | null>(null)
const businessMetricsScopeKey = ref('')
const businessMetricsLoading = ref(false)
const businessMetricsError = ref('')
const selectedRowKey = ref('')
const selectedApprovalRecordIds = ref(new Set<string>())
const selectedGenerationEmployeeIds = ref(new Set<string>())
const batchApprovalRecords = ref<SalaryRecord[]>([])
const batchApprovalOpen = ref(false)
const batchApproving = ref(false)
const addEmployeeOpen = ref(false)
const addEmployeeCandidates = ref<SalaryAssignmentCandidate[]>([])
const addEmployeeLoading = ref(false)
const addEmployeeSaving = ref(false)
const addEmployeeError = ref('')
const initialScopeBlocked = ref(false)
const salaryDetailDirty = ref(false)
const salaryDiscardOpen = ref(false)
const filterMenuOpen = ref(false)
const filterMenuRoot = ref<HTMLElement | null>(null)
const filterButton = ref<HTMLButtonElement | null>(null)
let pendingSalaryDiscardAction: (() => void) | null = null
let pendingSalaryDiscardCancel: (() => void) | null = null
let businessMetricsRequestController: AbortController | null = null

const workflow = useSalaryWorkflow({
  selectedMonth: page.selectedMonth,
  selectedStoreId: page.effectiveStoreId,
  selectedBrandId: page.effectiveBrandId,
  hasValidMonth: page.hasValidMonth,
  canGenerate: page.canGenerate,
  canEdit: page.canEdit,
  pageError: actionError,
  successMessage: page.successMessage,
  loadPage: async () => { await reloadSalaryData() },
  onDeleted: (record) => {
    if (selectedRowKey.value === rowKey(record)) selectedRowKey.value = ''
    const nextCheckedIds = new Set(selectedApprovalRecordIds.value)
    nextCheckedIds.delete(record.id)
    selectedApprovalRecordIds.value = nextCheckedIds
  },
})

const { markFresh } = useForegroundReload(async () => {
  const loaded = await reloadSalaryData(page.page.value, {
    protectUnsavedDetail: true,
  })
  if (!loaded) throw new Error(page.error.value || businessMetricsError.value || '工资数据加载失败')
}, {
  canReload: () => !page.initializing.value
    && !initialScopeBlocked.value
    && !page.loading.value
    && !businessMetricsLoading.value
    && !workflow.actioningId.value
    && !salaryDetailDirty.value
    && !workflow.showPreview.value
    && !workflow.actionConfirmation.value
    && !batchApprovalOpen.value
    && !addEmployeeOpen.value,
})

const title = computed(() => {
  if (page.isStoreManager.value) return `${page.selectedStoreName.value} · 员工工资`
  return page.selectedStoreId.value === 'all'
    ? '员工工资表'
    : `${page.selectedStoreName.value} · 员工工资表`
})
const salaryStoreOptions = computed(() => page.filteredAccessibleStores.value.map((store) => ({
  value: store.id,
  label: `${store.brandName ? `${store.brandName} · ` : ''}${store.name || store.id}`,
  description: [store.code, store.area || store.regionCode, store.status].filter(Boolean).join(' · '),
  searchText: [store.name, store.code, store.area, store.regionCode, store.status, store.brandName].filter(Boolean).join(' '),
})))
const selectedRecord = computed(() => page.filteredRows.value.find((row) => rowKey(row) === selectedRowKey.value) || null)
const grossTotal = computed(() => Number(page.summary.value?.grossTotal || 0))
const totalHours = computed(() => Number(page.pageData.value?.workHoursTotal || 0))
const vacationBalance = computed(() => Number(page.pageData.value?.vacationBalanceTotal || 0))
const employeeCount = computed(() => page.employeeCount.value)
const currentBusinessMetricsScopeKey = computed(() => JSON.stringify([
  page.effectiveStoreId.value,
  page.selectedMonth.value,
  page.effectiveBrandId.value ?? '',
]))
const visibleBusinessMetrics = computed(() => (
  businessMetricsScopeKey.value === currentBusinessMetricsScopeKey.value
    ? businessMetrics.value
    : null
))
const revenue = computed(() => metricNumber(visibleBusinessMetrics.value?.revenue))
const effectiveHours = computed(() => metricNumber(visibleBusinessMetrics.value?.effectiveHours))
const revenuePerHour = computed(() => metricNumber(visibleBusinessMetrics.value?.hourlyRevenue))
const perCapitaOutput = computed(() => metricNumber(visibleBusinessMetrics.value?.perCapitaOutput))
const commissionTotal = computed(() => metricNumber(visibleBusinessMetrics.value?.commissionTotal))
const storeFund = computed(() => metricNumber(visibleBusinessMetrics.value?.storeFund))
const efficiencyRatio = computed(() => revenue.value !== null && revenue.value > 0 ? grossTotal.value / revenue.value : null)
const canAddEmployee = computed(() => page.canEdit.value
  && page.hasValidMonth.value
  && Boolean(page.effectiveStoreId.value)
  && page.effectiveStoreId.value !== 'all'
  && page.isEffectiveStoreActive.value
  && !initialScopeBlocked.value)
const salaryMonthModel = computed({
  get: () => page.selectedMonth.value,
  set: (value: string) => requestSalaryDiscard(() => { page.selectedMonth.value = value }),
})
const salaryStoreModel = computed({
  get: () => page.selectedStoreId.value,
  set: (value: string) => requestSalaryDiscard(() => { page.selectedStoreId.value = value }),
})
const salaryStatusModel = computed({
  get: () => page.statusFilter.value,
  set: (value: string) => requestSalaryDiscard(() => { page.statusFilter.value = value }),
})
const activeSalaryStatusLabel = computed(() =>
  STATUS_OPTIONS.find((option) => option.value === page.statusFilter.value)?.label || '全部')
const salaryKeywordModel = computed({
  get: () => page.keyword.value,
  set: (value: string) => requestSalaryDiscard(() => { page.keyword.value = value }),
})
const generationSelectionModel = computed({
  get: () => [...selectedGenerationEmployeeIds.value],
  set: (values: string[]) => {
    selectedGenerationEmployeeIds.value = new Set(values
      .map((value) => String(value || '').trim())
      .filter(Boolean))
  },
})
const generationScopeLabel = computed(() => page.effectiveStoreId.value === 'all'
  ? '全部授权门店'
  : page.selectedStoreName.value)

function requestSalaryDiscard(action: () => void, cancel: () => void = () => {}) {
  if (!salaryDetailDirty.value) {
    action()
    return
  }
  if (salaryDiscardOpen.value) {
    cancel()
    return
  }
  pendingSalaryDiscardAction = action
  pendingSalaryDiscardCancel = cancel
  salaryDiscardOpen.value = true
}

function keepSalaryEditing() {
  const cancel = pendingSalaryDiscardCancel
  pendingSalaryDiscardAction = null
  pendingSalaryDiscardCancel = null
  salaryDiscardOpen.value = false
  cancel?.()
}

function discardSalaryChanges() {
  const action = pendingSalaryDiscardAction
  pendingSalaryDiscardAction = null
  pendingSalaryDiscardCancel = null
  salaryDiscardOpen.value = false
  selectedRowKey.value = ''
  action?.()
}

function filterMenuItems() {
  return Array.from(filterMenuRoot.value
    ?.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]') || [])
}

async function openFilterMenu() {
  if (filterMenuOpen.value) return
  filterMenuOpen.value = true
  await nextTick()
  const items = filterMenuItems()
  const activeIndex = STATUS_OPTIONS.findIndex(
    (option) => option.value === page.statusFilter.value)
  items[Math.max(0, activeIndex)]?.focus()
}

function closeFilterMenu(returnFocus = false) {
  filterMenuOpen.value = false
  if (returnFocus) void nextTick(() => filterButton.value?.focus())
}

function toggleFilterMenu() {
  if (filterMenuOpen.value) {
    closeFilterMenu()
    return
  }
  void openFilterMenu()
}

function selectSalaryStatus(value: string) {
  const changed = value !== page.statusFilter.value
  closeFilterMenu(true)
  if (changed) salaryStatusModel.value = value
}

function closeFilterMenuOnOutsideClick(event: PointerEvent) {
  if (!filterMenuOpen.value) return
  const target = event.target
  if (target instanceof Node && !filterMenuRoot.value?.contains(target)) {
    filterMenuOpen.value = false
  }
}

function navigateFilterMenu(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeFilterMenu(true)
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const items = filterMenuItems()
  if (!items.length) return
  event.preventDefault()
  const currentIndex = items.indexOf(document.activeElement as HTMLButtonElement)
  let nextIndex = 0
  if (event.key === 'End') nextIndex = items.length - 1
  else if (event.key === 'ArrowUp') nextIndex = currentIndex <= 0 ? items.length - 1 : currentIndex - 1
  else if (event.key === 'ArrowDown') nextIndex = currentIndex < 0 || currentIndex === items.length - 1 ? 0 : currentIndex + 1
  items[nextIndex]?.focus()
}

function metricNumber(value: number | null | undefined) {
  if (value === null || value === undefined) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function revenueMoney(value: number) {
  return money(value)
}

function metricReason(kind: 'revenue' | 'hours' | 'output' | 'fund') {
  if (businessMetricsLoading.value && !visibleBusinessMetrics.value) return '加载中'
  if (businessMetricsError.value) return businessMetricsError.value
  if (businessMetricsLoading.value) return '正在同步最新数据'
  if (revenue.value === null) return '当前范围缺少营业额'
  if (kind === 'revenue' && revenue.value <= 0) return '当前范围营业额为0'
  if (kind === 'hours' && (effectiveHours.value === null || effectiveHours.value <= 0)) return '当前范围缺少有效工时'
  if (kind === 'output' && perCapitaOutput.value === null) return '当前范围缺少产值数据'
  if (kind === 'fund' && storeFund.value === null) return '当前范围缺少提成池数据'
  return ''
}

async function loadBusinessMetrics() {
  businessMetricsRequestController?.abort()
  const controller = new AbortController()
  const requestScopeKey = currentBusinessMetricsScopeKey.value
  businessMetricsRequestController = controller
  businessMetricsError.value = ''
  if (!page.hasValidMonth.value || !page.isEffectiveStoreActive.value || initialScopeBlocked.value) {
    businessMetrics.value = null
    businessMetricsScopeKey.value = ''
    businessMetricsRequestController = null
    businessMetricsLoading.value = false
    return false
  }
  businessMetricsLoading.value = true
  try {
    const metrics = await getSalaryBusinessMetrics({
      month: page.selectedMonth.value,
      storeId: page.effectiveStoreId.value === 'all' ? undefined : page.effectiveStoreId.value,
      brandId: page.effectiveBrandId.value,
    }, controller.signal)
    if (controller.signal.aborted || requestScopeKey !== currentBusinessMetricsScopeKey.value) return false
    businessMetrics.value = metrics
    businessMetricsScopeKey.value = requestScopeKey
    return true
  } catch (error) {
    if (controller.signal.aborted || (error instanceof ApiError && error.code === 'REQUEST_CANCELLED')) return false
    if (requestScopeKey === currentBusinessMetricsScopeKey.value) {
      businessMetricsError.value = '工资经营指标暂时无法获取'
    }
    return false
  } finally {
    if (businessMetricsRequestController === controller) {
      businessMetricsRequestController = null
      businessMetricsLoading.value = false
    }
  }
}

async function reloadSalaryData(
  p = page.page.value,
  options: { protectUnsavedDetail?: boolean } = {},
) {
  const pageLoaded = await page.loadPage(p, {
    canApply: options.protectUnsavedDetail
      ? () => !salaryDetailDirty.value
      : undefined,
  })
  if (!pageLoaded) return false
  if (options.protectUnsavedDetail && salaryDetailDirty.value) return false
  const metricsLoaded = await loadBusinessMetrics()
  if (!metricsLoaded) return false
  markFresh()
  return true
}

async function loadSalaryPage(p: number) {
  const pageLoaded = await page.loadPage(p)
  if (!pageLoaded) return
  markFresh()
}

function requestSalaryPage(p: number) {
  requestSalaryDiscard(() => { void loadSalaryPage(p) })
}

async function retrySalaryData() {
  await reloadSalaryData()
}

function applyInitialScope() {
  if (props.initialMonth && /^\d{4}-(0[1-9]|1[0-2])$/.test(props.initialMonth)) page.selectedMonth.value = props.initialMonth
  if (page.isStoreManager.value) return
  initialScopeBlocked.value = false
  if (!props.initialStoreId) return
  if (page.accessibleStores.value.some((store) => store.id === props.initialStoreId)) {
    page.selectedStoreId.value = props.initialStoreId
    return
  }
  initialScopeBlocked.value = true
  page.pageData.value = null
  businessMetrics.value = null
  businessMetricsScopeKey.value = ''
  actionError.value = '该门店已停用或不在当前工资权限范围内，不能继续查看或编辑工资。'
}

async function previewGeneration(employeeIds?: string[]) {
  if (!page.effectiveStoreId.value) {
    actionError.value = '当前没有可生成工资的授权门店。'
    return
  }
  if (!page.isEffectiveStoreActive.value) {
    actionError.value = '该门店已停用，不能生成工资。'
    return
  }
  const requestedEmployeeIds = employeeIds === undefined
    ? undefined
    : Array.from(new Set(employeeIds.map((id) => String(id || '').trim()).filter(Boolean)))
  if (requestedEmployeeIds !== undefined && !requestedEmployeeIds.length) {
    actionError.value = '请先选择需要生成工资的员工。'
    return
  }
  const report = await workflow.doPreview(requestedEmployeeIds)
  if (!report) return
  const candidateIds = Array.from(new Set((report.candidates || [])
    .map((candidate) => String(candidate.employeeId || '').trim())
    .filter(Boolean)))
  const availableIds = new Set(candidateIds)
  selectedGenerationEmployeeIds.value = new Set(requestedEmployeeIds === undefined
    ? candidateIds
    : requestedEmployeeIds.filter((employeeId) => availableIds.has(employeeId)))
}

async function previewSelectedGeneration() {
  await previewGeneration([...selectedGenerationEmployeeIds.value])
}

async function confirmGeneration() {
  const generated = await workflow.doGenerate([...selectedGenerationEmployeeIds.value])
  if (generated) {
    selectedGenerationEmployeeIds.value = new Set()
    return
  }
  const availableIds = new Set((workflow.previewData.value?.candidates || [])
    .map((candidate) => String(candidate.employeeId || '').trim())
    .filter(Boolean))
  selectedGenerationEmployeeIds.value = new Set(
    [...selectedGenerationEmployeeIds.value].filter((employeeId) => availableIds.has(employeeId)),
  )
}

async function openAddEmployee() {
  actionError.value = ''
  addEmployeeError.value = ''
  if (!canAddEmployee.value) {
    actionError.value = '请先选择具体门店和月份，再添加人员。'
    return
  }
  addEmployeeCandidates.value = []
  addEmployeeOpen.value = true
  addEmployeeLoading.value = true
  try {
    addEmployeeCandidates.value = await getSalaryAssignmentCandidates(
      page.effectiveStoreId.value,
      page.selectedMonth.value,
    )
  } catch (error) {
    addEmployeeError.value = userError(error, '可添加人员名单加载失败，请稍后重试。')
  } finally {
    addEmployeeLoading.value = false
  }
}

function closeAddEmployee() {
  if (addEmployeeSaving.value) return
  addEmployeeOpen.value = false
  addEmployeeError.value = ''
}

async function confirmAddEmployee(employeeId: string) {
  if (!canAddEmployee.value || addEmployeeSaving.value) return
  addEmployeeSaving.value = true
  addEmployeeError.value = ''
  try {
    const record = await assignSalaryEmployee({
      storeId: page.effectiveStoreId.value,
      month: page.selectedMonth.value,
      employeeId,
    })
    addEmployeeOpen.value = false
    page.statusFilter.value = 'ACTIVE'
    page.keyword.value = ''
    page.successMessage.value = `已将 ${record.employeeName} 添加到当月工资名单，岗位保持为${record.position || '原岗位'}`
    await reloadSalaryData(1)
    selectedRowKey.value = rowKey(record)
  } catch (error) {
    addEmployeeError.value = userError(error, '添加人员失败，请稍后重试。')
  } finally {
    addEmployeeSaving.value = false
  }
}

async function recordsInScope() {
  return getSalaryRecords({
    month: page.selectedMonth.value,
    brandId: page.effectiveBrandId.value,
    storeId: page.effectiveStoreId.value === 'all' ? undefined : page.effectiveStoreId.value,
  })
}

async function batchApprove() {
  actionError.value = ''
  const selected = selectedApprovalRecordIds.value
  if (!selected.size) {
    actionError.value = '请先选择需要审核的员工。'
    return
  }
  try {
    const records = (await recordsInScope()).filter((row) => selected.has(row.id) && ['SUBMITTED', 'PENDING_REVIEW'].includes(row.status || ''))
    if (!records.length) {
      actionError.value = '所选员工中没有待审核工资。'
      return
    }
    batchApprovalRecords.value = records
    batchApprovalOpen.value = true
  } catch (error) {
    actionError.value = userError(error, '批量审核失败。')
  }
}

function cancelBatchApproval() {
  if (batchApproving.value) return
  batchApprovalOpen.value = false
  batchApprovalRecords.value = []
}

async function confirmBatchApproval() {
  const records = [...batchApprovalRecords.value]
  if (!records.length || batchApproving.value) return
  batchApproving.value = true
  actionError.value = ''
  try {
    for (const record of records) await approveSalaryRecord(record.id)
    selectedApprovalRecordIds.value = new Set()
    page.successMessage.value = `已审核 ${records.length} 条工资记录`
    await reloadSalaryData(1)
  } catch (error) {
    actionError.value = userError(error, '批量审核失败。')
  } finally {
    batchApproving.value = false
    batchApprovalOpen.value = false
    batchApprovalRecords.value = []
  }
}

function rowKey(record: SalaryRecord) { return record.id || `employee:${record.employeeId || `${record.storeId}:${record.employeeName}`}` }
function selectRecord(record: SalaryRecord) {
  const nextRowKey = rowKey(record)
  if (nextRowKey === selectedRowKey.value) return
  requestSalaryDiscard(() => { selectedRowKey.value = nextRowKey })
}
function toggleRow(record: SalaryRecord, checked: boolean) {
  if (record.status === 'PENDING_GENERATION' && page.canEdit.value && record.employeeId) {
    const next = new Set(selectedGenerationEmployeeIds.value)
    if (checked) next.add(record.employeeId); else next.delete(record.employeeId)
    selectedGenerationEmployeeIds.value = next
    return
  }
  if (page.canReview.value && record.id && ['SUBMITTED', 'PENDING_REVIEW'].includes(record.status || '')) {
    const next = new Set(selectedApprovalRecordIds.value)
    if (checked) next.add(record.id); else next.delete(record.id)
    selectedApprovalRecordIds.value = next
  }
}
function toggleAll(checked: boolean) {
  const nextApprovalIds = new Set(selectedApprovalRecordIds.value)
  const nextGenerationIds = new Set(selectedGenerationEmployeeIds.value)
  for (const row of page.filteredRows.value) {
    if (page.canEdit.value && row.status === 'PENDING_GENERATION' && row.employeeId) {
      if (checked) nextGenerationIds.add(row.employeeId)
      else nextGenerationIds.delete(row.employeeId)
    } else if (page.canReview.value && row.id && ['SUBMITTED', 'PENDING_REVIEW'].includes(row.status || '')) {
      if (checked) nextApprovalIds.add(row.id)
      else nextApprovalIds.delete(row.id)
    }
  }
  selectedApprovalRecordIds.value = nextApprovalIds
  selectedGenerationEmployeeIds.value = nextGenerationIds
}

async function markPaid(record: SalaryRecord) {
  if (!page.canPay.value) return
  await workflow.doMarkPaid(record)
}

async function saveAttendance(record: SalaryRecord, attendanceDays: number, overtimeHours: number, normalHours: number) {
  if (!record.employeeId) {
    actionError.value = '员工档案编号缺失，无法保存考勤。'
    return
  }
  actionError.value = ''
  try {
    await saveSalaryAttendance({
      storeId: record.storeId,
      employeeId: record.employeeId,
      month: record.month,
      attendanceDays,
      overtimeHours,
      normalHours,
    })
    page.successMessage.value = isHourlySalaryRecord(record)
      ? `已保存 ${record.employeeName} 的计时工时：正常${normalHours}小时，加班${overtimeHours}小时`
      : `已保存 ${record.employeeName} 的考勤：${attendanceDays}天，正常工时${normalHours}小时，加班${overtimeHours}小时`
    await reloadSalaryData(page.page.value)
  } catch (error) {
    actionError.value = userError(error, '考勤保存失败。')
  }
}

async function saveDetails(record: SalaryRecord, attendanceDays: number, overtimeHours: number, normalHours: number, attendanceChanged: boolean, payload: SalaryRecordPayload) {
  if (!record.employeeId) {
    actionError.value = '员工档案编号缺失，无法保存工资明细。'
    return
  }
  actionError.value = ''
  try {
    if (attendanceChanged) {
      await saveSalaryAttendance({ storeId: record.storeId, employeeId: record.employeeId, month: record.month, attendanceDays, overtimeHours, normalHours })
    }
    await saveSalaryRecord(payload, record.id)
    page.successMessage.value = attendanceChanged
      ? `已保存 ${record.employeeName} 的考勤、工资与假期信息`
      : `已保存 ${record.employeeName} 的工资与假期信息，原始工时保持不变`
    await reloadSalaryData(page.page.value)
  } catch (error) {
    actionError.value = userError(error, '工资明细保存失败。')
  }
}
watch(page.filteredRows, (rows) => {
  if (!rows.some((row) => rowKey(row) === selectedRowKey.value)) selectedRowKey.value = rows[0] ? rowKey(rows[0]) : ''
}, { immediate: true })
watch([page.effectiveStoreId, page.selectedMonth, page.effectiveBrandId], () => {
  selectedApprovalRecordIds.value = new Set()
  selectedGenerationEmployeeIds.value = new Set()
  actionError.value = ''
  if (!addEmployeeSaving.value) addEmployeeOpen.value = false
  if (page.initializing.value) return
  void loadBusinessMetrics()
})
watch([page.loading, businessMetricsLoading], ([pageLoading, metricsLoading]) => {
  if (!pageLoading && !metricsLoading && page.pageData.value && !page.error.value && !businessMetricsError.value) {
    markFresh()
  }
})
watch(salaryDetailDirty, (dirty) => emit('dirtyChange', dirty), { immediate: true })
watch(() => [props.initialStoreId, props.initialMonth], applyInitialScope)

onMounted(async () => {
  document.addEventListener('pointerdown', closeFilterMenuOnOutsideClick)
  await page.loadStores()
  page.applyRouteDefaults()
  applyInitialScope()
  page.initializing.value = false
  if (initialScopeBlocked.value) return
  await reloadSalaryData(1)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeFilterMenuOnOutsideClick)
})

onBeforeRouteLeave(() => {
  if (!salaryDetailDirty.value) return true
  if (salaryDiscardOpen.value) return false
  return new Promise<boolean>((resolve) => {
    requestSalaryDiscard(
      () => resolve(true),
      () => resolve(false),
    )
  })
})
</script>

<template>
  <div class="salary-workbench" :class="{ embedded }">
    <header class="salary-page-head">
      <div class="title-block"><h1>{{ title }}</h1><span>{{ page.selectedMonth.value }} · {{ page.total.value }} 名员工</span></div>
      <div class="head-controls">
        <input v-model="salaryMonthModel" type="month" aria-label="月份" />
        <SearchableSingleSelect
          v-if="!page.isStoreManager.value"
          :model-value="salaryStoreModel"
          :options="salaryStoreOptions"
          :disabled="page.storesLoading.value || page.isOwnStoreScope.value"
          :loading="page.storesLoading.value"
          :empty-option-label="page.isOwnStoreScope.value ? undefined : '全部授权门店'"
          empty-value="all"
          placeholder="请选择门店"
          search-placeholder="搜索门店名称、编号或区域"
          aria-label="门店"
          @update:model-value="salaryStoreModel = String($event)"
        />
        <button
          v-if="page.canEdit.value"
          class="add-person-button"
          :disabled="!canAddEmployee"
          :title="!canAddEmployee ? '请先选择具体门店和月份' : '添加其他门店员工到本月工资名单'"
          @click="openAddEmployee"
        ><UserPlus :size="16" />添加人员</button>
        <button
          v-if="page.canEdit.value"
          class="primary-button"
          :disabled="workflow.previewLoading.value || !page.canGenerate.value"
          :title="page.effectiveStoreId.value === 'all' ? '预览全部授权门店本月可生成工资的员工' : ''"
          @click="previewGeneration()"
        ><Eye :size="16" />{{ page.effectiveStoreId.value === 'all' ? '全部授权门店全选并生成' : '全选并生成' }}</button>
        <button v-if="page.canExport.value" class="export-button" :disabled="!page.hasValidMonth.value || page.loading.value" @click="workflow.doExport()"><Download :size="16" />导出工资表</button>
      </div>
    </header>

    <div v-if="page.error.value" class="page-error" role="alert">
      <span>{{ page.error.value }}</span>
      <button type="button" :disabled="page.loading.value" @click="retrySalaryData">重试</button>
    </div>
    <div v-if="actionError" class="inline-error" role="status">{{ actionError }}</div>
    <div v-if="page.storesError.value && !page.isStoreManager.value" class="aux-warning" role="status">
      <span>门店列表暂时无法获取</span>
      <button type="button" @click="page.loadStores()">重试</button>
    </div>
    <div v-if="page.successMessage.value" class="success-box">{{ page.successMessage.value }}</div>

    <SalarySummary
      :employee-count="employeeCount" :employees-loading="page.employeesLoading.value"
      :gross-total="grossTotal" :work-hours-total="totalHours" :vacation-balance-total="vacationBalance"
    />

    <section class="business-metrics">
      <article><span>当月营业额</span><b>{{ revenue === null ? '--' : revenueMoney(revenue) }}</b><small><span>{{ metricReason('revenue') || '直接去除小数，不四舍五入' }}</span><button v-if="businessMetricsError" type="button" @click="loadBusinessMetrics">重试</button></small></article>
      <article><span>人工占比</span><b>{{ efficiencyRatio === null ? '--' : `${(efficiencyRatio * 100).toFixed(1)}%` }}</b><small><span>{{ metricReason('revenue') || `应发 ${money(grossTotal)} ÷ 营业额` }}</span><button v-if="businessMetricsError" type="button" @click="loadBusinessMetrics">重试</button></small></article>
      <article><span>每小时营业额</span><b>{{ revenuePerHour === null ? '--' : money(revenuePerHour) }}</b><small><span>{{ metricReason('hours') || `正常工时＋加班工时，兼职/实习折半${effectiveHours === null ? '' : `（${wholeNumber(effectiveHours)}小时）`}` }}</span><button v-if="businessMetricsError" type="button" @click="loadBusinessMetrics">重试</button></small></article>
      <article><span>人均月产值</span><b>{{ perCapitaOutput === null ? '--' : money(perCapitaOutput) }}</b><small><span>{{ metricReason('output') || '每小时营业额 × 26天 × 8小时' }}</span><button v-if="businessMetricsError" type="button" @click="loadBusinessMetrics">重试</button></small></article>
      <article><span>店铺基金</span><b>{{ storeFund === null ? '--' : money(storeFund) }}</b><small><span>{{ metricReason('fund') || '提成总池 − 当前实发提成' }}</span><button v-if="businessMetricsError" type="button" @click="loadBusinessMetrics">重试</button></small></article>
    </section>

    <details class="rule-bar">
      <summary><span><b>提成核对</b>已核对提成合计 {{ commissionTotal === null ? '--' : money(commissionTotal) }}</span><span>查看明细<ChevronDown :size="16" /></span></summary>
      <div>提成金额以当前工资记录为准；营业额和比例仅用于核对，不在前端重新计算工资。</div>
    </details>
    <details class="rule-bar">
      <summary><span><b>假期结转规则</b>当前范围假期余额 {{ vacationBalance.toFixed(1) }} 天</span><span>查看规则<ChevronDown :size="16" /></span></summary>
      <div>假期余额按已保存工资记录汇总；未生成工资的员工不写入金额或假期记录。</div>
    </details>

    <div class="table-tools">
      <SearchInput v-model="salaryKeywordModel" class="salary-search" placeholder="搜索姓名、工号或岗位" aria-label="搜索工资记录" />
      <div>
        <button
          v-if="page.canEdit.value"
          class="batch-button"
          :disabled="selectedGenerationEmployeeIds.size === 0 || workflow.previewLoading.value"
          @click="previewSelectedGeneration"
        ><Eye :size="16" />生成所选工资</button>
        <button v-if="page.canReview.value" class="batch-button" :disabled="selectedApprovalRecordIds.size === 0" @click="batchApprove"><Check :size="16" />批量审核</button>
        <div ref="filterMenuRoot" class="salary-filter">
          <button
            ref="filterButton"
            class="filter-button"
            type="button"
            aria-label="筛选"
            aria-haspopup="menu"
            :aria-expanded="filterMenuOpen"
            aria-controls="salary-status-filter-menu"
            @click="toggleFilterMenu"
            @keydown.down.stop.prevent="openFilterMenu()"
            @keydown.esc.stop.prevent="closeFilterMenu(true)"
          >
            <Filter :size="16" />
            <span>筛选</span>
            <span v-if="page.statusFilter.value !== 'ACTIVE'" class="filter-button__value">
              {{ activeSalaryStatusLabel }}
            </span>
            <ChevronDown :size="15" :class="{ 'is-open': filterMenuOpen }" />
          </button>
          <div
            v-if="filterMenuOpen"
            id="salary-status-filter-menu"
            class="salary-filter-menu"
            role="menu"
            aria-label="工资状态筛选"
            aria-orientation="vertical"
            @keydown="navigateFilterMenu"
          >
            <div class="salary-filter-menu__title">工资状态</div>
            <button
              v-for="option in STATUS_OPTIONS"
              :key="option.value"
              type="button"
              role="menuitemradio"
              :aria-checked="page.statusFilter.value === option.value"
              :class="{ active: page.statusFilter.value === option.value }"
              @click="selectSalaryStatus(option.value)"
            >
              <span>{{ option.label }}</span>
              <Check v-if="page.statusFilter.value === option.value" :size="16" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <section class="salary-workspace">
      <SalaryTable
        :rows="page.filteredRows.value" :total="page.total.value" :page="page.page.value"
        :total-pages="page.totalPages.value" :loading="page.loading.value"
        :selected-row-key="selectedRowKey"
        :approval-checked-ids="selectedApprovalRecordIds"
        :generation-checked-employee-ids="selectedGenerationEmployeeIds"
        :can-edit="page.canEdit.value" :can-review="page.canReview.value"
        :deleting-id="workflow.deletingId.value"
        @page-change="requestSalaryPage" @select="selectRecord" @delete="workflow.doDelete" @toggle-row="toggleRow" @toggle-all="toggleAll"
      />
      <SalaryDetailPanel
        :record="selectedRecord" :revenue="revenue ?? 0" :can-edit="page.canEdit.value" :can-review="page.canReview.value" :can-pay="page.canPay.value"
        :actioning-id="workflow.actioningId.value"
        @submit="workflow.doSubmit" @approve="workflow.doApprove" @reject="workflow.doReject" @mark-paid="markPaid"
        @preview="previewGeneration"
        @save-attendance="saveAttendance"
        @save-details="saveDetails"
        @dirty-change="salaryDetailDirty = $event"
      />
    </section>

    <SalaryGenerationDialog
      :show="workflow.showPreview.value" :preview-data="workflow.previewData.value"
      :preview-loading="workflow.previewLoading.value" :generating="workflow.generating.value"
      :can-generate="page.canGenerate.value"
      :selected-employee-ids="generationSelectionModel"
      :scope-label="generationScopeLabel"
      @update:selected-employee-ids="generationSelectionModel = $event"
      @close="workflow.showPreview.value = false" @generate="confirmGeneration"
    />

    <SalaryAddEmployeeDialog
      :show="addEmployeeOpen"
      :candidates="addEmployeeCandidates"
      :loading="addEmployeeLoading"
      :saving="addEmployeeSaving"
      :error="addEmployeeError"
      :target-store-name="page.selectedStoreName.value"
      :month="page.selectedMonth.value"
      @close="closeAddEmployee"
      @submit="confirmAddEmployee"
    />

    <ActionConfirmDialog
      :open="Boolean(workflow.actionConfirmation.value)"
      :title="workflow.actionConfirmation.value?.title || ''"
      :message="workflow.actionConfirmation.value?.message || ''"
      :confirm-label="workflow.actionConfirmation.value?.confirmLabel"
      :confirm-variant="workflow.actionConfirmation.value?.confirmVariant"
      :busy="workflow.actionConfirmationBusy.value"
      :model-value="workflow.actionNote.value"
      :note-label="workflow.actionConfirmation.value?.noteLabel"
      :note-placeholder="workflow.actionConfirmation.value?.notePlaceholder"
      @update:model-value="workflow.actionNote.value = $event"
      @cancel="workflow.cancelActionConfirmation"
      @confirm="workflow.confirmAction"
    />

    <ActionConfirmDialog
      :open="batchApprovalOpen"
      title="批量审核工资"
      :message="`确认审核通过所选 ${batchApprovalRecords.length} 名员工的工资？`"
      confirm-label="确认审核"
      :busy="batchApproving"
      @cancel="cancelBatchApproval"
      @confirm="confirmBatchApproval"
    />

    <ActionConfirmDialog
      :open="salaryDiscardOpen"
      title="放弃未保存的工资修改？"
      message="切换筛选、记录或离开页面后，当前尚未保存的工资修改将不会保留。"
      confirm-label="放弃修改"
      confirm-variant="danger"
      @cancel="keepSalaryEditing"
      @confirm="discardSalaryChanges"
    />
  </div>
</template>

<style scoped>
.salary-workbench { display: grid; gap: 12px; min-width: 0; width: 100%; color: #182424; font-size: 14px; }
.salary-page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.title-block h1 { margin: 0; color: #182424; font-size: 23px; line-height: 1.25; }.title-block span { display: block; margin-top: 5px; color: #6f817f; font-size: 13px; }
.head-controls { display: flex; align-items: center; justify-content: flex-end; gap: 10px; flex-wrap: wrap; }
.head-controls input,.head-controls select { height: 36px; min-width: 132px; padding: 0 11px; border: 1px solid #d8e4e2; border-radius: 5px; background: #fff; color: #314543; font-size: 14px; }
.head-controls :deep(.searchable-single-select) { min-width: 210px; }
.head-controls :deep(.searchable-single-select__control) { min-height: 36px; }
.head-controls :deep(.searchable-single-select__control input) { height: 34px; min-width: 0; }
.head-controls button,.table-tools button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 36px; padding: 0 15px; border-radius: 5px; font-size: 14px; font-weight: 600; cursor: pointer; }
.primary-button { width: auto; min-height: 36px; margin: 0; padding: 0 15px; border: 1px solid #276b65; border-radius: 5px; background: #276b65; color: #fff; }.export-button,.batch-button,.add-person-button { border: 1px solid #4f948e; background: #fff; color: #276b65; }.head-controls button:disabled,.table-tools button:disabled { opacity: .5; cursor: default; }
.business-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.business-metrics article { min-height: 92px; padding: 14px 18px; border: 1px solid #dfe8e6; border-radius: 6px; background: #fff; }.business-metrics > article > span { color: #526765; font-size: 14px; }.business-metrics b { display: block; margin-top: 8px; font-size: 25px; line-height: 1; font-variant-numeric: tabular-nums; }.business-metrics small { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 9px; color: #6f817f; font-size: 12px; }.business-metrics small span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.business-metrics small button,.aux-warning button,.page-error button { flex: none; padding: 0; border: 0; background: transparent; color: #27756e; font-size: 12px; font-weight: 600; cursor: pointer; }
.rule-bar { border: 1px solid #d9e7e5; border-radius: 5px; background: #f9fbfb; }.rule-bar summary { display: flex; align-items: center; justify-content: space-between; gap: 20px; min-height: 40px; padding: 0 14px; color: #526765; cursor: pointer; list-style: none; }.rule-bar summary::-webkit-details-marker { display: none; }.rule-bar summary > span { display: inline-flex; align-items: center; gap: 10px; }.rule-bar summary b { color: #276b65; font-size: 14px; }.rule-bar summary span:last-child { color: #27756e; font-size: 13px; }.rule-bar[open] summary svg { transform: rotate(180deg); }.rule-bar > div { padding: 10px 14px; border-top: 1px solid #e2ebe9; color: #526765; font-size: 13px; }
.table-tools { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 2px; }.table-tools > div { display: flex; gap: 8px; }.table-tools > .salary-search { width: 300px; flex: none; }
.salary-filter { position: relative; display: inline-flex; }
.filter-button { border: 1px solid #d8e4e2; background: #fff; color: #526765; }
.filter-button[aria-expanded='true'] { border-color: #4f948e; color: #276b65; box-shadow: 0 0 0 2px rgb(79 148 142 / 12%); }
.filter-button__value { padding: 2px 6px; border-radius: 999px; background: #eaf5f3; color: #276b65; font-size: 11px; }
.filter-button svg:last-child { transition: transform .16s ease; }
.filter-button svg:last-child.is-open { transform: rotate(180deg); }
.salary-filter-menu { position: absolute; z-index: 40; top: calc(100% + 7px); right: 0; display: grid; width: 180px; max-width: calc(100vw - 24px); max-height: min(320px, 48vh); overflow-y: auto; padding: 7px; border: 1px solid var(--ds-line, #d8e4e2); border-radius: 7px; background: #fff; box-shadow: 0 14px 28px rgb(32 59 56 / 16%); }
.salary-filter-menu__title { padding: 7px 9px 6px; color: #7a8d8a; font-size: 12px; font-weight: 700; }
.salary-filter-menu button { justify-content: space-between; width: 100%; min-height: 36px; padding: 0 9px; border: 0; border-radius: 5px; background: transparent; color: #405654; font-size: 13px; font-weight: 600; }
.salary-filter-menu button:hover,.salary-filter-menu button:focus-visible { background: #f1f7f6; color: #276b65; outline: none; }
.salary-filter-menu button.active { background: #eaf5f3; color: #276b65; }
.salary-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 18px; align-items: start; min-width: 0; }
.page-error,.aux-warning { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 10px 12px; border-radius: 4px; font-size: 13px; }.page-error { border: 1px solid #efc9c2; background: #fff5f3; color: #a93f31; }.aux-warning { border: 1px solid #eadfbd; background: #fffaf0; color: #7b6533; }.inline-error,.success-box { padding: 9px 12px; border-radius: 4px; font-size: 13px; }.inline-error { border-left: 3px solid #d8583f; background: #fff2ef; color: #b94736; }.success-box { border-left: 3px solid #276b65; background: #eef7f5; color: #245f59; }
@media (max-width: 1120px) { .salary-workspace { grid-template-columns: 1fr; }.business-metrics { grid-template-columns: repeat(2,minmax(0,1fr)); } }
</style>
