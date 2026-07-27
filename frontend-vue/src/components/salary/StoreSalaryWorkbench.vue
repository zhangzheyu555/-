<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { Check, ChevronDown, Download, Eye, RotateCcw, UserPlus } from 'lucide-vue-next'
import {
  approveSalaryRecord, assignSalaryEmployee, getSalaryAssignmentCandidates, getSalaryBusinessMetrics,
  saveSalaryAttendance, saveSalaryRecord,
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
  { value: '', label: '全部状态' }, { value: 'PENDING_GENERATION', label: '待生成' },
  { value: 'DRAFT', label: '草稿' }, { value: 'SUBMITTED', label: '待审核' },
  { value: 'APPROVED', label: '已审核' }, { value: 'REJECTED', label: '已驳回' },
  { value: 'PAID', label: '已发放' }, { value: 'LOCKED', label: '已锁定' },
]

const page = useSalaryPage()
const actionError = ref('')
const businessMetrics = ref<SalaryBusinessMetrics | null>(null)
const businessMetricsScopeKey = ref('')
const businessMetricsLoading = ref(false)
const businessMetricsError = ref('')
const selectedRowKey = ref('')
const checkedIds = ref(new Set<string>())
const batchApprovalRecords = ref<SalaryRecord[]>([])
const batchApprovalOpen = ref(false)
const batchApproving = ref(false)
const addEmployeeOpen = ref(false)
const addEmployeeCandidates = ref<SalaryAssignmentCandidate[]>([])
const addEmployeeLoading = ref(false)
const addEmployeeSaving = ref(false)
const addEmployeeError = ref('')
const addEmployeeRetryable = ref(false)
const initialScopeBlocked = ref(false)
const salaryDetailDirty = ref(false)
const detailSavingKey = ref('')
const detailError = ref('')
const salaryDiscardOpen = ref(false)
const batchApprovalError = ref('')
let pendingSalaryDiscardAction: (() => void) | null = null
let pendingSalaryDiscardCancel: (() => void) | null = null
let businessMetricsRequestController: AbortController | null = null
let addEmployeeRequestController: AbortController | null = null

const workflow = useSalaryWorkflow({
  selectedMonth: page.selectedMonth,
  selectedStoreId: page.effectiveStoreId,
  selectedBrandId: page.effectiveBrandId,
  hasValidMonth: page.hasValidMonth,
  canGenerate: page.canGenerate,
  canEdit: page.canEdit,
  pageError: actionError,
  successMessage: page.successMessage,
  isPreviewContextAllowed: (context) => page.canEdit.value
    && page.hasValidMonth.value
    && context.month === page.selectedMonth.value
    && !page.loading.value
    && !initialScopeBlocked.value
    && page.accessibleStores.value.some((store) => store.id === context.storeId),
  loadPage: async () => reloadSalaryData(),
  onDeleted: (record) => {
    if (selectedRowKey.value === rowKey(record)) selectedRowKey.value = ''
    const nextCheckedIds = new Set(checkedIds.value)
    nextCheckedIds.delete(record.id)
    checkedIds.value = nextCheckedIds
    removeVisibleRecord(record)
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
  && !initialScopeBlocked.value
  && !page.loading.value)
const salaryOperationBusy = computed(() => Boolean(
  detailSavingKey.value
  || workflow.actioningId.value
  || workflow.deletingId.value
  || workflow.previewLoading.value
  || workflow.generating.value
  || workflow.exporting.value
  || workflow.actionConfirmationBusy.value
  || batchApproving.value
  || addEmployeeSaving.value,
))
const scopeActionGuidance = computed(() => {
  if (!page.canEdit.value) return ''
  if (!page.hasValidMonth.value) return '请选择有效月份后再处理工资。'
  if (!page.effectiveStoreId.value || page.effectiveStoreId.value === 'all') {
    return '顶部添加人员和批量生成需先选择具体门店；也可在员工明细中按其所属门店进入生成预览。'
  }
  if (!page.isEffectiveStoreActive.value) return '当前门店已停用，不能继续添加人员、生成或修改工资。'
  return ''
})
const previewStoreName = computed(() => {
  const storeId = workflow.previewContext.value?.storeId
  return storeId ? page.storeMap.value.get(storeId)?.name || storeId : page.selectedStoreName.value
})
const selectedDetailSaving = computed(() => Boolean(
  selectedRecord.value && detailSavingKey.value === rowKey(selectedRecord.value),
))
const salaryMonthModel = computed({
  get: () => page.selectedMonth.value,
  set: (value: string) => requestSalaryDiscard(() => {
    clearInteractionFeedback()
    page.selectedMonth.value = value
  }),
})
const salaryStoreModel = computed({
  get: () => page.selectedStoreId.value,
  set: (value: string) => requestSalaryDiscard(() => {
    clearInteractionFeedback()
    page.selectedStoreId.value = value
  }),
})
const salaryStatusModel = computed({
  get: () => page.statusFilter.value,
  set: (value: string) => requestSalaryDiscard(() => {
    clearInteractionFeedback()
    page.statusFilter.value = value
  }),
})
const salaryKeywordModel = computed({
  get: () => page.keyword.value,
  set: (value: string) => requestSalaryDiscard(() => {
    clearInteractionFeedback()
    page.keyword.value = value
  }),
})

function clearInteractionFeedback() {
  actionError.value = ''
  detailError.value = ''
  page.successMessage.value = ''
}

function requestSalaryDiscard(action: () => void, cancel: () => void = () => {}) {
  if (salaryOperationBusy.value) {
    actionError.value = '工资操作正在处理中，请完成后再切换范围或记录。'
    cancel()
    return
  }
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
  detailError.value = ''
  action?.()
}

function clearSalaryFilters() {
  if (!page.hasActiveListFilters.value) return
  requestSalaryDiscard(() => {
    clearInteractionFeedback()
    checkedIds.value = new Set()
    void (async () => {
      await page.setListFiltersWithoutReload('', '')
      await loadSalaryPage(1)
    })()
  })
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
  await loadBusinessMetrics()
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

async function previewGeneration() {
  actionError.value = ''
  detailError.value = ''
  if (!page.effectiveStoreId.value || page.effectiveStoreId.value === 'all') {
    actionError.value = '请先选择具体门店，再生成本月工资。'
    return
  }
  if (!page.isEffectiveStoreActive.value) {
    actionError.value = '该门店已停用，不能生成工资。'
    return
  }
  await workflow.doPreview()
}

async function previewEmployeeGeneration(record: SalaryRecord) {
  actionError.value = ''
  detailError.value = ''
  const storeId = String(record.storeId || '').trim()
  if (!storeId) {
    detailError.value = '该员工缺少所属门店，无法生成工资预览。请先完善员工档案。'
    return
  }
  if (record.month !== page.selectedMonth.value) {
    detailError.value = '当前员工数据所属月份已经变化，请刷新后重试。'
    return
  }
  if (!page.accessibleStores.value.some((store) => store.id === storeId)) {
    detailError.value = '该员工所属门店已停用或不在当前工资权限范围内，无法生成工资。'
    return
  }
  await workflow.doPreview({
    storeId,
    month: page.selectedMonth.value,
  })
}

async function confirmGeneration() {
  await workflow.doGenerate()
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
  await loadAddEmployeeCandidates()
}

async function loadAddEmployeeCandidates() {
  if (!addEmployeeOpen.value || !canAddEmployee.value) return
  addEmployeeRequestController?.abort()
  const controller = new AbortController()
  const storeId = page.effectiveStoreId.value
  const month = page.selectedMonth.value
  addEmployeeRequestController = controller
  addEmployeeLoading.value = true
  addEmployeeRetryable.value = false
  addEmployeeError.value = ''
  try {
    const candidates = await getSalaryAssignmentCandidates(storeId, month, controller.signal)
    if (
      controller.signal.aborted
      || !addEmployeeOpen.value
      || page.effectiveStoreId.value !== storeId
      || page.selectedMonth.value !== month
    ) return
    addEmployeeCandidates.value = candidates
  } catch (error) {
    if (controller.signal.aborted || (error instanceof ApiError && error.code === 'REQUEST_CANCELLED')) return
    addEmployeeError.value = userError(error, '可添加人员名单加载失败，请稍后重试。')
    addEmployeeRetryable.value = true
  } finally {
    if (addEmployeeRequestController === controller) {
      addEmployeeRequestController = null
      addEmployeeLoading.value = false
    }
  }
}

function closeAddEmployee() {
  if (addEmployeeSaving.value) return
  addEmployeeRequestController?.abort()
  addEmployeeRequestController = null
  addEmployeeLoading.value = false
  addEmployeeOpen.value = false
  addEmployeeError.value = ''
  addEmployeeRetryable.value = false
}

async function confirmAddEmployee(employeeId: string) {
  if (!canAddEmployee.value || addEmployeeSaving.value) return
  addEmployeeSaving.value = true
  addEmployeeError.value = ''
  addEmployeeRetryable.value = false
  try {
    const record = await assignSalaryEmployee({
      storeId: page.effectiveStoreId.value,
      month: page.selectedMonth.value,
      employeeId,
    })
    addEmployeeOpen.value = false
    await page.setListFiltersWithoutReload('', '')
    page.successMessage.value = `已将 ${record.employeeName} 添加到当月工资名单，岗位保持为${record.position || '原岗位'}`
    const loaded = await reloadSalaryData(1)
    selectedRowKey.value = rowKey(record)
    if (!loaded) {
      actionError.value = '人员已添加，但最新工资名单刷新失败，请点击重试。'
    }
  } catch (error) {
    addEmployeeError.value = userError(error, '添加人员失败，请稍后重试。')
  } finally {
    addEmployeeSaving.value = false
  }
}

function isReviewableRecord(record: SalaryRecord) {
  return Boolean(record.id) && ['SUBMITTED', 'PENDING_REVIEW'].includes(record.status || '')
}

async function batchApprove() {
  actionError.value = ''
  batchApprovalError.value = ''
  const selected = checkedIds.value
  if (!selected.size) {
    actionError.value = '请先选择需要审核的员工。'
    return
  }
  const records = page.filteredRows.value.filter((row) => selected.has(row.id) && isReviewableRecord(row))
  if (!records.length) {
    checkedIds.value = new Set()
    actionError.value = '所选工资已不处于待审核状态，请刷新后重新选择。'
    return
  }
  batchApprovalRecords.value = records
  batchApprovalOpen.value = true
}

function cancelBatchApproval() {
  if (batchApproving.value) return
  batchApprovalOpen.value = false
  batchApprovalRecords.value = []
  batchApprovalError.value = ''
}

async function confirmBatchApproval() {
  const records = [...batchApprovalRecords.value]
  if (!records.length || batchApproving.value) return
  batchApproving.value = true
  actionError.value = ''
  batchApprovalError.value = ''
  const approvedIds = new Set<string>()
  const failures: Array<{ record: SalaryRecord; error: unknown }> = []
  for (const record of records) {
    try {
      await approveSalaryRecord(record.id)
      approvedIds.add(record.id)
    } catch (error) {
      failures.push({ record, error })
    }
  }
  batchApproving.value = false

  if (!approvedIds.size) {
    batchApprovalError.value = userError(failures[0]?.error, '批量审核失败，请稍后重试。')
    return
  }

  const failedIds = new Set(failures.map(({ record }) => record.id))
  checkedIds.value = failedIds
  batchApprovalOpen.value = false
  batchApprovalRecords.value = []
  batchApprovalError.value = ''
  page.successMessage.value = `已审核 ${approvedIds.size} 条工资记录`
  if (failures.length) {
    actionError.value = `${failures.length} 条工资审核失败，失败记录已保留勾选，可重试。${userError(
      failures[0].error,
      '首条失败原因暂时无法获取。',
    )}`
  }
  const loaded = await reloadSalaryData(1)
  if (!loaded && !actionError.value) {
    actionError.value = '批量审核已完成，但最新数据刷新失败，请点击重试。'
  }
}

function rowKey(record: SalaryRecord) { return record.id || `employee:${record.employeeId || `${record.storeId}:${record.employeeName}`}` }
function selectRecord(record: SalaryRecord) {
  const nextRowKey = rowKey(record)
  if (nextRowKey === selectedRowKey.value) return
  requestSalaryDiscard(() => {
    detailError.value = ''
    selectedRowKey.value = nextRowKey
  })
}
function toggleRow(record: SalaryRecord, checked: boolean) {
  if (!isReviewableRecord(record) || !page.canReview.value) return
  const next = new Set(checkedIds.value)
  if (checked) next.add(record.id); else next.delete(record.id)
  checkedIds.value = next
}
function toggleAll(checked: boolean) {
  const next = new Set(checkedIds.value)
  for (const row of page.filteredRows.value.filter(isReviewableRecord)) {
    checked ? next.add(row.id) : next.delete(row.id)
  }
  checkedIds.value = next
}

async function markPaid(record: SalaryRecord) {
  if (!page.canPay.value) return
  await workflow.doMarkPaid(record)
}

function lockRecord(record: SalaryRecord) {
  if (!page.canEdit.value || record.status !== 'PAID') return
  workflow.doLock(record)
}

async function saveAttendance(record: SalaryRecord, attendanceDays: number, overtimeHours: number, normalHours: number) {
  const key = rowKey(record)
  if (detailSavingKey.value) return
  if (!record.employeeId) {
    detailError.value = '员工档案编号缺失，无法保存考勤。'
    return
  }
  detailSavingKey.value = key
  detailError.value = ''
  actionError.value = ''
  try {
    const attendance = await saveSalaryAttendance({
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
    const loaded = await reloadSalaryData(page.page.value)
    if (!loaded) {
      replaceVisibleRecord({
        ...record,
        attendance: isHourlySalaryRecord(record) ? `${attendance.normalHours}小时` : `${attendance.attendanceDays}天`,
        normalHours: attendance.normalHours,
        otHours: attendance.overtimeHours,
        workHours: attendance.totalHours,
      })
      detailError.value = '工时已保存，但最新工资数据刷新失败，请点击页面重试。'
    }
  } catch (error) {
    detailError.value = userError(error, '考勤保存失败，请检查后重试。')
  } finally {
    if (detailSavingKey.value === key) detailSavingKey.value = ''
  }
}

async function saveDetails(record: SalaryRecord, attendanceDays: number, overtimeHours: number, normalHours: number, attendanceChanged: boolean, payload: SalaryRecordPayload) {
  const key = rowKey(record)
  if (detailSavingKey.value) return
  if (!record.employeeId) {
    detailError.value = '员工档案编号缺失，无法保存工资明细。'
    return
  }
  detailSavingKey.value = key
  detailError.value = ''
  actionError.value = ''
  let attendanceSaved = false
  try {
    if (attendanceChanged) {
      await saveSalaryAttendance({ storeId: record.storeId, employeeId: record.employeeId, month: record.month, attendanceDays, overtimeHours, normalHours })
      attendanceSaved = true
    }
    const savedRecord = await saveSalaryRecord(payload, record.id)
    page.successMessage.value = attendanceChanged
      ? `已保存 ${record.employeeName} 的考勤、工资与假期信息`
      : `已保存 ${record.employeeName} 的工资与假期信息，原始工时保持不变`
    const loaded = await reloadSalaryData(page.page.value)
    if (!loaded) {
      replaceVisibleRecord(savedRecord)
      detailError.value = '工资明细已保存，但最新汇总刷新失败，请点击页面重试。'
    }
  } catch (error) {
    detailError.value = attendanceSaved
      ? `考勤已保存，但工资明细保存失败：${userError(error, '请稍后重试。')}`
      : userError(error, '工资明细保存失败，请检查后重试。')
  } finally {
    if (detailSavingKey.value === key) detailSavingKey.value = ''
  }
}

function replaceVisibleRecord(record: SalaryRecord) {
  const data = page.pageData.value
  if (!data) return
  const key = rowKey(record)
  const nextRows = page.rows.value.map((row) => rowKey(row) === key ? record : row)
  page.pageData.value = {
    ...data,
    content: nextRows,
    rows: data.rows ? nextRows : data.rows,
  }
}

function removeVisibleRecord(record: SalaryRecord) {
  const data = page.pageData.value
  if (!data) return
  const key = rowKey(record)
  const nextRows = page.rows.value.filter((row) => rowKey(row) !== key)
  const nextTotal = Math.max(0, page.total.value - 1)
  page.pageData.value = {
    ...data,
    content: nextRows,
    rows: data.rows ? nextRows : data.rows,
    total: nextTotal,
    totalElements: nextTotal,
  }
}
watch(page.filteredRows, (rows) => {
  if (!rows.some((row) => rowKey(row) === selectedRowKey.value)) selectedRowKey.value = rows[0] ? rowKey(rows[0]) : ''
  const visibleReviewableIds = new Set(rows.filter(isReviewableRecord).map((row) => row.id))
  const nextCheckedIds = new Set([...checkedIds.value].filter((id) => visibleReviewableIds.has(id)))
  if (nextCheckedIds.size !== checkedIds.value.size) checkedIds.value = nextCheckedIds
}, { immediate: true })
watch([page.effectiveStoreId, page.selectedMonth, page.effectiveBrandId], () => {
  checkedIds.value = new Set()
  actionError.value = ''
  detailError.value = ''
  workflow.closePreview()
  workflow.cancelActionConfirmation()
  cancelBatchApproval()
  if (!addEmployeeSaving.value) closeAddEmployee()
  if (page.initializing.value) return
  void loadBusinessMetrics()
})
watch([page.statusFilter, page.keyword, page.page], () => {
  checkedIds.value = new Set()
  if (!batchApproving.value) cancelBatchApproval()
})
watch([page.loading, businessMetricsLoading], ([pageLoading, metricsLoading]) => {
  if (!pageLoading && !metricsLoading && page.pageData.value && !page.error.value && !businessMetricsError.value) {
    markFresh()
  }
})
watch(salaryDetailDirty, (dirty) => emit('dirtyChange', dirty), { immediate: true })
watch(() => [props.initialStoreId, props.initialMonth], applyInitialScope)

onMounted(async () => {
  await page.loadStores()
  page.applyRouteDefaults()
  applyInitialScope()
  page.initializing.value = false
  if (initialScopeBlocked.value) return
  await reloadSalaryData(1)
})

onBeforeRouteLeave(() => {
  if (salaryOperationBusy.value) {
    actionError.value = '工资操作正在处理中，请完成后再离开页面。'
    return false
  }
  if (!salaryDetailDirty.value) return true
  if (salaryDiscardOpen.value) return false
  return new Promise<boolean>((resolve) => {
    requestSalaryDiscard(
      () => resolve(true),
      () => resolve(false),
    )
  })
})

onBeforeUnmount(() => {
  businessMetricsRequestController?.abort()
  businessMetricsRequestController = null
  addEmployeeRequestController?.abort()
  addEmployeeRequestController = null
})
</script>

<template>
  <div class="salary-workbench" :class="{ embedded }">
    <header class="salary-page-head">
      <div class="title-block">
        <h1>{{ title }}</h1>
        <span>
          {{ page.selectedMonth.value }} · 共 {{ employeeCount }} 名员工
          <template v-if="page.hasActiveListFilters.value"> · 当前显示 {{ page.filteredEmployeeCount.value }} 名</template>
        </span>
      </div>
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
        <select v-model="salaryStatusModel" aria-label="工资状态"><option v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select>
        <button
          v-if="page.canEdit.value"
          class="add-person-button"
          :disabled="!canAddEmployee || salaryOperationBusy"
          :title="!canAddEmployee ? '请先选择具体门店和月份' : '添加其他门店员工到本月工资名单'"
          @click="openAddEmployee"
        ><UserPlus :size="16" />添加人员</button>
        <button
          v-if="page.canEdit.value"
          class="primary-button"
          :disabled="workflow.previewLoading.value || salaryOperationBusy || !page.canGenerate.value"
          :title="!page.effectiveStoreId.value || page.effectiveStoreId.value === 'all' ? '请先选择具体门店' : ''"
          @click="previewGeneration"
        ><Eye :size="16" />{{ workflow.previewLoading.value ? '正在准备预览…' : '生成本月工资' }}</button>
        <button v-if="page.canExport.value" class="export-button" :disabled="!page.hasValidMonth.value || page.loading.value || workflow.exporting.value" @click="workflow.doExport()"><Download :size="16" />{{ workflow.exporting.value ? '正在导出…' : '导出工资表' }}</button>
      </div>
    </header>
    <div v-if="scopeActionGuidance" class="scope-guidance" role="note">{{ scopeActionGuidance }}</div>

    <div v-if="page.error.value" class="page-error" role="alert">
      <span>{{ page.error.value }}</span>
      <button type="button" :disabled="page.loading.value" @click="retrySalaryData">重试</button>
    </div>
    <div v-if="actionError" class="inline-error" role="alert">{{ actionError }}</div>
    <div v-if="page.storesError.value && !page.isStoreManager.value" class="aux-warning" role="status">
      <span>门店列表暂时无法获取</span>
      <button type="button" @click="page.loadStores()">重试</button>
    </div>
    <div v-if="page.successMessage.value" class="success-box" role="status">{{ page.successMessage.value }}</div>

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
        <button v-if="page.canReview.value" class="batch-button" :disabled="checkedIds.size === 0 || salaryOperationBusy" @click="batchApprove"><Check :size="16" />批量审核<span v-if="checkedIds.size">（{{ checkedIds.size }}）</span></button>
        <button class="filter-button" :disabled="!page.hasActiveListFilters.value || salaryOperationBusy" title="清除工资状态和搜索关键词" @click="clearSalaryFilters"><RotateCcw :size="16" />清除筛选</button>
      </div>
    </div>

    <section class="salary-workspace">
      <SalaryTable
        :rows="page.filteredRows.value" :total="page.total.value" :page="page.page.value"
        :total-pages="page.totalPages.value" :loading="page.loading.value"
        :selected-row-key="selectedRowKey" :checked-ids="checkedIds"
        :can-edit="page.canEdit.value" :can-review="page.canReview.value" :deleting-id="workflow.deletingId.value"
        @page-change="requestSalaryPage" @select="selectRecord" @delete="workflow.doDelete" @toggle-row="toggleRow" @toggle-all="toggleAll"
      />
      <SalaryDetailPanel
        :record="selectedRecord" :revenue="revenue ?? 0" :can-edit="page.canEdit.value" :can-review="page.canReview.value" :can-pay="page.canPay.value"
        :actioning-id="workflow.actioningId.value" :saving="selectedDetailSaving" :operation-error="detailError"
        @submit="workflow.doSubmit" @approve="workflow.doApprove" @reject="workflow.doReject" @mark-paid="markPaid" @lock="lockRecord"
        @preview="previewEmployeeGeneration"
        @save-attendance="saveAttendance"
        @save-details="saveDetails"
        @dirty-change="salaryDetailDirty = $event"
      />
    </section>

    <SalaryGenerationDialog
      :show="workflow.showPreview.value" :preview-data="workflow.previewData.value"
      :preview-loading="workflow.previewLoading.value" :preview-error="workflow.previewError.value"
      :generating="workflow.generating.value" :can-generate="workflow.canConfirmGeneration.value"
      :store-name="previewStoreName" :month="workflow.previewContext.value?.month || page.selectedMonth.value"
      @close="workflow.closePreview" @retry="workflow.doPreview" @generate="confirmGeneration"
    />

    <SalaryAddEmployeeDialog
      :show="addEmployeeOpen"
      :candidates="addEmployeeCandidates"
      :loading="addEmployeeLoading"
      :saving="addEmployeeSaving"
      :error="addEmployeeError"
      :retryable="addEmployeeRetryable"
      :target-store-name="page.selectedStoreName.value"
      :month="page.selectedMonth.value"
      @close="closeAddEmployee"
      @retry="loadAddEmployeeCandidates"
      @submit="confirmAddEmployee"
    />

    <ActionConfirmDialog
      :open="Boolean(workflow.actionConfirmation.value)"
      :title="workflow.actionConfirmation.value?.title || ''"
      :message="workflow.actionConfirmation.value?.message || ''"
      :confirm-label="workflow.actionConfirmation.value?.confirmLabel"
      :confirm-variant="workflow.actionConfirmation.value?.confirmVariant"
      :busy="workflow.actionConfirmationBusy.value"
      :error="workflow.actionConfirmationError.value"
      :model-value="workflow.actionNote.value"
      :note-label="workflow.actionConfirmation.value?.noteLabel"
      :note-placeholder="workflow.actionConfirmation.value?.notePlaceholder"
      :note-required="workflow.actionConfirmation.value?.kind === 'reject'"
      :note-max-length="workflow.actionConfirmation.value?.kind === 'reject' ? 255 : 0"
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
      :error="batchApprovalError"
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
.table-tools { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 2px; }.table-tools > div { display: flex; gap: 8px; }.table-tools > .salary-search { width: 300px; flex: none; }.filter-button { border: 1px solid #d8e4e2; background: #fff; color: #526765; }
.salary-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 18px; align-items: start; min-width: 0; }
.scope-guidance { padding: 9px 12px; border: 1px solid #d9e7e5; border-radius: 5px; background: #f7fbfa; color: #526765; font-size: 13px; line-height: 1.55; }
.page-error,.aux-warning { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 10px 12px; border-radius: 4px; font-size: 13px; }.page-error { border: 1px solid #efc9c2; background: #fff5f3; color: #a93f31; }.aux-warning { border: 1px solid #eadfbd; background: #fffaf0; color: #7b6533; }.inline-error,.success-box { padding: 9px 12px; border-radius: 4px; font-size: 13px; }.inline-error { border-left: 3px solid #d8583f; background: #fff2ef; color: #b94736; }.success-box { border-left: 3px solid #276b65; background: #eef7f5; color: #245f59; }
@media (max-width: 1120px) { .salary-workspace { grid-template-columns: 1fr; }.business-metrics { grid-template-columns: repeat(2,minmax(0,1fr)); } }

@media (max-width: 680px) {
  .salary-page-head,
  .table-tools,
  .page-error,
  .aux-warning {
    align-items: stretch;
    flex-direction: column;
  }

  .head-controls,
  .table-tools > div {
    display: grid;
    width: 100%;
    grid-template-columns: minmax(0, 1fr);
  }

  .head-controls > *,
  .head-controls input,
  .head-controls select,
  .head-controls :deep(.searchable-single-select),
  .head-controls button,
  .table-tools > *,
  .table-tools > .salary-search,
  .table-tools button {
    width: 100%;
    min-width: 0;
  }

  .head-controls input,
  .head-controls select,
  .head-controls :deep(.searchable-single-select__control),
  .head-controls button,
  .table-tools button {
    min-height: 44px;
  }

  .business-metrics {
    grid-template-columns: minmax(0, 1fr);
  }

  .rule-bar summary {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
    padding-block: 10px;
  }

  .page-error button,
  .aux-warning button {
    width: 100%;
    min-height: 40px;
    text-align: left;
  }
}
</style>
