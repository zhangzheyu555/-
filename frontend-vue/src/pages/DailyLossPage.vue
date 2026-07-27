<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  CheckCircle2,
  ChevronDown,
  Download,
  FileUp,
  Image,
  ImageOff,
  PackageMinus,
  Plus,
  Send,
  Trash2,
  X,
} from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import BusinessScopeBar from '../components/common/BusinessScopeBar.vue'
import SearchableSingleSelect from '../components/common/SearchableSingleSelect.vue'
import UiButton from '../components/ui/UiButton.vue'
import UnsavedChangesDialog from '../components/ui/UnsavedChangesDialog.vue'
import {
  downloadMonthlyDailyLossExcel,
  fetchDailyLossAttachment,
  getDailyLossItems,
  getDailyLossMonthlyArchive,
  getDailyLossReports,
  reviewDailyLossReport,
  saveDailyLossReport,
  submitDailyLossReport,
  uploadDailyLossReportAttachments,
  type DailyLossAttachment,
  type DailyLossItem,
  type DailyLossMonthlyArchive,
  type DailyLossReport,
} from '../api/dailyLoss'
import { getStores, type StoreInfo } from '../api/operations'
import { useBusinessScope } from '../composables/useBusinessScope'
import { useForegroundReload } from '../composables/useForegroundReload'
import { PERMISSIONS } from '../permissions/permissions'
import { isBossRole, normalizeRoleCode } from '../permissions/roles'
import { useAuthStore } from '../stores/auth'
import { normalizeBrandName } from '../utils/brand'

interface LossLineForm {
  itemConfigId: string
  quantity: string
  reason: string
}

interface CategoryTab {
  code: string
  name: string
  count: number
}

const QUICK_REASONS = ['变质', '打翻', '切配损耗', '过期', '其他']
const ALL_CATEGORY = 'ALL'
const RECENT_CATEGORY = 'RECENT'

const auth = useAuthStore()
const scope = useBusinessScope()
const route = useRoute()
const stores = ref<StoreInfo[]>([])
const items = ref<DailyLossItem[]>([])
const reports = ref<DailyLossReport[]>([])
const monthlyArchive = ref<DailyLossMonthlyArchive | null>(null)
const selectedStoreId = ref('')
const selectedMonth = ref(currentMonth())
const loading = ref(true)
const loadingData = ref(false)
const submitting = ref(false)
const exporting = ref(false)
const pageError = ref('')
const actionMessage = ref('')
const uploadProgress = ref(0)
const selectedFiles = ref<File[]>([])
const selectedFilePreviews = ref<Record<string, string>>({})
const imageUrls = ref<Record<string, string>>({})
const imageStates = ref<Record<string, 'loading' | 'ready' | 'error'>>({})
const preview = ref<{ url: string; alt: string } | null>(null)
const detailReport = ref<DailyLossReport | null>(null)
const approvalNotes = ref<Record<string, string>>({})
const approvingId = ref('')
const lines = ref<LossLineForm[]>([emptyLine()])
const supplierCompensation = ref('0')
const formRef = ref<HTMLElement | null>(null)
const recordsRef = ref<HTMLElement | null>(null)
const recordFilter = ref<'ALL' | 'NOT_REPORTED' | 'SUBMITTED' | 'REVIEWED'>('ALL')
const pickerSelectRef = ref<{ focus: () => void } | null>(null)
const pickerOpen = ref(false)
const pickerLineIndex = ref(0)
const pickerCategory = ref(ALL_CATEGORY)
const recentItemIds = ref<number[]>([])
const pendingStoreId = ref<string | null>(null)
const scopeDiscardOpen = ref(false)
const loadedDailyLossQueryKey = ref('')
let dailyLossLoadSerial = 0
const photoControllers = new Map<string, AbortController>()

const hasPendingLossChanges = computed(() => Boolean(
  selectedFiles.value.length
  || Number(supplierCompensation.value) > 0
  || lines.value.some((line) => line.itemConfigId || line.quantity || line.reason.trim())
  || Object.values(approvalNotes.value).some((note) => String(note || '').trim()),
))

const { markFresh } = useForegroundReload(async () => {
  const loaded = await loadDailyLossData()
  if (!loaded) throw new Error(pageError.value || '报损数据读取失败')
}, {
  canReload: () => !loading.value
    && !loadingData.value
    && !submitting.value
    && !approvingId.value
    && !hasPendingLossChanges.value
    && !scopeDiscardOpen.value,
})

const canSubmit = computed(() => auth.hasPermission(PERMISSIONS.DAILY_LOSS_CREATE)
  && (isBossRole(auth.role) || normalizeRoleCode(auth.role) === 'STORE_MANAGER'))
const canReview = computed(() => auth.hasPermission(PERMISSIONS.DAILY_LOSS_REVIEW)
  && (isBossRole(auth.role) || normalizeRoleCode(auth.role) === 'SUPERVISOR'))
const hasGlobalDailyLossStoreScope = computed(() => (
  isBossRole(auth.role) || normalizeRoleCode(auth.role) === 'SUPERVISOR'
))
const storeScope = computed(() => normalizeRoleCode(auth.role) === 'FINANCE'
  ? (auth.dataScope('FINANCE') || auth.dataScope('STORE'))
  : (auth.dataScope('STORE') || auth.dataScope('WAREHOUSE')))
const accessibleStores = computed(() => {
  let scopedStores: StoreInfo[]
  if (scope.isStoreManager.value) {
    scopedStores = stores.value.filter((store) => store.id === scope.boundStoreId.value)
  } else if (hasGlobalDailyLossStoreScope.value || storeScope.value?.mode === 'ALL') {
    scopedStores = stores.value
  } else {
    const storeIds = storeScope.value?.storeIds || []
    scopedStores = storeIds.length
      ? stores.value.filter((store) => storeIds.includes(store.id))
      : stores.value
  }
  return scopedStores.filter((store) => normalizeBrandName(store.brandName) === '茹菓')
})
const selectableStores = computed(() => accessibleStores.value)
const selectableStoreOptions = computed(() => selectableStores.value.map((store) => ({
  value: store.id,
  label: `${normalizeBrandName(store.brandName)} · ${store.name || store.id}`,
  description: [store.code, store.area || store.regionCode, store.status].filter(Boolean).join(' · '),
  searchText: [store.name, store.code, store.area, store.regionCode, store.status, store.brandName].filter(Boolean).join(' '),
})))
const effectiveStoreId = computed(() => scope.scopedStoreId(selectedStoreId.value))
const canSelectAllStores = computed(() => !scope.isStoreManager.value
  && (hasGlobalDailyLossStoreScope.value || storeScope.value?.mode === 'ALL'))
const currentDailyLossQueryKey = computed(() => dailyLossQueryKey(
  effectiveStoreId.value,
  selectedMonth.value,
  canSelectAllStores.value,
))
const dailyLossScopeReady = computed(() => loadedDailyLossQueryKey.value === currentDailyLossQueryKey.value)
const scopedReports = computed(() => dailyLossScopeReady.value ? reports.value : [])
const scopedMonthlyArchive = computed(() => dailyLossScopeReady.value ? monthlyArchive.value : null)
const canExport = computed(() => auth.hasPermission(PERMISSIONS.DAILY_LOSS_EXPORT)
  && (isBossRole(auth.role) || normalizeRoleCode(auth.role) === 'SUPERVISOR'))
const notReportedCount = computed(() => scopedReports.value.filter((report) => statusKey(report) === 'NOT_REPORTED').length)
const pendingCount = computed(() => scopedReports.value.filter((report) => statusKey(report) === 'SUBMITTED').length)
const reviewedCount = computed(() => scopedReports.value.filter((report) => ['REVIEWED', 'APPROVED'].includes(statusKey(report))).length)
const displayedReports = computed(() => scopedReports.value.filter((report) => {
  if (recordFilter.value === 'ALL') return true
  if (recordFilter.value === 'REVIEWED') return ['REVIEWED', 'APPROVED'].includes(statusKey(report))
  return statusKey(report) === recordFilter.value
}))
const reportDayGroups = computed(() => {
  const groups = new Map<string, DailyLossReport[]>()
  for (const report of displayedReports.value) {
    const rows = groups.get(report.lossDate) || []
    rows.push(report)
    groups.set(report.lossDate, rows)
  }
  return Array.from(groups, ([date, rows]) => ({
    date,
    rows,
    notReportedCount: rows.filter((report) => statusKey(report) === 'NOT_REPORTED').length,
  }))
})
const todayReport = computed(() => scopedReports.value.find((report) => report.lossDate === localDate()))
const itemsById = computed(() => new Map(items.value.map((item) => [Number(item.id), item])))
const expectedLossAmount = computed(() => lines.value.reduce((total, line) => {
  const item = selectedItem(line)
  const quantity = Number(line.quantity)
  const factor = Number(item?.quantityPerPricingUnit || 1)
  const price = Number(item?.unitPrice || 0)
  return total + (Number.isFinite(quantity) && quantity > 0 && factor > 0 ? quantity / factor * price : 0)
}, 0))
const expectedSupplierCompensation = computed(() => Math.max(0, Number(supplierCompensation.value) || 0))
const expectedStoreBorneAmount = computed(() => Math.max(0, expectedLossAmount.value - expectedSupplierCompensation.value))
const categoryTabs = computed<CategoryTab[]>(() => {
  const grouped = new Map<string, CategoryTab>()
  for (const item of items.value) {
    const code = itemCategoryCode(item)
    const name = itemCategoryName(item)
    const current = grouped.get(code)
    if (current) current.count += 1
    else grouped.set(code, { code, name, count: 1 })
  }
  return [
    { code: ALL_CATEGORY, name: '全部', count: items.value.length },
    { code: RECENT_CATEGORY, name: '最近使用', count: recentItemIds.value.filter((id) => itemsById.value.has(id)).length },
    ...Array.from(grouped.values()).sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN')),
  ]
})
const pickerItems = computed(() => {
  let rows = items.value
  if (pickerCategory.value === RECENT_CATEGORY) {
    const recent = recentItemIds.value
      .map((id) => itemsById.value.get(id))
      .filter((item): item is DailyLossItem => Boolean(item))
    rows = recent
  } else if (pickerCategory.value !== ALL_CATEGORY) {
    rows = rows.filter((item) => itemCategoryCode(item) === pickerCategory.value)
  }
  return rows
})
const pickerItemOptions = computed(() => pickerItems.value.map((item) => ({
  value: String(item.id),
  label: itemLabel(item),
  description: [
    item.itemCode || item.code,
    itemCategoryName(item),
    itemUnit(item),
    itemPriceLabel(item),
  ].filter(Boolean).join(' · '),
  searchText: [
    itemLabel(item),
    item.itemCode || item.code,
    itemCategoryName(item),
    itemUnit(item),
  ].filter(Boolean).join(' '),
  disabled: isPickerItemSelected(item),
})))

onMounted(() => {
  document.addEventListener('keydown', closeLayersOnEsc)
  void initialize()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', closeLayersOnEsc)
  releaseRemoteImages()
  releaseSelectedPreviews()
})

watch([effectiveStoreId, selectedMonth], () => {
  detailReport.value = null
  if (!loading.value) void loadDailyLossData()
})

watch(categoryTabs, (tabs) => {
  if (!tabs.some((tab) => tab.code === pickerCategory.value)) {
    pickerCategory.value = ALL_CATEGORY
  }
})

function emptyLine(): LossLineForm {
  return { itemConfigId: '', quantity: '', reason: '' }
}

async function initialize() {
  loading.value = true
  pageError.value = ''
  try {
    if (scope.configurationError.value) {
      pageError.value = scope.configurationError.value
      return
    }
    stores.value = await getStores()
    selectedStoreId.value = initialStoreId()
    const requestedMonth = queryValue('month')
    if (/^\d{4}-(0[1-9]|1[0-2])$/.test(requestedMonth)) selectedMonth.value = requestedMonth
    await loadDailyLossData()
  } catch (error) {
    pageError.value = readableError(error, '每日报损暂时无法读取，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function loadDailyLossData() {
  const loadSerial = ++dailyLossLoadSerial
  const requestQueryKey = currentDailyLossQueryKey.value
  if (!effectiveStoreId.value && !canSelectAllStores.value) {
    items.value = []
    reports.value = []
    monthlyArchive.value = null
    loadedDailyLossQueryKey.value = requestQueryKey
    markFresh()
    return true
  }
  loadingData.value = true
  pageError.value = ''
  try {
    const [itemRows, reportRows, archiveRow] = await Promise.all([
      getDailyLossItems(),
      getDailyLossReports({ storeId: effectiveStoreId.value, month: selectedMonth.value }),
      canReview.value ? getDailyLossMonthlyArchive(selectedMonth.value) : Promise.resolve(null),
    ])
    if (loadSerial !== dailyLossLoadSerial || requestQueryKey !== currentDailyLossQueryKey.value) return false
    items.value = itemRows
    reports.value = reportRows
    monthlyArchive.value = archiveRow
    openRequestedReport(reportRows)
    await loadRemoteImages(reportRows)
    if (loadSerial !== dailyLossLoadSerial || requestQueryKey !== currentDailyLossQueryKey.value) return false
    loadedDailyLossQueryKey.value = requestQueryKey
    markFresh()
    return true
  } catch (error) {
    if (loadSerial === dailyLossLoadSerial && requestQueryKey === currentDailyLossQueryKey.value) {
      pageError.value = readableError(error, '报损数据读取失败，请稍后重试。')
    }
    return false
  } finally {
    if (loadSerial === dailyLossLoadSerial) loadingData.value = false
  }
}

function dailyLossQueryKey(storeId: string, month: string, allStores: boolean) {
  const storeScope = storeId || (allStores ? '__ALL_STORES__' : '__NO_STORE__')
  return `${storeScope}\u0000${month}`
}

function addLine() {
  lines.value.push(emptyLine())
}

function requestStoreChange(nextStoreId: string) {
  if (nextStoreId === selectedStoreId.value) return
  if (hasPendingLossChanges.value) {
    pendingStoreId.value = nextStoreId
    scopeDiscardOpen.value = true
    return
  }
  selectedStoreId.value = nextStoreId
}

function keepCurrentLossDraft() {
  pendingStoreId.value = null
  scopeDiscardOpen.value = false
}

function discardLossDraftAndChangeStore() {
  const nextStoreId = pendingStoreId.value
  resetLossDraft()
  pendingStoreId.value = null
  scopeDiscardOpen.value = false
  if (nextStoreId !== null) selectedStoreId.value = nextStoreId
}

function resetLossDraft() {
  lines.value = [emptyLine()]
  supplierCompensation.value = '0'
  selectedFiles.value = []
  releaseSelectedPreviews()
  Object.keys(approvalNotes.value).forEach((key) => delete approvalNotes.value[key])
}

function removeLine(index: number) {
  lines.value.splice(index, 1)
  if (!lines.value.length) lines.value.push(emptyLine())
}

async function showFilteredReports(filter: 'NOT_REPORTED' | 'SUBMITTED' | 'REVIEWED') {
  recordFilter.value = filter
  await nextTick()
  recordsRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function filteredRecordsTitle() {
  if (recordFilter.value === 'NOT_REPORTED') return '全部未报门店'
  if (recordFilter.value === 'SUBMITTED') return '全部待复核报损'
  if (recordFilter.value === 'REVIEWED') return '全部已复核报损'
  return '每日报损列表'
}

function filteredRecordsDescription() {
  if (recordFilter.value === 'NOT_REPORTED') return '按天显示尚未提交报损的门店。'
  if (recordFilter.value === 'SUBMITTED') return '集中显示所有门店等待督导处理的报损。'
  if (recordFilter.value === 'REVIEWED') return '集中显示已经完成督导复核的报损。'
  return '按天显示各门店未报、待复核、已复核；未来日期不显示。'
}

function itemLabel(item: DailyLossItem) {
  return item.itemName || item.name || item.itemCode || item.code || `品类 ${item.id}`
}

function itemUnit(item?: DailyLossItem) {
  return item?.unit || item?.stockUnit || '克'
}

function itemCategoryName(item: DailyLossItem) {
  return item.categoryName || item.category || '其他'
}

function itemPriceLabel(item: DailyLossItem) {
  return `每${itemUnit(item)} ¥${Number(item.unitPrice || 0).toFixed(4)}`
}

function itemCategoryCode(item: DailyLossItem) {
  return item.categoryCode || item.categoryName || item.category || '其他'
}

function selectedItem(line: LossLineForm) {
  return itemsById.value.get(Number(line.itemConfigId))
}

function selectedLineUnit(line: LossLineForm) {
  return itemUnit(selectedItem(line))
}

function pricingHint(line: LossLineForm) {
  const item = selectedItem(line)
  if (!item) return ''
  const factor = Number(item.quantityPerPricingUnit || 1)
  if (Number(item.unitPrice || 0) === 0) {
    return `仓库免费叫货（0元），按${itemUnit(item)}登记报损数量`
  }
  return `${formatQuantity(factor)}${itemUnit(item)} = 1${item.pricingUnit || itemUnit(item)}，单价 ¥${formatMoney(item.unitPrice || 0)}`
}

function formatQuantity(value: number) {
  return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(4)))
}

function formatMoney(value?: number) {
  return Number(value || 0).toFixed(2)
}

function openItemPicker(index: number) {
  if (!items.value.length) {
    pageError.value = '暂无报损品类，请先导入报损单价配置'
    return
  }
  pickerLineIndex.value = index
  pickerOpen.value = true
  void nextTick(() => pickerSelectRef.value?.focus())
}

function closeItemPicker() {
  pickerOpen.value = false
}

function setPickerCategory(code: string) {
  pickerCategory.value = code
}

function isPickerItemSelected(item: DailyLossItem) {
  const itemId = String(item.id)
  return lines.value.some((line, index) => (
    index !== pickerLineIndex.value && line.itemConfigId === itemId
  ))
}

function selectPickerItem(item: DailyLossItem) {
  if (isPickerItemSelected(item)) return
  const line = lines.value[pickerLineIndex.value]
  if (!line) return
  line.itemConfigId = String(item.id)
  const id = Number(item.id)
  recentItemIds.value = [id, ...recentItemIds.value.filter((recentId) => recentId !== id)].slice(0, 8)
  closeItemPicker()
}

function selectPickerItemValue(value: string | number) {
  const item = itemsById.value.get(Number(value))
  if (item) selectPickerItem(item)
}

function applyQuickReason(line: LossLineForm, reason: string) {
  line.reason = reason
}

function selectedFileKey(file: File) {
  return `${file.name}:${file.size}:${file.lastModified}`
}

function onFilesChanged(event: Event) {
  const input = event.target as HTMLInputElement
  const incomingFiles = Array.from(input.files || []).filter((file) => file.type.startsWith('image/'))
  input.value = ''
  const existingKeys = new Set(selectedFiles.value.map(selectedFileKey))
  const addedFiles = incomingFiles.filter((file) => !existingKeys.has(selectedFileKey(file)))
  selectedFiles.value = [...selectedFiles.value, ...addedFiles]
  selectedFilePreviews.value = {
    ...selectedFilePreviews.value,
    ...Object.fromEntries(addedFiles.map((file) => [selectedFileKey(file), URL.createObjectURL(file)])),
  }
}

function removeSelectedFile(file: File) {
  const key = selectedFileKey(file)
  const previewUrl = selectedFilePreviews.value[key]
  if (previewUrl) URL.revokeObjectURL(previewUrl)
  selectedFiles.value = selectedFiles.value.filter((current) => current !== file)
  const next = { ...selectedFilePreviews.value }
  delete next[key]
  selectedFilePreviews.value = next
}

async function submitReport() {
  if (submitting.value || !canSubmit.value) return
  if (!effectiveStoreId.value) {
    pageError.value = '请先选择有权限的门店。'
    return
  }
  if (!items.value.length) {
    pageError.value = '暂无报损品类，请先导入报损单价配置'
    return
  }
  const details = lines.value.map((line) => ({
    itemConfigId: Number(line.itemConfigId),
    lossQuantity: Number(line.quantity),
    lossReason: line.reason.trim() || undefined,
  })).filter((line) => Number.isInteger(line.itemConfigId) && line.itemConfigId > 0 && Number.isFinite(line.lossQuantity) && line.lossQuantity > 0)
  if (!details.length) {
    pageError.value = '请至少选择一个报损品类，并填写大于零的数量。'
    return
  }
  if (expectedSupplierCompensation.value > expectedLossAmount.value + 0.005) {
    pageError.value = '厂商赔付金额不能超过报损总金额。'
    return
  }
  if (!selectedFiles.value.length && !(todayReport.value?.attachments?.length)) {
    pageError.value = '请至少上传一张报损照片。'
    return
  }
  submitting.value = true
  uploadProgress.value = 0
  pageError.value = ''
  actionMessage.value = ''
  try {
    const saved = await saveDailyLossReport({
      storeId: effectiveStoreId.value,
      lossDate: localDate(),
      details,
      supplierCompensationAmount: expectedSupplierCompensation.value,
    })
    if (selectedFiles.value.length && saved.id) {
      await uploadDailyLossReportAttachments(saved.id, selectedFiles.value, (percent) => { uploadProgress.value = percent })
    }
    if (saved.id) await submitDailyLossReport(saved.id)
    actionMessage.value = '今日报损已提交，等待督导复核。'
    resetLossDraft()
    await loadDailyLossData()
  } catch (error) {
    pageError.value = readableError(error, '报损提交失败，请检查填写内容后重试。')
  } finally {
    submitting.value = false
    uploadProgress.value = 0
  }
}

async function reviewReport(report: DailyLossReport) {
  if (!canReview.value || approvingId.value || !report.id) return
  approvingId.value = report.id
  pageError.value = ''
  actionMessage.value = ''
  try {
    await reviewDailyLossReport(report.id, approvalNotes.value[report.id])
    actionMessage.value = `${report.storeName || report.storeId} ${report.lossDate} 的报损已复核，库存已按报损明细扣减。`
    await loadDailyLossData()
  } catch (error) {
    pageError.value = readableError(error, '复核失败，请稍后重试。')
  } finally {
    approvingId.value = ''
  }
}

async function exportMonthlyExcel() {
  if ((!effectiveStoreId.value && !canSelectAllStores.value) || !canExport.value || exporting.value) return
  exporting.value = true
  pageError.value = ''
  actionMessage.value = ''
  try {
    await downloadMonthlyDailyLossExcel(selectedMonth.value, effectiveStoreId.value || undefined)
    actionMessage.value = '本月报损 Excel 已开始下载。'
  } catch (error) {
    pageError.value = readableError(error, '本月报损 Excel 导出失败，请确认门店和月份后重试。')
  } finally {
    exporting.value = false
  }
}

async function loadRemoteImages(rows: DailyLossReport[]) {
  releaseRemoteImages()
  const attachments = rows.flatMap((report) => report.attachments || []).filter(isImageAttachment)
  imageStates.value = Object.fromEntries(attachments.map((attachment) => [String(attachment.id), 'loading' as const]))
  await Promise.all(attachments.map(loadRemoteImage))
}

async function loadRemoteImage(attachment: DailyLossAttachment) {
  const key = String(attachment.id)
  const controller = new AbortController()
  photoControllers.set(key, controller)
  try {
    const blob = await fetchDailyLossAttachment(attachment, controller.signal)
    if (!String(blob.type || attachment.contentType || '').startsWith('image/')) throw new Error('不是图片')
    imageUrls.value = { ...imageUrls.value, [key]: URL.createObjectURL(blob) }
    imageStates.value = { ...imageStates.value, [key]: 'ready' }
  } catch {
    if (!controller.signal.aborted) imageStates.value = { ...imageStates.value, [key]: 'error' }
  } finally {
    photoControllers.delete(key)
  }
}

function releaseRemoteImages() {
  photoControllers.forEach((controller) => controller.abort())
  photoControllers.clear()
  Object.values(imageUrls.value).forEach((url) => URL.revokeObjectURL(url))
  imageUrls.value = {}
  imageStates.value = {}
  preview.value = null
}

function releaseSelectedPreviews() {
  Object.values(selectedFilePreviews.value).forEach((url) => URL.revokeObjectURL(url))
  selectedFilePreviews.value = {}
}

function isImageAttachment(attachment: DailyLossAttachment) {
  return String(attachment.contentType || '').startsWith('image/') || /\.(?:jpe?g|png|webp|gif)$/i.test(attachment.fileName)
}

function reportPhotos(report: DailyLossReport | null) {
  return (report?.attachments || []).filter(isImageAttachment)
}

function openPreview(attachment: DailyLossAttachment) {
  const url = imageUrls.value[String(attachment.id)]
  if (url) preview.value = { url, alt: attachment.fileName || '报损照片' }
}

function openSelectedPreview(file: File) {
  const url = selectedFilePreviews.value[selectedFileKey(file)]
  if (url) preview.value = { url, alt: file.name }
}

function openReport(report: DailyLossReport) {
  if (!report.reported && report.lossDate === localDate() && canSubmit.value) {
    formRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    actionMessage.value = '今日尚未提交，请在上方填写并上传报损照片。'
    return
  }
  detailReport.value = report
}

function initialStoreId() {
  if (scope.isStoreManager.value) return scope.boundStoreId.value
  const requested = queryValue('storeId')
  if (requested && selectableStores.value.some((store) => store.id === requested)) return requested
  return canSelectAllStores.value ? '' : selectableStores.value[0]?.id || ''
}

function openRequestedReport(rows: DailyLossReport[]) {
  const reportId = queryValue('reportId')
  if (!reportId) return
  detailReport.value = rows.find((report) => report.id === reportId) || null
}

function queryValue(key: string) {
  const raw = route.query[key]
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' ? value.trim() : ''
}

function closeLayersOnEsc(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  if (preview.value) preview.value = null
  else if (pickerOpen.value) closeItemPicker()
  else if (detailReport.value) detailReport.value = null
}

function statusKey(report: DailyLossReport) {
  return String(report.status || '').trim().toUpperCase()
}

function statusLabel(report: DailyLossReport) {
  if (report.statusLabel) return report.statusLabel
  const status = statusKey(report)
  if (status === 'NOT_REPORTED') return '未报'
  if (status === 'DRAFT') return '已保存'
  if (status === 'SUBMITTED') return '待复核'
  if (['REVIEWED', 'APPROVED'].includes(status)) return '已复核'
  if (status === 'REJECTED') return '已驳回'
  return '处理中'
}

function readableError(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

function localDate() {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

function currentMonth() {
  return localDate().slice(0, 7)
}
</script>

<template>
  <section class="daily-loss-page">
    <PageHeader title="每日报损" />

    <div v-if="pageError" class="message message--error" role="alert">{{ pageError }}</div>
    <div v-if="actionMessage" class="message message--success" role="status">{{ actionMessage }}</div>

    <section class="loss-toolbar content-card" aria-label="报损筛选">
      <BusinessScopeBar v-if="scope.isStoreManager.value" />
      <label v-if="!scope.isStoreManager.value" class="toolbar-field">
        <span>门店</span>
        <SearchableSingleSelect
          :model-value="selectedStoreId"
          :options="selectableStoreOptions"
          :disabled="loading || loadingData"
          :empty-option-label="canSelectAllStores ? '全部茹菓门店' : undefined"
          empty-value=""
          placeholder="请选择门店"
          search-placeholder="搜索门店名称、编号、区域或状态"
          aria-label="搜索门店"
          @update:model-value="requestStoreChange(String($event))"
        />
      </label>
      <UiButton
        v-if="canExport"
        class="toolbar-export"
        :loading="exporting"
        :disabled="(!effectiveStoreId && !canSelectAllStores) || loading || loadingData"
        @click="exportMonthlyExcel"
      >
        <template #icon><Download :size="16" /></template>
        {{ exporting ? '正在生成 Excel…' : '导出本月报损 Excel' }}
      </UiButton>
    </section>

    <div class="loss-summary" aria-label="报损汇总">
      <article class="summary-action-card">
        <button type="button" class="summary-action" @click="showFilteredReports('NOT_REPORTED')">
          <span>未报</span><strong>{{ notReportedCount }}</strong><small>点击查看全部未报</small>
        </button>
      </article>
      <article class="summary-action-card">
        <button type="button" class="summary-action" @click="showFilteredReports('SUBMITTED')">
          <span>待复核</span><strong>{{ pendingCount }}</strong><small>点击查看全部待复核</small>
        </button>
      </article>
      <article class="summary-action-card">
        <button type="button" class="summary-action" @click="showFilteredReports('REVIEWED')">
          <span>已复核</span><strong>{{ reviewedCount }}</strong><small>点击查看全部已复核</small>
        </button>
      </article>
      <article><span>今日状态</span><strong>{{ todayReport ? statusLabel(todayReport) : '未报' }}</strong><small>{{ localDate() }}</small></article>
    </div>

    <section v-if="scopedMonthlyArchive" class="content-card archive-summary" aria-label="历史月度报损归档">
      <header>
        <div>
          <h2>{{ scopedMonthlyArchive.sourceTitle }}</h2>
          <p>历史真实数据 · {{ scopedMonthlyArchive.storeCount }} 家门店 · {{ scopedMonthlyArchive.itemCount }} 个品类</p>
        </div>
        <span :class="{ warning: scopedMonthlyArchive.reconciliationStatus === 'SOURCE_VARIANCE' }">
          {{ scopedMonthlyArchive.reconciliationStatus === 'MATCHED' ? '源表已核对' : '源表存在汇总差异' }}
        </span>
      </header>
      <div class="archive-amounts">
        <div><span>源表总损耗</span><strong>¥{{ formatMoney(scopedMonthlyArchive.declaredTotalLossAmount) }}</strong></div>
        <div><span>厂商赔付</span><strong>¥{{ formatMoney(scopedMonthlyArchive.supplierCompensationAmount) }}</strong></div>
        <div><span>系统计算店铺承担</span><strong>¥{{ formatMoney(scopedMonthlyArchive.calculatedStoreBorneAmount) }}</strong></div>
      </div>
      <p v-if="scopedMonthlyArchive.reconciliationStatus === 'SOURCE_VARIANCE'" class="archive-note">
        原始值已完整保留：{{ scopedMonthlyArchive.sourceNote }}
      </p>
    </section>

    <form v-if="canSubmit" ref="formRef" class="content-card loss-form" @submit.prevent="submitReport">
      <div class="section-heading">
        <PackageMinus :size="20" />
        <div>
          <h2>今日报损</h2>
          <p>按实际单位录入数量，系统自动折算计价，并分别核算厂商赔付与店铺承担。</p>
        </div>
      </div>

      <div v-if="!loadingData && !items.length" class="empty-config-alert" role="status">
        暂无报损品类，请先导入报损单价配置
      </div>

      <div class="line-list">
        <article v-for="(line, index) in lines" :key="index" class="line-row">
          <label class="item-field">
            <span>品类</span>
            <button
              class="item-picker-trigger"
              type="button"
              :aria-expanded="pickerOpen && pickerLineIndex === index"
              @click="openItemPicker(index)"
            >
              <span v-if="selectedItem(line)" class="picked-item">
                <b>{{ itemLabel(selectedItem(line)!) }}</b>
                <small>{{ itemCategoryName(selectedItem(line)!) }}</small>
              </span>
              <span v-else class="picker-placeholder">选择品类</span>
              <ChevronDown :size="16" />
            </button>
          </label>
          <label class="quantity-field">
            <span>数量</span>
            <span class="quantity-control">
              <input v-model="line.quantity" type="number" min="0.01" step="0.01" inputmode="decimal" placeholder="0.00" required />
              <em>{{ selectedLineUnit(line) }}</em>
            </span>
            <small v-if="selectedItem(line)" class="pricing-hint">{{ pricingHint(line) }}</small>
          </label>
          <label class="reason-field">
            <span>原因</span>
            <input v-model.trim="line.reason" maxlength="120" placeholder="填写报损原因" />
            <div class="quick-reasons" aria-label="快捷原因">
              <button
                v-for="reason in QUICK_REASONS"
                :key="reason"
                type="button"
                :class="{ active: line.reason === reason }"
                @click="applyQuickReason(line, reason)"
              >
                {{ reason }}
              </button>
            </div>
          </label>
          <button class="icon-line-button" type="button" aria-label="删除明细" @click="removeLine(index)">
            <Trash2 :size="16" />
          </button>
        </article>
      </div>

      <button class="text-button" type="button" @click="addLine"><Plus :size="15" />增加品类</button>

      <section class="settlement-block" aria-label="报损结算">
        <label>
          <span>厂商赔付金额</span>
          <span class="money-control"><em>¥</em><input v-model="supplierCompensation" type="number" min="0" step="0.01" inputmode="decimal" /></span>
        </label>
        <div><span>总计损耗金额</span><strong>¥{{ formatMoney(expectedLossAmount) }}</strong></div>
        <div><span>店铺承担</span><strong>¥{{ formatMoney(expectedStoreBorneAmount) }}</strong></div>
      </section>

      <section class="photo-upload-block" aria-label="报损照片上传">
        <label class="attachment-field">
          <span><FileUp :size="15" />报损照片</span>
          <span class="file-picker-ui">
            <span class="file-picker-button">选择照片</span>
            <em>{{ selectedFiles.length ? `已选择 ${selectedFiles.length} 张` : '未选择照片' }}</em>
          </span>
          <input type="file" multiple accept="image/*" @change="onFilesChanged" />
          <small v-if="selectedFiles.length">已选择 {{ selectedFiles.length }} 张照片，可继续追加选择<span v-if="submitting && uploadProgress">，上传 {{ uploadProgress }}%</span></small>
          <small v-else>提交前至少上传一张报损现场照片，照片数量不限。</small>
        </label>
        <div v-if="selectedFiles.length" class="selected-preview-grid">
          <figure v-for="file in selectedFiles" :key="selectedFileKey(file)">
            <button type="button" :aria-label="`预览 ${file.name}`" @click="openSelectedPreview(file)">
              <img :src="selectedFilePreviews[selectedFileKey(file)]" :alt="`${file.name} 缩略图`" />
            </button>
            <button type="button" class="remove-photo" :aria-label="`移除 ${file.name}`" @click="removeSelectedFile(file)">
              <X :size="13" />
            </button>
          </figure>
        </div>
      </section>

      <div class="form-footer">
        <UiButton variant="primary" type="submit" :loading="submitting" :disabled="!effectiveStoreId">
          <template #icon><Send :size="17" /></template>
          提交今日报损
        </UiButton>
      </div>
    </form>

    <section ref="recordsRef" class="content-card records-card" aria-label="每日报损列表">
      <div class="records-toolbar">
        <div>
          <h2>{{ filteredRecordsTitle() }}</h2>
          <p>{{ filteredRecordsDescription() }}</p>
        </div>
        <UiButton v-if="recordFilter !== 'ALL'" variant="ghost" @click="recordFilter = 'ALL'">返回全部记录</UiButton>
      </div>
      <div v-if="(loading || loadingData) && !scopedReports.length" class="empty-state">正在读取报损记录...</div>
      <div v-else-if="reportDayGroups.length" class="day-groups">
        <section v-for="group in reportDayGroups" :key="group.date" class="day-group">
          <header class="day-group-header">
            <strong>{{ group.date }}</strong>
            <span v-if="recordFilter === 'ALL'">{{ group.notReportedCount ? `${group.notReportedCount} 家未报` : '全部已报' }}</span>
            <span v-else>{{ group.rows.length }} 条记录</span>
          </header>
          <ol class="record-list">
            <li
              v-for="report in group.rows"
              :key="`${report.storeId}-${report.lossDate}`"
              class="record-row"
              :class="{ empty: !report.reported }"
              role="button"
              tabindex="0"
              @click="openReport(report)"
              @keydown.enter.prevent="openReport(report)"
            >
          <div class="record-main">
            <div class="record-title">
              <strong>{{ report.storeName || report.storeId }} · {{ report.lossDate }}</strong>
              <span class="status-pill" :class="`status-${statusKey(report).toLowerCase()}`">{{ statusLabel(report) }}</span>
            </div>
            <p v-if="report.reported">
              {{ report.detailCount || 0 }} 项明细 · 损耗 ¥{{ formatMoney(report.totalAmount) }} · 厂商赔付 ¥{{ formatMoney(report.supplierCompensationAmount) }} · 店铺承担 ¥{{ formatMoney(report.storeBorneAmount) }}
            </p>
            <p v-if="report.reported && ['REVIEWED', 'APPROVED'].includes(statusKey(report))" class="inventory-result">
              {{ report.inventoryStatusLabel || (report.inventoryDeducted ? '库存已准确扣减' : '库存扣减状态异常') }}
            </p>
            <p v-else>当天尚未提交报损。</p>
            <div v-if="report.details?.length" class="detail-list">
              <span v-for="detail in report.details" :key="detail.id">
                {{ detail.itemName }} {{ detail.lossQuantity }}{{ detail.unit || '' }}
              </span>
            </div>
            <div v-if="reportPhotos(report).length" class="photo-grid">
              <button
                v-for="attachment in reportPhotos(report)"
                :key="String(attachment.id)"
                type="button"
                :disabled="imageStates[String(attachment.id)] !== 'ready'"
                :aria-label="`预览 ${attachment.fileName}`"
                @click.stop="openPreview(attachment)"
              >
                <img v-if="imageStates[String(attachment.id)] === 'ready'" :src="imageUrls[String(attachment.id)]" :alt="`${attachment.fileName} 缩略图`" />
                <ImageOff v-else-if="imageStates[String(attachment.id)] === 'error'" :size="18" />
                <Image v-else :size="18" />
              </button>
            </div>
          </div>
          <div v-if="canReview && report.id && statusKey(report) === 'SUBMITTED'" class="review-action" @click.stop>
            <input v-model="approvalNotes[report.id]" maxlength="200" placeholder="复核备注（可选）" />
            <UiButton variant="primary" :loading="approvingId === report.id" @click="reviewReport(report)">
              <template #icon><CheckCircle2 :size="16" /></template>
              复核通过
            </UiButton>
          </div>
          <p v-else-if="report.reviewNote" class="approval-note">复核说明：{{ report.reviewNote }}</p>
            </li>
          </ol>
        </section>
      </div>
      <div v-else class="empty-state">{{ recordFilter === 'ALL' ? '当前月份暂无报损记录。' : '当前筛选状态没有记录。' }}</div>
    </section>
  </section>

  <Teleport to="body">
    <div v-if="pickerOpen" class="picker-backdrop" @click.self="closeItemPicker">
      <section class="item-picker-dialog" role="dialog" aria-modal="true" aria-label="选择报损品类" tabindex="-1">
        <header>
          <div>
            <h2>选择品类</h2>
            <p>可按名称、编码、分类或单位搜索，选项同时显示损耗单价。</p>
          </div>
          <UiButton variant="ghost" icon-only aria-label="关闭品类选择" title="关闭" @click="closeItemPicker">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>
        <div class="category-tabs" aria-label="品类分类">
          <button
            v-for="tab in categoryTabs"
            :key="tab.code"
            type="button"
            :class="{ active: pickerCategory === tab.code }"
            @click="setPickerCategory(tab.code)"
          >
            {{ tab.name }}<span>{{ tab.count }}</span>
          </button>
        </div>
        <SearchableSingleSelect
          ref="pickerSelectRef"
          :model-value="lines[pickerLineIndex]?.itemConfigId || ''"
          :options="pickerItemOptions"
          placeholder="选择报损品类"
          search-placeholder="搜索名称、编码、分类或单位"
          aria-label="搜索报损品类"
          empty-message="没有匹配的报损品类，请调整搜索词或分类"
          @update:model-value="selectPickerItemValue"
        />
      </section>
    </div>
  </Teleport>

  <Teleport to="body">
    <div v-if="detailReport" class="detail-backdrop" @click.self="detailReport = null">
      <section class="detail-dialog" role="dialog" aria-modal="true" aria-label="报损详情">
        <header>
          <div>
            <h2>{{ detailReport.storeName || detailReport.storeId }} · {{ detailReport.lossDate }}</h2>
            <p><span class="status-pill" :class="`status-${statusKey(detailReport).toLowerCase()}`">{{ statusLabel(detailReport) }}</span></p>
          </div>
          <UiButton variant="ghost" icon-only aria-label="关闭报损详情" title="关闭" @click="detailReport = null">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>
        <div v-if="!detailReport.reported" class="empty-state">
          该日期尚未提交报损。历史日期不能在此补报，请按现有业务规则处理。
        </div>
        <div v-else class="detail-body">
          <section class="detail-settlement">
            <div><span>总计损耗金额</span><strong>¥{{ formatMoney(detailReport.totalAmount) }}</strong></div>
            <div><span>厂商赔付金额</span><strong>¥{{ formatMoney(detailReport.supplierCompensationAmount) }}</strong></div>
            <div><span>店铺承担</span><strong>¥{{ formatMoney(detailReport.storeBorneAmount) }}</strong></div>
          </section>
          <section>
            <h3>报损明细</h3>
            <div class="detail-list detail-list--dialog">
              <span v-for="detail in detailReport.details || []" :key="detail.id">
                {{ detail.itemName }} {{ detail.lossQuantity }}{{ detail.unit || '' }} → {{ detail.pricedQuantity }}{{ detail.pricingUnit || detail.unit || '' }} · ¥{{ formatMoney(detail.amountSnapshot) }}<template v-if="detail.lossReason"> · {{ detail.lossReason }}</template>
              </span>
            </div>
          </section>
          <section>
            <h3>报损照片</h3>
            <div v-if="reportPhotos(detailReport).length" class="photo-grid photo-grid--large">
              <button
                v-for="attachment in reportPhotos(detailReport)"
                :key="String(attachment.id)"
                type="button"
                :disabled="imageStates[String(attachment.id)] !== 'ready'"
                :aria-label="`预览 ${attachment.fileName}`"
                @click="openPreview(attachment)"
              >
                <img v-if="imageStates[String(attachment.id)] === 'ready'" :src="imageUrls[String(attachment.id)]" :alt="`${attachment.fileName} 缩略图`" />
                <ImageOff v-else-if="imageStates[String(attachment.id)] === 'error'" :size="22" />
                <Image v-else :size="22" />
              </button>
            </div>
            <div v-else class="empty-state">该报损暂无可预览图片。</div>
          </section>
        </div>
      </section>
    </div>
  </Teleport>

  <Teleport to="body">
    <div v-if="preview" class="image-preview-backdrop" @click.self="preview = null">
      <section class="image-preview-dialog" role="dialog" aria-modal="true" aria-label="报损图片预览">
        <header>
          <b>{{ preview.alt }}</b>
          <UiButton variant="ghost" icon-only aria-label="关闭图片预览" title="关闭" @click="preview = null">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>
        <img :src="preview.url" :alt="preview.alt" />
      </section>
    </div>
  </Teleport>

  <UnsavedChangesDialog
    :open="scopeDiscardOpen"
    title="切换门店前确认"
    message="切换门店会放弃当前尚未提交的报损内容和复核备注。"
    @keep-editing="keepCurrentLossDraft"
    @discard="discardLossDraftAndChangeStore"
  />
</template>

<style scoped>
.daily-loss-page { display: grid; gap: 16px; min-width: 0; }
.message { padding: 11px 13px; border-radius: 8px; font-weight: 650; font-size: 14px; }
.message--error { color: #9f2734; border: 1px solid #efc9cf; background: #fff5f5; }
.message--success { color: #185c48; border: 1px solid #bde4d5; background: #f0fbf6; }

.loss-toolbar {
  display: grid;
  grid-template-columns: minmax(210px, 1fr) max-content;
  min-height: 64px;
  align-items: end;
  gap: 12px;
  padding: 12px 14px;
}

.loss-toolbar :deep(.business-scope-static),
.loss-toolbar :deep(.business-scope-error) { grid-column: 1 / -1; }

.toolbar-field {
  display: grid;
  min-width: 190px;
  gap: 4px;
  color: var(--ds-muted);
  font-weight: 700;
  font-size: 12px;
}

.toolbar-field select,
.toolbar-field input {
  width: 100%;
  min-height: 38px;
  padding: 7px 10px;
  border: 1px solid var(--ds-line);
  border-radius: 7px;
  background: var(--ds-surface);
  color: var(--ds-ink);
  font: inherit;
}

.toolbar-field :deep(.searchable-single-select) { width: 100%; }

.toolbar-export { justify-self: end; }

.loss-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.loss-summary article { display: grid; gap: 4px; padding: 15px 16px; border: 1px solid var(--ds-line); border-radius: 8px; background: var(--ds-surface); }
.loss-summary span, .loss-summary small { color: var(--ds-muted); font-size: 12px; }
.loss-summary strong { color: var(--ds-ink); font-size: 22px; line-height: 1.15; word-break: break-word; }
.summary-action-card { padding: 0 !important; }
.summary-action {
  display: grid;
  width: 100%;
  height: 100%;
  gap: 4px;
  padding: 15px 16px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.summary-action:hover,
.summary-action:focus-visible {
  background: var(--ds-primary-soft);
  outline: 2px solid var(--ds-primary);
}

.archive-summary { display: grid; gap: 14px; padding: 18px 20px; }
.archive-summary header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.archive-summary h2 { margin: 0; color: var(--ds-ink); font-size: 17px; }
.archive-summary header p { margin: 4px 0 0; color: var(--ds-muted); font-size: 12px; }
.archive-summary header > span { padding: 4px 9px; border-radius: 999px; background: var(--ds-success-soft); color: #27724b; font-size: 12px; font-weight: 700; }
.archive-summary header > span.warning { background: var(--ds-warning-soft); color: #87500f; }
.archive-amounts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.archive-amounts div { display: grid; gap: 4px; padding: 12px; border: 1px solid var(--ds-line); border-radius: 7px; background: #fbfdfc; }
.archive-amounts span { color: var(--ds-muted); font-size: 12px; }
.archive-amounts strong { color: var(--ds-ink); font-size: 19px; }
.archive-note { margin: 0; padding: 9px 11px; border-radius: 6px; background: var(--ds-warning-soft); color: #87500f; font-size: 12px; line-height: 1.5; }

.loss-form,
.records-card {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: 20px;
}

.section-heading,
.records-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--ds-line);
}

.section-heading svg { margin-top: 2px; color: var(--ds-primary-hover); }
.section-heading h2,
.records-toolbar h2 { margin: 0; color: var(--ds-ink); font-size: 17px; }
.section-heading p,
.records-toolbar p { margin: 3px 0 0; color: var(--ds-muted); font-size: 12px; line-height: 1.5; }

.empty-config-alert {
  padding: 10px 12px;
  border: 1px solid #efddb9;
  border-radius: 7px;
  background: var(--ds-warning-soft);
  color: #87500f;
  font-size: 13px;
  font-weight: 650;
}

.line-list { display: grid; gap: 12px; }
.line-row {
  display: grid;
  grid-template-columns: minmax(280px, 1.45fr) minmax(180px, .7fr) minmax(260px, 1fr) 42px;
  gap: 12px;
  align-items: start;
  padding: 12px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fbfdfc;
}

.loss-form label { display: grid; min-width: 0; gap: 6px; color: var(--ds-secondary); font-size: 13px; font-weight: 700; }
.item-picker-trigger,
.quantity-control,
.reason-field input,
.review-action input {
  width: 100%;
  min-height: 42px;
  border: 1px solid var(--ds-line-strong);
  border-radius: 7px;
  background: #fff;
  color: var(--ds-ink);
  font: inherit;
}

.item-picker-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 10px;
  text-align: left;
}

.item-picker-trigger:hover,
.item-picker-trigger:focus-visible {
  border-color: var(--ds-primary);
  outline: none;
  box-shadow: 0 0 0 2px rgba(118, 189, 184, .18);
}

.picker-placeholder { color: var(--ds-muted); font-weight: 650; }
.picked-item { display: grid; min-width: 0; gap: 2px; }
.picked-item b { overflow: hidden; color: var(--ds-ink); text-overflow: ellipsis; white-space: nowrap; }
.picked-item small { overflow: hidden; color: var(--ds-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }

.quantity-control {
  display: grid;
  grid-template-columns: minmax(112px, 1fr) auto;
  align-items: center;
  overflow: hidden;
}

.quantity-control input {
  min-width: 0;
  min-height: 40px;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.quantity-control em {
  min-width: 42px;
  padding: 0 10px;
  border-left: 1px solid var(--ds-line);
  color: var(--ds-muted);
  font-style: normal;
  text-align: center;
  white-space: nowrap;
}

.pricing-hint { color: var(--ds-muted); font-size: 11px; font-weight: 600; line-height: 1.35; }

.settlement-block,
.detail-settlement {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  padding: 14px;
  border: 1px solid #efddb9;
  border-radius: 8px;
  background: var(--ds-warning-soft);
}

.settlement-block > div,
.detail-settlement > div { display: grid; gap: 5px; align-content: center; }
.settlement-block span,
.detail-settlement span { color: var(--ds-muted); font-size: 12px; font-weight: 700; }
.settlement-block strong,
.detail-settlement strong { color: var(--ds-ink); font-size: 19px; }

.money-control {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  min-height: 42px;
  overflow: hidden;
  border: 1px solid var(--ds-line-strong);
  border-radius: 7px;
  background: #fff;
}

.money-control em { padding-left: 10px; color: var(--ds-muted); font-style: normal; }
.money-control input { min-width: 0; min-height: 40px; border: 0; background: transparent; color: var(--ds-ink); font: inherit; }

.reason-field input { padding: 8px 10px; }
.quick-reasons { display: flex; gap: 6px; flex-wrap: wrap; }
.quick-reasons button,
.category-tabs button {
  min-height: 28px;
  padding: 0 9px;
  border: 1px solid var(--ds-line);
  border-radius: 999px;
  background: #fff;
  color: var(--ds-secondary);
  font-size: 12px;
  font-weight: 700;
}

.quick-reasons button.active,
.quick-reasons button:hover,
.category-tabs button.active {
  border-color: var(--ds-primary);
  background: var(--ds-primary-soft);
  color: var(--ds-primary-hover);
}

.icon-line-button {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  margin-top: 23px;
  border: 1px solid var(--ds-line);
  border-radius: 7px;
  background: #fff;
  color: var(--ds-danger);
}

.text-button {
  display: inline-flex;
  width: fit-content;
  min-height: 36px;
  align-items: center;
  gap: 6px;
  padding: 0 11px;
  border: 0;
  border-radius: 6px;
  background: var(--ds-surface-muted);
  color: var(--ds-primary-hover);
  font-weight: 800;
}

.photo-upload-block {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px dashed var(--ds-line-strong);
  border-radius: 8px;
  background: #fff;
}

.attachment-field span { display: inline-flex; align-items: center; gap: 6px; }
.attachment-field {
  position: relative;
}

.attachment-field input {
  position: absolute;
  inset: auto auto 0 0;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.file-picker-ui {
  display: flex;
  width: 100%;
  min-height: 42px;
  align-items: center;
  gap: 10px;
  padding: 6px;
  border: 1px solid var(--ds-line);
  border-radius: 7px;
  background: var(--ds-surface);
}

.file-picker-button {
  min-height: 30px;
  padding: 6px 11px;
  border-radius: 6px;
  background: var(--ds-primary-hover);
  color: #fff;
  font-weight: 800;
}

.file-picker-ui em {
  min-width: 0;
  color: var(--ds-muted);
  font-style: normal;
  font-size: 13px;
}

.attachment-field small { color: var(--ds-muted); font-size: 12px; }

.selected-preview-grid,
.photo-grid {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.selected-preview-grid figure {
  position: relative;
  width: 82px;
  height: 68px;
  margin: 0;
}

.selected-preview-grid figure > button:first-child,
.photo-grid button {
  width: 100%;
  height: 100%;
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--ds-line);
  border-radius: 7px;
  background: var(--ds-surface-muted);
  color: var(--ds-muted);
}

.selected-preview-grid img,
.photo-grid img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.remove-photo {
  position: absolute;
  top: -7px;
  right: -7px;
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  padding: 0;
  border: 1px solid #fff;
  border-radius: 999px;
  background: var(--ds-danger);
  color: #fff;
}

.form-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
  border-top: 1px solid var(--ds-line);
}

.day-groups { display: grid; gap: 16px; }
.day-group { display: grid; gap: 9px; }
.day-group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-left: 4px solid var(--ds-primary);
  border-radius: 6px;
  background: var(--ds-surface-muted);
}
.day-group-header strong { color: var(--ds-ink); font-size: 15px; }
.day-group-header span { color: var(--ds-secondary); font-size: 12px; font-weight: 750; }
.record-list { display: grid; margin: 0; padding: 0; list-style: none; gap: 10px; }
.record-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  transition: border-color 160ms ease-out, background-color 160ms ease-out;
}

.record-row:hover,
.record-row:focus-visible {
  border-color: var(--ds-primary);
  background: #fbfefd;
  outline: none;
}

.record-row.empty { background: #fafbfc; }
.record-main { min-width: 0; }
.record-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.record-title strong { color: var(--ds-ink); font-size: 15px; }
.record-main p { margin: 7px 0 4px; color: var(--ds-secondary); font-size: 13px; line-height: 1.5; }
.status-pill { display: inline-flex; min-height: 23px; align-items: center; padding: 3px 7px; border-radius: 999px; background: #eef4f3; color: #3e5e5b; font-size: 11px; font-weight: 800; }
.status-not_reported { background: #f1f3f5; color: #5d6670; }
.status-draft { background: #eaf3ff; color: #245a99; }
.status-submitted { background: #fff5df; color: #9a6814; }
.status-reviewed,
.status-approved { background: #e9f8f0; color: #1d7655; }
.status-rejected { background: #fff0f1; color: #a53a46; }

.detail-list { display: flex; margin-top: 8px; gap: 6px; flex-wrap: wrap; }
.detail-list span { padding: 3px 7px; border-radius: 999px; background: var(--ds-surface-muted); color: var(--ds-secondary); font-size: 12px; }
.photo-grid { margin-top: 8px; }
.photo-grid button { display: grid; width: 64px; height: 54px; place-items: center; }
.photo-grid--large button { width: 112px; height: 92px; }
.review-action { display: grid; align-content: start; min-width: 190px; gap: 8px; }
.review-action :deep(.ui-button) { min-width: 126px; height: 38px; padding: 0 12px; font-size: 13px; }
.approval-note { grid-column: 1 / -1; margin: 0; color: var(--ds-muted); font-size: 12px; }
.empty-state { padding: 28px 16px; color: var(--ds-muted); text-align: center; font-size: 14px; }

.picker-backdrop,
.detail-backdrop,
.image-preview-backdrop {
  position: fixed;
  inset: 0;
  z-index: var(--ds-z-modal);
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(9, 14, 14, .58);
}

.item-picker-dialog,
.detail-dialog {
  display: grid;
  width: min(880px, calc(100vw - 48px));
  max-height: calc(100vh - 48px);
  overflow: hidden;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
}

.item-picker-dialog {
  grid-template-rows: auto auto minmax(0, 1fr);
}

.item-picker-dialog header,
.detail-dialog header,
.image-preview-dialog header {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.item-picker-dialog h2,
.detail-dialog h2,
.detail-dialog h3 {
  margin: 0;
  color: var(--ds-ink);
  font-size: 17px;
}

.item-picker-dialog p,
.detail-dialog p { margin: 3px 0 0; color: var(--ds-muted); font-size: 12px; }

.category-tabs {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}

.category-tabs button { flex: 0 0 auto; }
.category-tabs span { margin-left: 6px; color: var(--ds-muted); font-weight: 800; }
.item-picker-dialog :deep(.searchable-single-select) {
  min-height: 0;
}

.item-picker-dialog :deep(.searchable-single-select__control) {
  min-height: 42px;
  border-color: var(--ds-line-strong);
  border-radius: 7px;
}

.item-picker-dialog :deep(.searchable-single-select__menu) {
  position: static;
  max-height: min(420px, 48vh);
  margin-top: 8px;
  box-shadow: none;
}

.detail-body { display: grid; gap: 16px; overflow: auto; }
.detail-body section { display: grid; gap: 8px; }
.detail-list--dialog span { border-radius: 6px; }

.image-preview-backdrop { z-index: var(--ds-z-preview); background: rgba(9, 14, 14, .72); }
.image-preview-dialog { display: grid; max-width: min(960px, calc(100vw - 48px)); max-height: calc(100vh - 48px); overflow: hidden; border-radius: 8px; background: #fff; }
.image-preview-dialog header { padding: 10px 12px; border-bottom: 1px solid var(--ds-line); }
.image-preview-dialog header b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.image-preview-dialog > img { display: block; max-width: 100%; max-height: calc(100vh - 102px); margin: auto; object-fit: contain; }

@media (max-width: 1080px) {
  .loss-toolbar { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .toolbar-export { grid-column: 2; align-self: end; justify-self: end; }
  .line-row { grid-template-columns: minmax(240px, 1.2fr) minmax(160px, .7fr) minmax(220px, 1fr) 42px; }
}

@media (max-width: 900px) {
  .loss-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .line-row,
  .record-row { grid-template-columns: 1fr; }
  .icon-line-button { width: 100%; margin-top: 0; }
  .review-action { min-width: 0; }
  .review-action :deep(.ui-button) { width: 100%; }
}

@media (max-width: 680px) {
  .daily-loss-page { gap: 12px; }
  .loss-toolbar {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    align-items: end;
    gap: 8px;
    min-height: 0;
    padding: 10px;
  }
  .loss-toolbar :deep(.business-scope-static),
  .loss-toolbar :deep(.business-scope-error) {
    grid-column: 1 / -1;
    width: 100%;
    max-width: none;
  }
  .toolbar-field { min-width: 0; }
  .loss-summary { grid-template-columns: 1fr 1fr; gap: 8px; }
  .loss-summary article { padding: 12px; }
  .archive-amounts { grid-template-columns: 1fr; }
  .loss-form,
  .records-card { padding: 14px; }
  .line-row { padding: 10px; gap: 10px; }
  .form-footer { justify-content: stretch; }
  .form-footer :deep(.ui-button),
  .loss-toolbar :deep(.ui-button) { width: 100%; min-width: 0; }
  .picker-backdrop {
    align-items: end;
    padding: 0;
  }
  .item-picker-dialog {
    width: 100vw;
    max-height: 88vh;
    border-radius: 12px 12px 0 0;
  }
  .detail-backdrop,
  .image-preview-backdrop { padding: 12px; }
  .detail-dialog,
  .image-preview-dialog { width: calc(100vw - 24px); max-width: calc(100vw - 24px); max-height: calc(100vh - 24px); }
  .photo-grid--large button { width: 92px; height: 78px; }
}

@media (max-width: 430px) {
  .loss-toolbar { grid-template-columns: 1fr; }
  .toolbar-export { grid-column: 1; justify-self: stretch; }
  .loss-summary { grid-template-columns: 1fr; }
  .archive-summary header { display: grid; }
  .selected-preview-grid figure { width: 74px; height: 62px; }
}
</style>
