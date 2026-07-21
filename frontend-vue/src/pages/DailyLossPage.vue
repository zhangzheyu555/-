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
  Info,
  PackageMinus,
  Plus,
  RefreshCw,
  Search,
  Send,
  Trash2,
  X,
} from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import BusinessScopeBar from '../components/common/BusinessScopeBar.vue'
import UiButton from '../components/ui/UiButton.vue'
import {
  downloadMonthlyDailyLossExcel,
  fetchDailyLossAttachment,
  getDailyLossItems,
  getDailyLossReports,
  reviewDailyLossReport,
  saveDailyLossReport,
  submitDailyLossReport,
  uploadDailyLossReportAttachments,
  type DailyLossAttachment,
  type DailyLossItem,
  type DailyLossReport,
} from '../api/dailyLoss'
import { getStores, type StoreInfo } from '../api/operations'
import { useBusinessScope } from '../composables/useBusinessScope'
import { PERMISSIONS } from '../permissions/permissions'
import { isBossRole, normalizeRoleCode } from '../permissions/roles'
import { useAuthStore } from '../stores/auth'

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
const selectedStoreId = ref('')
const selectedMonth = ref(currentMonth())
const loading = ref(true)
const refreshing = ref(false)
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
const formRef = ref<HTMLElement | null>(null)
const pickerSearchRef = ref<HTMLInputElement | null>(null)
const pickerOpen = ref(false)
const pickerLineIndex = ref(0)
const pickerQuery = ref('')
const pickerCategory = ref(ALL_CATEGORY)
const pickerActiveIndex = ref(0)
const recentItemIds = ref<number[]>([])
const photoControllers = new Map<string, AbortController>()

const canSubmit = computed(() => auth.hasPermission(PERMISSIONS.DAILY_LOSS_CREATE)
  && (isBossRole(auth.role) || normalizeRoleCode(auth.role) === 'STORE_MANAGER'))
const canReview = computed(() => auth.hasPermission(PERMISSIONS.DAILY_LOSS_REVIEW)
  && (isBossRole(auth.role) || normalizeRoleCode(auth.role) === 'SUPERVISOR'))
const storeScope = computed(() => normalizeRoleCode(auth.role) === 'FINANCE'
  ? (auth.dataScope('FINANCE') || auth.dataScope('STORE'))
  : (auth.dataScope('STORE') || auth.dataScope('WAREHOUSE')))
const accessibleStores = computed(() => {
  if (scope.isStoreManager.value) {
    return stores.value.filter((store) => store.id === scope.boundStoreId.value)
  }
  if (storeScope.value?.mode === 'ALL') return stores.value
  const storeIds = storeScope.value?.storeIds || []
  if (storeIds.length) return stores.value.filter((store) => storeIds.includes(store.id))
  return stores.value
})
const effectiveStoreId = computed(() => scope.scopedStoreId(selectedStoreId.value))
const canSelectAllStores = computed(() => !scope.isStoreManager.value && storeScope.value?.mode === 'ALL')
const canExport = computed(() => auth.hasPermission(PERMISSIONS.DAILY_LOSS_EXPORT)
  && (isBossRole(auth.role) || normalizeRoleCode(auth.role) === 'FINANCE'))
const notReportedCount = computed(() => reports.value.filter((report) => statusKey(report) === 'NOT_REPORTED').length)
const pendingCount = computed(() => reports.value.filter((report) => statusKey(report) === 'SUBMITTED').length)
const reviewedCount = computed(() => reports.value.filter((report) => ['REVIEWED', 'APPROVED'].includes(statusKey(report))).length)
const todayReport = computed(() => reports.value.find((report) => report.lossDate === localDate()))
const itemsById = computed(() => new Map(items.value.map((item) => [Number(item.id), item])))
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
  const keyword = pickerQuery.value.trim().toLowerCase()
  if (!keyword) return rows
  return rows.filter((item) => [
    itemLabel(item),
    item.itemCode || item.code || '',
    itemCategoryName(item),
    itemUnit(item),
  ].some((value) => value.toLowerCase().includes(keyword)))
})

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
  if (!loading.value) void refreshData()
})

watch([pickerQuery, pickerCategory], () => {
  pickerActiveIndex.value = 0
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
    await refreshData()
  } catch (error) {
    pageError.value = readableError(error, '每日报损暂时无法读取，请稍后刷新。')
  } finally {
    loading.value = false
  }
}

async function refreshData() {
  if (!effectiveStoreId.value && !canSelectAllStores.value) {
    items.value = []
    reports.value = []
    return
  }
  refreshing.value = true
  pageError.value = ''
  try {
    const [itemRows, reportRows] = await Promise.all([
      getDailyLossItems(),
      getDailyLossReports({ storeId: effectiveStoreId.value, month: selectedMonth.value }),
    ])
    items.value = itemRows
    reports.value = reportRows
    openRequestedReport(reportRows)
    await loadRemoteImages(reportRows)
  } catch (error) {
    pageError.value = readableError(error, '报损数据读取失败，请刷新后重试。')
  } finally {
    refreshing.value = false
  }
}

function addLine() {
  lines.value.push(emptyLine())
}

function removeLine(index: number) {
  lines.value.splice(index, 1)
  if (!lines.value.length) lines.value.push(emptyLine())
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

function itemCategoryCode(item: DailyLossItem) {
  return item.categoryCode || item.categoryName || item.category || '其他'
}

function selectedItem(line: LossLineForm) {
  return itemsById.value.get(Number(line.itemConfigId))
}

function selectedLineUnit(line: LossLineForm) {
  return itemUnit(selectedItem(line))
}

function openItemPicker(index: number) {
  if (!items.value.length) {
    pageError.value = '暂无报损品类，请先导入报损单价配置'
    return
  }
  pickerLineIndex.value = index
  pickerQuery.value = ''
  pickerOpen.value = true
  pickerActiveIndex.value = 0
  void nextTick(() => pickerSearchRef.value?.focus())
}

function closeItemPicker() {
  pickerOpen.value = false
}

function setPickerCategory(code: string) {
  pickerCategory.value = code
}

function selectPickerItem(item: DailyLossItem) {
  const line = lines.value[pickerLineIndex.value]
  if (!line) return
  line.itemConfigId = String(item.id)
  const id = Number(item.id)
  recentItemIds.value = [id, ...recentItemIds.value.filter((recentId) => recentId !== id)].slice(0, 8)
  closeItemPicker()
}

function handlePickerKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    closeItemPicker()
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Enter'].includes(event.key)) return
  const rows = pickerItems.value
  if (!rows.length) return
  event.preventDefault()
  if (event.key === 'ArrowDown') {
    pickerActiveIndex.value = (pickerActiveIndex.value + 1) % rows.length
  } else if (event.key === 'ArrowUp') {
    pickerActiveIndex.value = (pickerActiveIndex.value - 1 + rows.length) % rows.length
  } else {
    selectPickerItem(rows[pickerActiveIndex.value])
  }
}

function applyQuickReason(line: LossLineForm, reason: string) {
  line.reason = reason
}

function onFilesChanged(event: Event) {
  const input = event.target as HTMLInputElement
  const nextFiles = Array.from(input.files || []).filter((file) => file.type.startsWith('image/')).slice(0, 12)
  input.value = ''
  releaseSelectedPreviews()
  selectedFiles.value = nextFiles
  selectedFilePreviews.value = Object.fromEntries(nextFiles.map((file) => [file.name, URL.createObjectURL(file)]))
}

function removeSelectedFile(file: File) {
  const previewUrl = selectedFilePreviews.value[file.name]
  if (previewUrl) URL.revokeObjectURL(previewUrl)
  selectedFiles.value = selectedFiles.value.filter((current) => current !== file)
  const next = { ...selectedFilePreviews.value }
  delete next[file.name]
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
    })
    if (selectedFiles.value.length && saved.id) {
      await uploadDailyLossReportAttachments(saved.id, selectedFiles.value, (percent) => { uploadProgress.value = percent })
    }
    if (saved.id) await submitDailyLossReport(saved.id)
    actionMessage.value = '今日报损已提交，等待督导复核。'
    lines.value = [emptyLine()]
    selectedFiles.value = []
    releaseSelectedPreviews()
    await refreshData()
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
    actionMessage.value = `${report.storeName || report.storeId} ${report.lossDate} 的报损已复核。`
    await refreshData()
  } catch (error) {
    pageError.value = readableError(error, '复核失败，请刷新记录后重试。')
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
  const url = selectedFilePreviews.value[file.name]
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
  if (requested && accessibleStores.value.some((store) => store.id === requested)) return requested
  return canSelectAllStores.value ? '' : accessibleStores.value[0]?.id || ''
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
    <PageHeader
      title="每日报损"
      subtitle="店长每日提交，督导按门店和日期复核；可按当前范围导出本月报损 Excel。"
    >
      <template #actions>
        <UiButton :loading="refreshing" @click="refreshData">
          <template #icon><RefreshCw :size="16" /></template>
          刷新
        </UiButton>
      </template>
    </PageHeader>

    <div v-if="pageError" class="message message--error" role="alert">{{ pageError }}</div>
    <div v-if="actionMessage" class="message message--success" role="status">{{ actionMessage }}</div>

    <section class="loss-toolbar content-card" aria-label="报损筛选">
      <BusinessScopeBar v-if="scope.isStoreManager.value" />
      <label v-else class="toolbar-field">
        <span>门店</span>
        <select v-model="selectedStoreId" :disabled="loading || refreshing">
          <option v-if="canSelectAllStores" value="">全部门店</option>
          <option v-else value="" disabled>请选择门店</option>
          <option v-for="store in accessibleStores" :key="store.id" :value="store.id">
            {{ store.brandName ? `${store.brandName} · ` : '' }}{{ store.name || store.id }}
          </option>
        </select>
      </label>
      <label class="toolbar-field toolbar-field--month">
        <span>月份</span>
        <input v-model="selectedMonth" type="month" :disabled="loading || refreshing" />
      </label>
      <UiButton
        v-if="canExport"
        :loading="exporting"
        :disabled="(!effectiveStoreId && !canSelectAllStores) || loading || refreshing"
        @click="exportMonthlyExcel"
      >
        <template #icon><Download :size="16" /></template>
        {{ exporting ? '正在生成 Excel…' : '导出本月报损 Excel' }}
      </UiButton>
      <div class="toolbar-note" title="页面不录入单价，后端按配置快照计算金额。">
        <Info :size="16" />
        <span>单价与金额由后端按配置快照核算，页面不录入单价。</span>
      </div>
    </section>

    <div class="loss-summary" aria-label="报损汇总">
      <article><span>未报</span><strong>{{ notReportedCount }}</strong><small>本月日期状态</small></article>
      <article><span>待复核</span><strong>{{ pendingCount }}</strong><small>等待督导确认</small></article>
      <article><span>已复核</span><strong>{{ reviewedCount }}</strong><small>已完成闭环</small></article>
      <article><span>今日状态</span><strong>{{ todayReport ? statusLabel(todayReport) : '未报' }}</strong><small>{{ localDate() }}</small></article>
    </div>

    <form v-if="canSubmit" ref="formRef" class="content-card loss-form" @submit.prevent="submitReport">
      <div class="section-heading">
        <PackageMinus :size="20" />
        <div>
          <h2>今日报损</h2>
          <p>一次提交多个品类，照片先上传到后端附件库；门店不填写单价。</p>
        </div>
      </div>

      <div v-if="!refreshing && !items.length" class="empty-config-alert" role="status">
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

      <section class="photo-upload-block" aria-label="报损照片上传">
        <label class="attachment-field">
          <span><FileUp :size="15" />报损照片</span>
          <span class="file-picker-ui">
            <span class="file-picker-button">选择照片</span>
            <em>{{ selectedFiles.length ? `已选择 ${selectedFiles.length} 张` : '未选择照片' }}</em>
          </span>
          <input type="file" multiple accept="image/*" @change="onFilesChanged" />
          <small v-if="selectedFiles.length">已选择 {{ selectedFiles.length }} 张照片<span v-if="submitting && uploadProgress">，上传 {{ uploadProgress }}%</span></small>
          <small v-else>提交前至少上传一张报损现场照片。</small>
        </label>
        <div v-if="selectedFiles.length" class="selected-preview-grid">
          <figure v-for="file in selectedFiles" :key="file.name">
            <button type="button" :aria-label="`预览 ${file.name}`" @click="openSelectedPreview(file)">
              <img :src="selectedFilePreviews[file.name]" :alt="`${file.name} 缩略图`" />
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

    <section class="content-card records-card" aria-label="每日报损列表">
      <div class="records-toolbar">
        <div>
          <h2>每日报损列表</h2>
          <p>按门店和日期显示未报、待复核、已复核；点击记录查看明细和照片。</p>
        </div>
      </div>
      <div v-if="loading || refreshing" class="empty-state">正在读取报损记录...</div>
      <ol v-else class="record-list">
        <li
          v-for="report in reports"
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
              {{ report.detailCount || 0 }} 项明细 · 照片 {{ report.attachmentCount || 0 }} 张
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
  </section>

  <Teleport to="body">
    <div v-if="pickerOpen" class="picker-backdrop" @click.self="closeItemPicker">
      <section class="item-picker-dialog" role="dialog" aria-modal="true" aria-label="选择报损品类" tabindex="-1" @keydown="handlePickerKeydown">
        <header>
          <div>
            <h2>选择品类</h2>
            <p>按分类和关键词快速定位，页面不显示单价。</p>
          </div>
          <UiButton variant="ghost" icon-only aria-label="关闭品类选择" title="关闭" @click="closeItemPicker">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>
        <label class="picker-search">
          <Search :size="16" />
          <input ref="pickerSearchRef" v-model="pickerQuery" type="search" placeholder="搜索品类名称、编码或单位" />
        </label>
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
        <div v-if="pickerItems.length" class="picker-grid" role="listbox" aria-label="可选报损品类">
          <button
            v-for="(item, index) in pickerItems"
            :key="item.id"
            type="button"
            class="picker-card"
            :class="{ active: pickerActiveIndex === index }"
            role="option"
            :aria-selected="pickerActiveIndex === index"
            @mouseenter="pickerActiveIndex = index"
            @click="selectPickerItem(item)"
          >
            <b>{{ itemLabel(item) }}</b>
            <span>{{ itemUnit(item) }}</span>
            <small>{{ itemCategoryName(item) }}</small>
          </button>
        </div>
        <div v-else class="empty-state">没有匹配的报损品类，请调整搜索词或分类。</div>
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
          <section>
            <h3>报损明细</h3>
            <div class="detail-list detail-list--dialog">
              <span v-for="detail in detailReport.details || []" :key="detail.id">
                {{ detail.itemName }} {{ detail.lossQuantity }}{{ detail.unit || '' }}<template v-if="detail.lossReason"> · {{ detail.lossReason }}</template>
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
</template>

<style scoped>
.daily-loss-page { display: grid; gap: 16px; min-width: 0; }
.message { padding: 11px 13px; border-radius: 8px; font-weight: 650; font-size: 14px; }
.message--error { color: #9f2734; border: 1px solid #efc9cf; background: #fff5f5; }
.message--success { color: #185c48; border: 1px solid #bde4d5; background: #f0fbf6; }

.loss-toolbar {
  display: flex;
  min-height: 64px;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  flex-wrap: wrap;
}

.toolbar-field {
  display: grid;
  min-width: 190px;
  gap: 4px;
  color: var(--ds-muted);
  font-weight: 700;
  font-size: 12px;
}

.toolbar-field--month { min-width: 176px; }
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

.toolbar-note {
  display: inline-flex;
  min-width: 220px;
  max-width: 460px;
  align-items: center;
  gap: 7px;
  color: #31645e;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.45;
}

.toolbar-note svg { flex: none; color: var(--ds-primary-hover); }

.loss-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.loss-summary article { display: grid; gap: 4px; padding: 15px 16px; border: 1px solid var(--ds-line); border-radius: 8px; background: var(--ds-surface); }
.loss-summary span, .loss-summary small { color: var(--ds-muted); font-size: 12px; }
.loss-summary strong { color: var(--ds-ink); font-size: 22px; line-height: 1.15; word-break: break-word; }

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
.picker-search {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 0 10px;
  border: 1px solid var(--ds-line-strong);
  border-radius: 7px;
  background: #fff;
  color: var(--ds-muted);
}

.picker-search input {
  width: 100%;
  min-height: 38px;
  border: 0;
  box-shadow: none;
}

.category-tabs {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}

.category-tabs button { flex: 0 0 auto; }
.category-tabs span { margin-left: 6px; color: var(--ds-muted); font-weight: 800; }
.picker-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 10px;
  overflow: auto;
  padding-right: 2px;
}

.picker-card {
  display: grid;
  min-height: 92px;
  align-content: start;
  gap: 7px;
  padding: 12px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
  text-align: left;
}

.picker-card:hover,
.picker-card.active {
  border-color: var(--ds-primary);
  background: #f7fcfb;
}

.picker-card b {
  overflow: hidden;
  color: var(--ds-ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker-card span,
.picker-card small { color: var(--ds-muted); font-size: 12px; }
.detail-body { display: grid; gap: 16px; overflow: auto; }
.detail-body section { display: grid; gap: 8px; }
.detail-list--dialog span { border-radius: 6px; }

.image-preview-backdrop { z-index: var(--ds-z-preview); background: rgba(9, 14, 14, .72); }
.image-preview-dialog { display: grid; max-width: min(960px, calc(100vw - 48px)); max-height: calc(100vh - 48px); overflow: hidden; border-radius: 8px; background: #fff; }
.image-preview-dialog header { padding: 10px 12px; border-bottom: 1px solid var(--ds-line); }
.image-preview-dialog header b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.image-preview-dialog > img { display: block; max-width: 100%; max-height: calc(100vh - 102px); margin: auto; object-fit: contain; }

@media (max-width: 1080px) {
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
  .loss-toolbar :deep(.business-scope-error),
  .toolbar-note {
    grid-column: 1 / -1;
    width: 100%;
    max-width: none;
  }
  .toolbar-field,
  .toolbar-field--month {
    min-width: 0;
  }
  .loss-summary { grid-template-columns: 1fr 1fr; gap: 8px; }
  .loss-summary article { padding: 12px; }
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
  .loss-summary { grid-template-columns: 1fr; }
  .picker-grid { grid-template-columns: 1fr; }
  .selected-preview-grid figure { width: 74px; height: 62px; }
}
</style>
