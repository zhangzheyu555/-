<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { AlertTriangle, CheckCircle2, Download, ImagePlus, Link2, LoaderCircle, Plus, RefreshCw, RotateCw, Sparkles, Trash2, Upload, XCircle } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'
import InspectionHistoricalEvidenceDialog from '../components/inspection/InspectionHistoricalEvidenceDialog.vue'
import {
  confirmInspectionDetection,
  confirmInspectionDetectionSuggestion,
  createInspectionRecord,
  detectInspectionPhoto,
  downloadInspectionExcel,
  fetchInspectionAttachment,
  getInspectionEvidenceAttachments,
  getInspectionRecord,
  getInspectionRecords,
  getInspectionServiceHealth,
  getInspectionStandard,
  revokeInspectionDetection,
  uploadInspectionAttachment,
  type InspectionAttachment,
  type InspectionCategoryCode,
  type InspectionDetectionItem,
  type InspectionDetectionResult,
  type InspectionEvidenceCandidate,
  type InspectionEvidenceLinkResponse,
  type InspectionItemResult,
  type InspectionRecord,
  type InspectionServiceHealth,
} from '../api/inspection'
import { getBrands, getStores, type BrandInfo, type StoreInfo } from '../api/operations'
import { PERMISSIONS } from '../permissions/permissions'
import { canAccessRoles } from '../permissions/roles'
import { useAuthStore } from '../stores/auth'
import {
  emptyInspectionStandard,
  getInspectionDimensions,
  inspectionStandardStats,
  toInspectionStandardSet,
  type InspectionStandardClause,
  type InspectionStandardSet,
} from '../data/inspectionStandards'
import { formatScore, inspectionScoreView, INSPECTION_MAX_SCORE, INSPECTION_PASS_SCORE } from '../utils/inspectionScore'

type InspectionTab = 'records' | 'create' | 'standards'

interface BrandOption {
  name: string
  color: string
  soft: string
}

interface DraftPhoto {
  attachmentId?: number
  /** Historical photosJson position, supplied by the backend-safe detail payload. */
  sourcePhotoIndex?: number
  fileName: string
  sourceFile?: File
  url?: string
  previewUrl?: string
  contentType?: string
  fileSize?: number
  detectionStatus: 'detecting' | 'success' | 'failed'
  detectionError?: string
  detection?: InspectionDetectionResult
  reviewStatus: 'pending' | 'accepted' | 'dismissed'
  modelLinkedClauseId?: number
  modelAddedPhotoLink?: boolean
}

type PersistedDraftPhoto = DraftPhoto & { attachmentId: number }

interface DeductionDetail {
  id?: string
  standardId?: string
  standardTitle?: string
  standardDescription?: string
  suggestedScore?: number
  dim?: string
  categoryCode?: InspectionCategoryCode
  code?: string
  item?: string
  issue?: string
  deduct?: number
  requirement?: string
  redline?: boolean
  method?: string
  sourceImageId?: string
  modelAssisted?: boolean
  modelConfidence?: number
  photoAttachmentIds?: number[]
  responsiblePerson?: string
  rectificationDeadline?: string
  rectificationStatus?: string
  reviewResult?: string
}

interface DeductionForm {
  dimension: string
  clauseKey: string
  manualItem: string
  deduct: number | null
  issue: string
}

type DetailPhotoLoadStatus = 'loading' | 'ready' | 'forbidden' | 'missing' | 'failed'

interface DetailPhotoPreview {
  status: DetailPhotoLoadStatus
  url?: string
  message?: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const canonicalBrandNames = ['茹菓', '霸王茶姬', '瑞幸咖啡']
const brandPalette: Record<string, { color: string; soft: string }> = {
  茹菓: { color: '#76bdb8', soft: '#e9f6f5' },
  霸王茶姬: { color: '#9c2f3e', soft: '#fff0f2' },
  瑞幸咖啡: { color: '#2458c7', soft: '#edf4ff' },
}

const activeTab = ref<InspectionTab>('records')
const records = ref<InspectionRecord[]>([])
const stores = ref<StoreInfo[]>([])
const backendBrands = ref<BrandInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const draftReviewBusyKeys = ref<string[]>([])
const persistedDecisionBusyKeys = ref<string[]>([])
const draftReviewTimers = new Map<string, number>()
const exportingRecordId = ref('')
const uploading = ref(false)
const checkingDetectionService = ref(false)
const refreshingStandard = ref(false)
const detectionService = ref<InspectionServiceHealth | null>(null)
const errorMessage = ref('')
const actionMessage = ref('')
const selectedRecordId = ref('')
const detailRecord = ref<InspectionRecord | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
let detailRequestSequence = 0
const detailPhotoPreviews = ref<Record<string, DetailPhotoPreview>>({})
const detailPhotoPreview = ref<{ url: string; fileName: string } | null>(null)
const detailPhotoControllers = new Map<string, AbortController>()
const detailPhotoPreviewDialog = ref<HTMLElement | null>(null)
let detailPhotoPreviewTrigger: HTMLElement | null = null
let detailPhotoPreviewGeneration = 0
// Metadata only. The cache is keyed exclusively by the server-provided photosJson
// index; never infer an evidence state from a display name or an attachment id.
const detailEvidenceCandidatesByPhotoIndex = ref<Record<string, InspectionEvidenceCandidate>>({})
const detailEvidenceCandidatesLoading = ref(false)
const historicalEvidenceDialog = ref<{
  attachmentId?: number
  sourcePhotoIndex?: number
  mode: 'existing' | 'upload'
} | null>(null)
const filterBrand = ref('')
const filterMonth = ref('')
const standardDimension = ref('')
const inspectionStandard = ref<InspectionStandardSet>(emptyInspectionStandard)

const canManageInspection = computed(() => auth.hasPermission(PERMISSIONS.INSPECTION_MANAGE))
// The server remains authoritative. This only prevents a non-manager from seeing an
// action they cannot complete; SUPERVISOR is normalized to the local OPERATIONS role.
const canSupplementHistoricalEvidence = computed(() => (
  canManageInspection.value && canAccessRoles(auth.role, ['BOSS', 'SUPERVISOR'])
))
const tabs = computed<Array<{ id: InspectionTab; label: string; to: string }>>(() => [
  { id: 'records', label: '巡检记录', to: '/operations/inspection/records' },
  ...(canManageInspection.value
    ? [
        { id: 'create' as const, label: '发起巡检', to: '/operations/inspection/tasks' },
        { id: 'standards' as const, label: '稽核标准', to: '/operations/inspection/standards' },
      ]
    : []),
])

const draft = reactive({
  standardVersionId: undefined as number | undefined,
  standardVersion: '',
  brandName: '茹菓',
  storeId: '',
  inspectionDate: todayDate(),
  inspector: '',
  fullScore: INSPECTION_MAX_SCORE,
  note: '',
  photos: [] as DraftPhoto[],
  deductions: [] as DeductionDetail[],
  redlines: [] as DeductionDetail[],
  itemResults: [] as InspectionItemResult[],
})

const deductionForm = reactive<DeductionForm>({
  dimension: '',
  clauseKey: '',
  manualItem: '',
  deduct: null,
  issue: '',
})

const brandOptions = computed<BrandOption[]>(() => {
  const names = new Set<string>()
  canonicalBrandNames.forEach((name) => names.add(name))
  backendBrands.value.forEach((brand) => names.add(normalizeBrandName(brand.name)))
  stores.value.forEach((store) => names.add(normalizeBrandName(store.brandName)))
  records.value.forEach((record) => names.add(recordBrandName(record)))
  return Array.from(names)
    .filter((name) => canonicalBrandNames.includes(name))
    .sort((a, b) => canonicalBrandNames.indexOf(a) - canonicalBrandNames.indexOf(b))
    .map((name) => ({ name, ...(brandPalette[name] || { color: '#64748b', soft: '#f1f5f9' }) }))
})

const monthOptions = computed(() => {
  const months = new Set<string>()
  records.value.forEach((record) => {
    const month = (record.inspectionDate || '').slice(0, 7)
    if (month) months.add(month)
  })
  return Array.from(months).sort().reverse()
})

const allVisibleRecords = computed(() => records.value.slice().sort(compareInspectionRecord))

const filteredRecords = computed(() => {
  return allVisibleRecords.value.filter((record) => {
    const brandMatch = !filterBrand.value || recordBrandName(record) === filterBrand.value
    const monthMatch = !filterMonth.value || (record.inspectionDate || '').startsWith(filterMonth.value)
    return brandMatch && monthMatch
  })
})

const summary = computed(() => {
  const source = allVisibleRecords.value
  const total = source.length
  const thisMonth = currentMonth()
  const monthCount = source.filter((record) => (record.inspectionDate || '').startsWith(thisMonth)).length
  const scoreSource = source.map(inspectionScoreView).filter((item) => item.valid && item.score !== null)
  const averageScore = scoreSource.length
    ? roundScore(scoreSource.reduce((sum, item) => sum + (item.score ?? 0), 0) / scoreSource.length)
    : null
  const invalidScoreCount = source.length - scoreSource.length
  const redlineCount = source.filter((record) => recordRedLineCount(record) > 0).length
  return { total, monthCount, averageScore, invalidScoreCount, redlineCount }
})

const createStoreOptions = computed(() => {
  return stores.value
    .filter((store) => normalizeBrandName(store.brandName) === draft.brandName)
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
})

const selectedRecord = computed(() => {
  if (!selectedRecordId.value) return null
  return detailRecord.value && String(detailRecord.value.id) === selectedRecordId.value
    ? detailRecord.value
    : null
})

const globalStandard = computed(() => inspectionStandard.value)
const globalStandardStats = computed(() => inspectionStandardStats(globalStandard.value))
const draftDimensions = computed(() => getInspectionDimensions(globalStandard.value))
const clausesForDimension = computed(() => {
  return globalStandard.value.groups.find((group) => group.dim === deductionForm.dimension)?.items || []
})
const selectedClause = computed(() => clausesForDimension.value[Number(deductionForm.clauseKey)] || null)
const hasGlobalStandard = computed(() => globalStandard.value.groups.length > 0 || globalStandard.value.redlines.length > 0)
const standardReady = computed(() => (
  hasGlobalStandard.value
  && globalStandard.value.valid
  && globalStandard.value.saveAllowed
  && !globalStandard.value.validationError
))
const passLine = computed(() => globalStandard.value.passScore || INSPECTION_PASS_SCORE)
const invalidStandardDiagnostics = computed(() => globalStandard.value.diagnostics.filter((item) => !item.valid))

const acceptedModelDeductionByClause = computed(() => {
  const result = new Map<number, number>()
  const seenDetectionKeys = new Set<string>()
  draft.photos.forEach((photo) => {
    if (photo.reviewStatus !== 'accepted' || !photo.detection) return
    const key = detectionKey(photo.detection)
    const clauseId = detectionClauseId(photo.detection)
    const confirmed = Math.abs(safeNumber(photo.detection.confirmedDeduction))
    if (!key || seenDetectionKeys.has(key) || !clauseId || confirmed <= 0) return
    seenDetectionKeys.add(key)
    result.set(clauseId, Math.max(result.get(clauseId) || 0, confirmed))
  })
  return result
})

const categoryScores = computed(() => {
  return globalStandard.value.groups.map((group) => {
    const itemScore = draft.itemResults
      .filter((item) => item.categoryCode === group.categoryCode)
      .reduce((sum, item) => {
        const actualScore = safeNumber(item.actualScore)
        const existingDeduction = Math.max(0, safeNumber(item.standardScore) - actualScore)
        const modelDeduction = acceptedModelDeductionByClause.value.get(item.standardItemId) || 0
        const previewAdjustment = Math.max(0, modelDeduction - existingDeduction)
        // The authoritative rule deduction can exceed one clause's nominal score.
        // Keep the negative clause contribution here and clamp only the category total.
        return sum + actualScore - previewAdjustment
      }, 0)
    const additionalDeduction = draft.deductions
      .filter((item) => (item.categoryCode || categoryCodeForDimension(item.dim)) === group.categoryCode && !item.standardId)
      .reduce((sum, item) => sum + safeNumber(item.deduct), 0)
    return {
      code: group.categoryCode,
      name: group.dim,
      fullScore: group.fullScore,
      score: Math.max(0, roundScore(itemScore - additionalDeduction)),
    }
  })
})

const manualCurrentScore = computed(() => roundScore(globalStandard.value.groups.reduce((sum, group) => {
  const itemScore = draft.itemResults
    .filter((item) => item.categoryCode === group.categoryCode)
    .reduce((categorySum, item) => categorySum + safeNumber(item.actualScore), 0)
  const additionalDeduction = draft.deductions
    .filter((item) => (item.categoryCode || categoryCodeForDimension(item.dim)) === group.categoryCode && !item.standardId)
    .reduce((categorySum, item) => categorySum + safeNumber(item.deduct), 0)
  return sum + Math.max(0, itemScore - additionalDeduction)
}, 0)))

const currentScore = computed(() => roundScore(categoryScores.value.reduce((sum, category) => sum + category.score, 0)))
const deductionTotal = computed(() => Math.max(0, roundScore(INSPECTION_MAX_SCORE - currentScore.value)))
const redLineHit = computed(() => draft.itemResults.some((item) => (
  item.riskLevel === 'RED' && Boolean(item.issueFound)
)) || draft.redlines.length > 0)
const yellowRiskCount = computed(() => draft.itemResults.filter((item) => (
  item.riskLevel === 'YELLOW' && safeNumber(item.actualScore) < safeNumber(item.standardScore)
)).length)
const detectingPhotoCount = computed(() => draft.photos.filter((photo) => photo.detectionStatus === 'detecting').length)
const failedPhotoCount = computed(() => draft.photos.filter((photo) => photo.detectionStatus === 'failed').length)
const pendingReviewCount = computed(() => draft.photos.filter((photo) => photo.detectionStatus === 'success' && photo.reviewStatus === 'pending').length)
const recognitionReady = computed(() => (
  draft.photos.length > 0
  && detectingPhotoCount.value === 0
  && failedPhotoCount.value === 0
  && pendingReviewCount.value === 0
))
const scoreDisplay = computed(() => recognitionReady.value ? String(currentScore.value) : '—')
const inspectionResultText = computed(() => {
  if (!draft.photos.length || detectingPhotoCount.value > 0) return '待识别'
  if (failedPhotoCount.value > 0) return '识别服务不可用'
  if (pendingReviewCount.value > 0) return '待人工确认'
  if (redLineHit.value) return '不合格（命中红线）'
  return currentScore.value >= passLine.value ? '合格' : '不合格'
})
const scoreTone = computed(() => {
  if (!recognitionReady.value) return 'pending'
  if (redLineHit.value || currentScore.value < passLine.value) return 'bad'
  if (yellowRiskCount.value > 0) return 'warn'
  return 'good'
})
const detectionServiceUp = computed(() => detectionService.value?.status === 'UP')
const detectionServiceMessage = computed(() => {
  if (checkingDetectionService.value) return '正在检查识别服务...'
  if (detectionServiceUp.value) return '识别服务正常，模型已就绪'
  return detectionService.value?.message || '尚未检查识别服务'
})
const saveBlockedReason = computed(() => {
  if (!standardReady.value) return hasGlobalStandard.value
    ? '当前标准未通过校验，请刷新标准后再保存'
    : '最新巡检标准尚未加载完成'
  if (!draft.itemResults.length) return '最新巡检条款尚未初始化'
  if (!draft.photos.length) return '请先上传现场照片并完成识别'
  if (detectingPhotoCount.value > 0) return `还有 ${detectingPhotoCount.value} 张照片正在识别`
  if (failedPhotoCount.value > 0) return `有 ${failedPhotoCount.value} 张照片识别失败，请重试或移除`
  if (pendingReviewCount.value > 0) return `还有 ${pendingReviewCount.value} 张照片等待督导确认`
  const associatedIds = new Set(draft.itemResults.flatMap((item) => [
    ...item.photoAttachmentIds,
    ...(item.beforePhotoAttachmentIds || []),
    ...(item.afterPhotoAttachmentIds || []),
  ]))
  const unlinkedCount = draft.photos.filter((photo) => !photo.attachmentId || !associatedIds.has(photo.attachmentId)).length
  if (unlinkedCount > 0) return `还有 ${unlinkedCount} 张照片未关联具体检查条款`
  return ''
})

const draftRows = computed(() => [
  ...draft.deductions.map((item, index) => ({ kind: 'deduction' as const, index, item })),
  ...draft.redlines.map((item, index) => ({ kind: 'redline' as const, index, item })),
])

const selectedStandard = computed(() => globalStandard.value)
const selectedStandardStats = computed(() => globalStandardStats.value)
const selectedStandardGroups = computed(() => {
  const groups = selectedStandard.value.groups
  if (!standardDimension.value) return groups
  return groups.filter((group) => group.dim === standardDimension.value)
})

function todayDate() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function currentMonth() {
  return todayDate().slice(0, 7)
}

function normalizeBrandName(name?: string) {
  const value = String(name || '').trim()
  if (!value) return ''
  if (value.includes('茹菓') || value.includes('茹果') || value.includes('苹果')) return '茹菓'
  if (value.includes('霸王')) return '霸王茶姬'
  if (value.includes('瑞幸')) return '瑞幸咖啡'
  return value
}

function safeNumber(value: unknown) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function roundScore(value: number) {
  return Math.round(value * 100) / 100
}

function categoryCodeForDimension(value?: string): InspectionCategoryCode {
  const text = String(value || '')
  if (text.includes('卫生')) return 'HYGIENE'
  if (text.includes('服务')) return 'SERVICE'
  return 'MATERIAL'
}

function itemDeduction(item: InspectionItemResult) {
  const deductionScore = Number(item.deductionScore)
  if (Number.isFinite(deductionScore) && deductionScore >= 0) {
    return roundScore(deductionScore)
  }
  return Math.max(0, roundScore(safeNumber(item.standardScore) - safeNumber(item.actualScore)))
}

function itemNeedsRectification(item: InspectionItemResult) {
  return Boolean(item.issueFound) || itemDeduction(item) > 0 || Boolean(item.deductionReason?.trim())
}

function normalizeItemScore(item: InspectionItemResult) {
  const standardScore = Math.max(0, safeNumber(item.standardScore))
  item.actualScore = Math.max(0, Math.min(standardScore, roundScore(safeNumber(item.actualScore))))
  item.redLineHit = item.riskLevel === 'RED' && Boolean(item.issueFound)
  if (!itemNeedsRectification(item)) {
    item.deductionReason = ''
    item.responsiblePerson = ''
    item.rectificationDeadline = ''
    item.rectificationStatus = '无需整改'
    item.reviewResult = ''
  } else if (!item.rectificationStatus || item.rectificationStatus === '无需整改') {
    item.rectificationStatus = '待整改'
  }
}

function toggleRedLineIssue(item: InspectionItemResult, checked: boolean) {
  item.issueFound = checked
  item.redLineHit = checked
  if (!checked) item.deductionReason = ''
  normalizeItemScore(item)
}

function itemPhotoSelected(item: InspectionItemResult, attachmentId?: number) {
  return Boolean(attachmentId && item.photoAttachmentIds.includes(attachmentId))
}

function draftItemsForCategory(categoryCode: InspectionCategoryCode) {
  return draft.itemResults.filter((item) => item.categoryCode === categoryCode)
}

function toggleItemPhoto(item: InspectionItemResult, attachmentId: number | undefined, checked: boolean) {
  if (!attachmentId) return
  item.photoAttachmentIds = checked
    ? Array.from(new Set([...item.photoAttachmentIds, attachmentId]))
    : item.photoAttachmentIds.filter((id) => id !== attachmentId)
  item.beforePhotoAttachmentIds = [...item.photoAttachmentIds]
}

function itemAfterPhotoSelected(item: InspectionItemResult, attachmentId?: number) {
  return Boolean(attachmentId && item.afterPhotoAttachmentIds?.includes(attachmentId))
}

function toggleAfterPhoto(item: InspectionItemResult, attachmentId: number | undefined, checked: boolean) {
  if (!attachmentId) return
  const current = item.afterPhotoAttachmentIds || []
  item.afterPhotoAttachmentIds = checked
    ? Array.from(new Set([...current, attachmentId]))
    : current.filter((id) => id !== attachmentId)
}

function recordStore(record: InspectionRecord) {
  return stores.value.find((store) => store.id === record.storeId)
}

function recordBrandName(record: InspectionRecord) {
  return normalizeBrandName(record.brandName || record.brand || recordStore(record)?.brandName)
}

function brandMeta(name?: string) {
  const normalized = normalizeBrandName(name)
  return brandPalette[normalized] || { color: '#64748b', soft: '#f1f5f9' }
}

function compareInspectionRecord(a: InspectionRecord, b: InspectionRecord) {
  return (b.inspectionDate || '').localeCompare(a.inspectionDate || '') || String(b.id || '').localeCompare(String(a.id || ''))
}

function tabFromRoute(): InspectionTab {
  const tab = Array.isArray(route.query.tab) ? route.query.tab[0] : route.query.tab
  const metaTab = route.meta.inspectionTab
  const raw = String(tab || metaTab || '').toLowerCase()
  if (canManageInspection.value && ['create', 'tasks', 'new'].includes(raw)) return 'create'
  if (canManageInspection.value && ['standards', 'standard', 'rules', 'std'].includes(raw)) return 'standards'
  if (canManageInspection.value && (route.path.includes('/tasks') || route.path.includes('/create'))) return 'create'
  if (canManageInspection.value && (route.path.includes('/rules') || route.path.includes('/standards'))) return 'standards'
  return 'records'
}

function applyRouteState() {
  activeTab.value = tabFromRoute()
  const brand = Array.isArray(route.query.brand) ? route.query.brand[0] : route.query.brand
  const month = Array.isArray(route.query.month) ? route.query.month[0] : route.query.month
  filterBrand.value = brand ? normalizeBrandName(String(brand)) : ''
  filterMonth.value = month ? String(month) : ''
  const recordId = Array.isArray(route.query.recordId) ? route.query.recordId[0] : route.query.recordId
  selectedRecordId.value = typeof recordId === 'string' ? recordId : ''
}

function switchTab(tab: InspectionTab) {
  actionMessage.value = ''
  errorMessage.value = ''
  const target = tabs.value.find((item) => item.id === tab)
  if (!target) return
  const query: Record<string, string> = {}
  if (tab === 'records') {
    if (filterBrand.value) query.brand = filterBrand.value
    if (filterMonth.value) query.month = filterMonth.value
  }
  void router.push({ path: target.to, query })
}

function setBrandFilter(brandName: string) {
  filterBrand.value = brandName
  syncRecordQuery()
}

function setMonthFilter(month: string) {
  filterMonth.value = month
  syncRecordQuery()
}

function syncRecordQuery() {
  const query: Record<string, string> = {}
  if (filterBrand.value) query.brand = filterBrand.value
  if (filterMonth.value) query.month = filterMonth.value
  void router.replace({ path: '/operations/inspection/records', query })
}

function openRecordDetail(recordId: string) {
  const query: Record<string, string> = { recordId }
  if (filterBrand.value) query.brand = filterBrand.value
  if (filterMonth.value) query.month = filterMonth.value
  void router.push({ path: '/operations/inspection/records', query })
}

function closeRecordDetail() {
  detailRequestSequence += 1
  releaseDetailPhotoPreviews()
  detailEvidenceCandidatesByPhotoIndex.value = {}
  detailEvidenceCandidatesLoading.value = false
  historicalEvidenceDialog.value = null
  selectedRecordId.value = ''
  detailRecord.value = null
  detailError.value = ''
  detailLoading.value = false
  syncRecordQuery()
}

function openHistoricalEvidenceDialog(photo?: DraftPhoto) {
  if (!selectedRecord.value || !canSupplementHistoricalEvidence.value) return
  historicalEvidenceDialog.value = {
    attachmentId: photo?.attachmentId,
    sourcePhotoIndex: photo?.sourcePhotoIndex,
    mode: photo ? unlinkedEvidenceActionMode(photo) : 'upload',
  }
}

function closeHistoricalEvidenceDialog() {
  historicalEvidenceDialog.value = null
}

async function onHistoricalEvidenceSaved(result: InspectionEvidenceLinkResponse) {
  const recordId = String(result.recordId || selectedRecordId.value)
  historicalEvidenceDialog.value = null
  actionMessage.value = String(result.action).toUpperCase() === 'SUPPLEMENT'
    ? '原图已补传并关联到所选历史条款；历史评分和整改状态未改动。'
    : '已将所选证据关联到历史条款；历史评分和整改状态未改动。'
  if (recordId) await loadSelectedRecord(recordId)
}

async function loadSelectedRecord(recordId: string) {
  const requestSequence = ++detailRequestSequence
  releaseDetailPhotoPreviews()
  detailEvidenceCandidatesByPhotoIndex.value = {}
  detailEvidenceCandidatesLoading.value = false
  detailRecord.value = null
  detailError.value = ''
  detailLoading.value = true
  try {
    const record = await getInspectionRecord(recordId)
    if (requestSequence !== detailRequestSequence || selectedRecordId.value !== recordId) return
    detailRecord.value = record
    void loadDetailPhotoPreviews(record)
    void loadDetailEvidenceCandidateStatuses(record, requestSequence)
  } catch (error) {
    if (requestSequence !== detailRequestSequence || selectedRecordId.value !== recordId) return
    detailError.value = friendlyError(error, '巡检详情没有读取成功，请稍后再试。')
  } finally {
    if (requestSequence === detailRequestSequence) detailLoading.value = false
  }
}

async function loadDetailEvidenceCandidateStatuses(record: InspectionRecord, requestSequence: number) {
  if (!canSupplementHistoricalEvidence.value) return
  detailEvidenceCandidatesLoading.value = true
  try {
    const response = await getInspectionEvidenceAttachments(String(record.id))
    if (requestSequence !== detailRequestSequence || selectedRecordId.value !== String(record.id)) return
    detailEvidenceCandidatesByPhotoIndex.value = Object.fromEntries(
      response.candidates
        .filter((candidate) => Number.isInteger(candidate.photoIndex) && Number(candidate.photoIndex) >= 0)
        .map((candidate) => [String(candidate.photoIndex), candidate]),
    )
  } catch {
    // The detail view remains usable without this optional metadata. Do not turn a
    // candidate lookup failure into an assertion that an original image exists.
    if (requestSequence === detailRequestSequence && selectedRecordId.value === String(record.id)) {
      detailEvidenceCandidatesByPhotoIndex.value = {}
    }
  } finally {
    if (requestSequence === detailRequestSequence && selectedRecordId.value === String(record.id)) {
      detailEvidenceCandidatesLoading.value = false
    }
  }
}

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [recordData, brandData, storeData, standardData] = await Promise.all([
      getInspectionRecords(),
      getBrands().catch(() => [] as BrandInfo[]),
      getStores().catch(() => [] as StoreInfo[]),
      getInspectionStandard().catch(() => null),
    ])
    records.value = recordData
    backendBrands.value = brandData
    stores.value = storeData
    inspectionStandard.value = toInspectionStandardSet(standardData)
    ensureDraftStore()
    ensureDeductionForm()
    ensureDraftItemResults()
    if (selectedRecordId.value && !detailLoading.value) await loadSelectedRecord(selectedRecordId.value)
  } catch (error) {
    errorMessage.value = friendlyError(error, '巡检记录没有读取成功，请稍后再试。')
  } finally {
    loading.value = false
  }
}

function ensureDraftStore() {
  if (!brandOptions.value.find((brand) => brand.name === draft.brandName)) {
    draft.brandName = brandOptions.value[0]?.name || '茹菓'
  }
  const options = createStoreOptions.value
  if (!options.find((store) => store.id === draft.storeId)) {
    draft.storeId = options[0]?.id || ''
  }
}

function ensureDeductionForm() {
  const dimensions = draftDimensions.value
  if (!dimensions.includes(deductionForm.dimension)) {
    deductionForm.dimension = dimensions[0] || ''
  }
  const clauses = clausesForDimension.value
  if (clauses.length) {
    const clauseIndex = Number(deductionForm.clauseKey)
    if (!Number.isInteger(clauseIndex) || clauseIndex < 0 || clauseIndex >= clauses.length) {
      deductionForm.clauseKey = '0'
    }
    fillDeductionFromClause()
  } else {
    deductionForm.clauseKey = ''
    if (!deductionForm.deduct || deductionForm.deduct <= 0) deductionForm.deduct = 1
  }
}

async function refreshStandard() {
  refreshingStandard.value = true
  errorMessage.value = ''
  actionMessage.value = ''
  try {
    inspectionStandard.value = toInspectionStandardSet(await getInspectionStandard())
    ensureDeductionForm()
    ensureDraftItemResults(true)
    actionMessage.value = standardReady.value
      ? `已刷新到有效标准 ${globalStandard.value.version || ''}，共 ${globalStandardStats.value.clauseCount} 条。`
      : `已刷新标准，当前仍有 ${invalidStandardDiagnostics.value.length} 项校验未通过。`
  } catch (error) {
    errorMessage.value = friendlyError(error, '稽核标准没有刷新成功，请稍后再试。')
  } finally {
    refreshingStandard.value = false
  }
}

function ensureDraftItemResults(force = false) {
  if (!hasGlobalStandard.value) {
    draft.itemResults = []
    draft.standardVersionId = globalStandard.value.standardVersionId
    draft.standardVersion = globalStandard.value.version
    return
  }
  const clauses = globalStandard.value.groups.flatMap((group) => group.items)
  const sameVersion = draft.standardVersionId === globalStandard.value.standardVersionId
    && draft.standardVersion === globalStandard.value.version
  const sameItems = draft.itemResults.length === clauses.length
    && clauses.every((clause) => draft.itemResults.some((item) => item.standardItemId === clause.id))
  if (!force && sameVersion && sameItems) return

  draft.standardVersionId = globalStandard.value.standardVersionId
  draft.standardVersion = globalStandard.value.version
  draft.fullScore = INSPECTION_MAX_SCORE
  draft.itemResults = clauses.map((clause) => ({
    standardItemId: clause.id,
    code: clause.code,
    dimension: clause.categoryName,
    categoryCode: clause.categoryCode,
    categoryName: clause.categoryName,
    title: clause.item,
    description: clause.description,
    checkMethod: clause.method,
    standardScore: clause.score,
    actualScore: clause.score,
    deductionReason: '',
    riskLevel: clause.riskLevel,
    issueFound: false,
    redLineHit: false,
    photoAttachmentIds: [],
    beforePhotoAttachmentIds: [],
    afterPhotoAttachmentIds: [],
    responsiblePerson: '',
    rectificationDeadline: '',
    rectificationStatus: '待整改',
    reviewResult: '',
  }))
}

function fillDeductionFromClause() {
  if (!selectedClause.value) return
  deductionForm.deduct = selectedClause.value.score || 1
}

function handleDraftBrandChange() {
  draft.storeId = ''
  ensureDraftStore()
  ensureDeductionForm()
}

function resetDraft() {
  const nextBrand = filterBrand.value || draft.brandName || brandOptions.value[0]?.name || '茹菓'
  draft.brandName = nextBrand
  draft.storeId = ''
  draft.inspectionDate = todayDate()
  draft.inspector = ''
  draft.fullScore = INSPECTION_MAX_SCORE
  draft.note = ''
  releaseDraftPhotos()
  draft.photos = []
  draft.deductions = []
  draft.redlines = []
  draft.itemResults = []
  draftReviewBusyKeys.value = []
  clearDraftReviewTimers()
  deductionForm.manualItem = ''
  deductionForm.issue = ''
  deductionForm.deduct = null
  ensureDraftStore()
  ensureDeductionForm()
  ensureDraftItemResults(true)
}

function clearDraftReviewTimers() {
  draftReviewTimers.forEach((timer) => window.clearTimeout(timer))
  draftReviewTimers.clear()
}

function parseJsonArray<T = unknown>(value?: string): T[] {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function normalizeDeductionDetail(item: unknown, redline = false): DeductionDetail {
  const value = (item || {}) as Record<string, unknown>
  const dim = textValue(value.dim ?? value.dimension ?? value.project ?? value.deduction_project)
  const title = textValue(value.item ?? value.title ?? value.standardTitle ?? value.standard_title ?? value.deduction_content ?? value.content)
  const issue = textValue(value.issue ?? value.problemDescription ?? value.problem_description ?? value.description)
  const deduct = safeNumber(value.deduct ?? value.deductionScore ?? value.deduction_score ?? value.actual_deduction_score ?? value.score)
  return {
    standardId: textValue(value.standardId ?? value.standard_id),
    standardTitle: textValue(value.standardTitle ?? value.standard_title),
    standardDescription: textValue(value.standardDescription ?? value.standard_description),
    suggestedScore: safeNumber(value.suggestedScore ?? value.suggested_score),
    dim,
    categoryCode: categoryCodeForDimension(dim),
    code: textValue(value.code),
    item: title || '现场问题',
    issue,
    deduct,
    requirement: textValue(value.requirement ?? value.rectificationRequirement ?? value.rectification_requirement),
    redline: redline || Boolean(value.redline ?? value.red_line),
    method: textValue(value.method),
    sourceImageId: textValue(value.sourceImageId ?? value.source_image_id),
    modelAssisted: Boolean(value.modelAssisted ?? value.model_assisted),
    modelConfidence: safeNumber(value.modelConfidence ?? value.model_confidence),
    photoAttachmentIds: numberArray(value.photoAttachmentIds ?? value.photo_attachment_ids),
    responsiblePerson: textValue(value.responsiblePerson ?? value.responsible_person),
    rectificationDeadline: textValue(value.rectificationDeadline ?? value.rectification_deadline),
    rectificationStatus: textValue(value.rectificationStatus ?? value.rectification_status),
    reviewResult: textValue(value.reviewResult ?? value.review_result),
  }
}

function textValue(value: unknown) {
  return String(value ?? '').trim()
}

function numberArray(value: unknown) {
  return Array.isArray(value) ? value.map(Number).filter((item) => Number.isFinite(item)) : []
}

function deductionDetails(record: InspectionRecord) {
  return parseJsonArray(record.deductionsJson).map((item) => normalizeDeductionDetail(item, false))
}

function redlineDetails(record: InspectionRecord) {
  return parseJsonArray(record.redlinesJson).map((item) => normalizeDeductionDetail(item, true))
}

function recordPhotos(record: InspectionRecord) {
  return parseJsonArray<Record<string, unknown>>(record.photosJson).map((photo, index) => normalizeRecordPhoto(photo, index))
}

function normalizeRecordPhoto(value: Record<string, unknown>, sourcePhotoIndex?: number): DraftPhoto {
  const nestedDetection = value.detection && typeof value.detection === 'object'
    ? value.detection as InspectionDetectionResult
    : undefined
  const directDetection = (
    value.detectionKey
    || value.image_id
    || value.imageId
    || value.detections
    || value.detection_count !== undefined
  ) ? value as InspectionDetectionResult : undefined
  const detection = nestedDetection || directDetection
  const decision = detectionDecisionStatus(detection)
  const reviewStatus: DraftPhoto['reviewStatus'] = decision === 'CONFIRMED'
    ? 'accepted'
    : ['REVOKED', 'DISMISSED'].includes(decision) ? 'dismissed' : 'pending'
  return {
    attachmentId: positiveNumber(value.attachmentId ?? value.attachment_id),
    sourcePhotoIndex,
    fileName: textValue(value.fileName ?? value.filename ?? detection?.filename) || '现场照片',
    url: textValue(value.url),
    contentType: textValue(value.contentType ?? value.content_type),
    fileSize: safeNumber(value.fileSize ?? value.file_size),
    detectionStatus: detection ? 'success' : 'failed',
    detection,
    reviewStatus,
  }
}

/**
 * A photo is evidence for a clause only when its persisted attachment id is explicitly
 * present in that clause's photoAttachmentIds. AI matching alone must never create a
 * score/evidence relationship in the detail view.
 */
function recordClausePhotos(record: InspectionRecord, item: InspectionItemResult) {
  const photosByAttachmentId = new Map(
    recordPhotos(record)
      .filter(hasPersistedAttachment)
      .map((photo) => [photo.attachmentId, photo]),
  )
  return numberArray(item.photoAttachmentIds)
    .map((attachmentId) => photosByAttachmentId.get(attachmentId))
    .filter(hasPersistedAttachment)
}

function recordUnlinkedPhotos(record: InspectionRecord) {
  const associatedIds = new Set(recordItemResults(record).flatMap((item) => numberArray(item.photoAttachmentIds)))
  return recordPhotos(record).filter((photo) => !photo.attachmentId || !associatedIds.has(photo.attachmentId))
}

function recordPhotoIsExplicitlyLinked(record: InspectionRecord, photo: DraftPhoto) {
  const attachmentId = photo.attachmentId
  return Boolean(attachmentId && recordItemResults(record).some((item) => (
    numberArray(item.photoAttachmentIds).includes(attachmentId)
  )))
}

function evidenceCandidateForHistoricalPhoto(photo: DraftPhoto) {
  if (!Number.isInteger(photo.sourcePhotoIndex) || Number(photo.sourcePhotoIndex) < 0) return undefined
  return detailEvidenceCandidatesByPhotoIndex.value[String(photo.sourcePhotoIndex)]
}

function historicalPhotoNeedsOriginalUpload(photo: DraftPhoto) {
  const status = evidenceCandidateForHistoricalPhoto(photo)?.status
  return status === 'MISSING' || status === 'ORIGINAL_NOT_STORED'
}

function hasValidUnlinkedHistoricalEvidence(photo: DraftPhoto) {
  const candidate = evidenceCandidateForHistoricalPhoto(photo)
  return candidate?.status === 'UNLINKED'
    && Number.isInteger(candidate.attachmentId)
    && Number(candidate.attachmentId) > 0
    && candidate.attachmentId === photo.attachmentId
}

function unlinkedEvidenceActionMode(photo: DraftPhoto): 'existing' | 'upload' {
  return hasValidUnlinkedHistoricalEvidence(photo) ? 'existing' : 'upload'
}

function unlinkedEvidenceActionLabel(photo: DraftPhoto) {
  return hasValidUnlinkedHistoricalEvidence(photo) ? '关联已有证据' : '补传并关联证据'
}

function unlinkedPhotoMessage(photo: DraftPhoto) {
  if (historicalPhotoNeedsOriginalUpload(photo) || !photo.attachmentId) return '原图未入库，需补传'
  return hasValidUnlinkedHistoricalEvidence(photo) ? '待人工关联历史条款' : '待核验并关联历史条款'
}

function recordPendingAiPhotos(record: InspectionRecord) {
  return recordPhotos(record).filter((photo) => {
    if (!photo.detection) return false
    const status = detectionDecisionStatus(photo.detection)
    return status === 'PENDING' || status === 'UNMATCHED'
  })
}

function detailPhotoKey(photo: DraftPhoto) {
  return photo.attachmentId ? String(photo.attachmentId) : ''
}

function detailPhotoState(photo: DraftPhoto): DetailPhotoPreview {
  if (!photo.attachmentId) return { status: 'missing', message: '原图未入库，需补传' }
  return detailPhotoPreviews.value[detailPhotoKey(photo)] || { status: 'loading', message: '正在加载图片…' }
}

function detailPhotoMessage(photo: DraftPhoto) {
  const state = detailPhotoState(photo)
  if (state.status === 'ready') return ''
  return state.message || '图片加载失败'
}

function isPreviewableImage(blob: Blob, photo: DraftPhoto) {
  if (String(blob.type || photo.contentType || '').toLowerCase().startsWith('image/')) return true
  return /\.(?:avif|gif|jpe?g|png|webp)$/i.test(photo.fileName)
}

function detailPhotoError(error: unknown): DetailPhotoPreview {
  const status = Number((error as { status?: number })?.status)
  if (status === 403) return { status: 'forbidden', message: '无查看权限' }
  if (status === 404) return { status: 'missing', message: '图片不存在' }
  if (status === 401) return { status: 'forbidden', message: '登录已失效，请重新登录' }
  return { status: 'failed', message: '图片加载失败，请稍后重试' }
}

function replaceDetailPhotoState(key: string, state: DetailPhotoPreview) {
  detailPhotoPreviews.value = { ...detailPhotoPreviews.value, [key]: state }
}

async function loadDetailPhotoPreviews(record: InspectionRecord) {
  releaseDetailPhotoPreviews()
  const generation = detailPhotoPreviewGeneration
  // Do not fetch an original image merely because a historical metadata entry has
  // an attachmentId. A Blob is requested only after this exact id is persisted in
  // a snapshot clause's photoAttachmentIds collection.
  const uniquePhotos = Array.from(new Map(
    recordItemResults(record)
      .flatMap((item) => recordClausePhotos(record, item))
      .map((photo) => [String(photo.attachmentId), photo]),
  ).values())
  detailPhotoPreviews.value = Object.fromEntries(
    uniquePhotos.map((photo) => [detailPhotoKey(photo), { status: 'loading', message: '正在加载图片…' }]),
  )
  await Promise.all(uniquePhotos.map((photo) => loadDetailPhotoPreview(photo, generation)))
}

async function loadDetailPhotoPreview(photo: DraftPhoto, generation = detailPhotoPreviewGeneration) {
  const attachmentId = photo.attachmentId
  const key = detailPhotoKey(photo)
  if (!attachmentId || !key) return
  detailPhotoControllers.get(key)?.abort()
  const controller = new AbortController()
  detailPhotoControllers.set(key, controller)
  replaceDetailPhotoState(key, { status: 'loading', message: '正在加载图片…' })
  try {
    const blob = await fetchInspectionAttachment(attachmentId, controller.signal)
    if (generation !== detailPhotoPreviewGeneration || controller.signal.aborted) return
    if (!isPreviewableImage(blob, photo)) {
      replaceDetailPhotoState(key, { status: 'failed', message: '附件不是可预览图片' })
      return
    }
    const previous = detailPhotoPreviews.value[key]?.url
    if (previous) URL.revokeObjectURL(previous)
    replaceDetailPhotoState(key, { status: 'ready', url: URL.createObjectURL(blob) })
  } catch (error) {
    if (generation !== detailPhotoPreviewGeneration || controller.signal.aborted) return
    replaceDetailPhotoState(key, detailPhotoError(error))
  } finally {
    if (detailPhotoControllers.get(key) === controller) detailPhotoControllers.delete(key)
  }
}

function retryDetailPhoto(photo: DraftPhoto) {
  void loadDetailPhotoPreview(photo)
}

function markDetailPhotoFailed(photo: DraftPhoto) {
  const key = detailPhotoKey(photo)
  if (!key) return
  const url = detailPhotoPreviews.value[key]?.url
  if (url) URL.revokeObjectURL(url)
  replaceDetailPhotoState(key, { status: 'failed', message: '图片加载失败，请稍后重试' })
  if (detailPhotoPreview.value?.url === url) closeDetailPhotoPreview()
}

function openDetailPhotoPreview(photo: DraftPhoto, event?: MouseEvent) {
  const state = detailPhotoState(photo)
  if (state.status !== 'ready' || !state.url) return
  detailPhotoPreviewTrigger = event?.currentTarget instanceof HTMLElement
    ? event.currentTarget
    : document.activeElement instanceof HTMLElement ? document.activeElement : null
  detailPhotoPreview.value = { url: state.url, fileName: photo.fileName || '现场图片' }
}

function closeDetailPhotoPreview() {
  detailPhotoPreview.value = null
  const trigger = detailPhotoPreviewTrigger
  detailPhotoPreviewTrigger = null
  trigger?.focus()
}

function handleDetailPhotoPreviewKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDetailPhotoPreview()
    return
  }
  if (event.key !== 'Tab' || !detailPhotoPreviewDialog.value) return
  const focusable = Array.from(detailPhotoPreviewDialog.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )).filter((element) => !element.hasAttribute('hidden'))
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function releaseDetailPhotoPreviews() {
  detailPhotoPreviewGeneration += 1
  detailPhotoControllers.forEach((controller) => controller.abort())
  detailPhotoControllers.clear()
  Object.values(detailPhotoPreviews.value).forEach((state) => {
    if (state.url) URL.revokeObjectURL(state.url)
  })
  detailPhotoPreviews.value = {}
  detailPhotoPreview.value = null
  detailPhotoPreviewTrigger = null
}

function clauseEvidenceStatus(record: InspectionRecord, item: InspectionItemResult) {
  const photos = recordClausePhotos(record, item)
  const decisionStatuses = photos.map((photo) => detectionDecisionStatus(photo.detection))
  const effective = itemDeduction(item) > 0
  if (effective) {
    const status = decisionStatuses.includes('CONFIRMED')
      ? '历史已生效（AI已确认）'
      : '历史已生效（人工确认）'
    return decisionStatuses.includes('PENDING') || decisionStatuses.includes('UNMATCHED')
      ? `${status}；另有 AI 待确认未计分`
      : status
  }
  if (decisionStatuses.includes('UNMATCHED')) return '未匹配正式条款，未计分'
  if (decisionStatuses.includes('PENDING')) return 'AI待确认，未计分'
  return photos.length ? '已关联证据，未扣分' : '未关联证据'
}

function photoAiStatus(photo: DraftPhoto) {
  const status = detectionDecisionStatus(photo.detection)
  if (status === 'UNMATCHED') return '未匹配正式条款，未计入本次得分'
  if (status === 'PENDING') return 'AI待确认，未计入本次得分'
  if (status === 'CONFIRMED') return '历史已生效（AI已确认）'
  if (status === 'REVOKED') return 'AI确认已撤销，未计分'
  return '未关联证据'
}

function positiveNumber(value: unknown) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : undefined
}

function hasPersistedAttachment(photo: DraftPhoto | undefined): photo is PersistedDraftPhoto {
  const attachmentId = photo?.attachmentId
  return typeof attachmentId === 'number' && Number.isFinite(attachmentId) && attachmentId > 0
}

function hasRepairMarker(record: InspectionRecord) {
  const status = String(record.repairStatus || '').toUpperCase()
  return Boolean(record.repaired || record.repairAuditId || status === 'REPAIRED' || status === 'MANUAL_REVIEW' || status === 'PENDING_REVIEW')
}

function recordItemResults(record: InspectionRecord) {
  if (Array.isArray(record.itemResults) && record.itemResults.length) return record.itemResults
  if (hasRepairMarker(record)) return []
  const fallback = [...deductionDetails(record), ...redlineDetails(record)].map((item, index) => ({
    standardItemId: Number(item.standardId || index + 1),
    code: item.code,
    dimension: item.dim,
    categoryCode: item.categoryCode || categoryCodeForDimension(item.dim),
    categoryName: item.dim,
    title: item.standardTitle || item.item,
    description: item.standardDescription,
    checkMethod: item.method,
    standardScore: safeNumber(item.suggestedScore || item.deduct),
    actualScore: Math.max(0, safeNumber(item.suggestedScore || item.deduct) - safeNumber(item.deduct)),
    deductionReason: item.issue,
    riskLevel: item.redline ? 'RED' as const : 'NORMAL' as const,
    issueFound: Boolean(item.redline),
    redLineHit: Boolean(item.redline),
    photoAttachmentIds: item.photoAttachmentIds || [],
    responsiblePerson: item.responsiblePerson,
    rectificationDeadline: item.rectificationDeadline,
    rectificationStatus: item.rectificationStatus,
    reviewResult: item.reviewResult,
  }))
  return Array.from(new Map(fallback.map((item) => [item.standardItemId, item])).values())
}

function recordSnapshotComplete(record: InspectionRecord) {
  if (!Array.isArray(record.itemResults) || record.itemResults.length !== 105) return false
  const itemIds = new Set(record.itemResults.map((item) => item.standardItemId))
  if (itemIds.size !== 105) return false
  const categoryCounts = record.itemResults.reduce<Record<InspectionCategoryCode, number>>((counts, item) => {
    const categoryCode = item.categoryCode || categoryCodeForDimension(item.categoryName || item.dimension)
    if (categoryCode) counts[categoryCode] += 1
    return counts
  }, { MATERIAL: 0, HYGIENE: 0, SERVICE: 0 })
  return categoryCounts.MATERIAL === 40 && categoryCounts.HYGIENE === 47 && categoryCounts.SERVICE === 18
}

function recordSnapshotHeading(record: InspectionRecord) {
  const count = recordItemResults(record).length
  return recordSnapshotComplete(record)
    ? `当时使用的完整标准快照（${count}条）`
    : `历史巡检条款快照（${count}条）`
}

function recordSnapshotEmptyText(record: InspectionRecord) {
  return hasRepairMarker(record)
    ? '历史问题快照不完整，原始说明仅供审计，不参与当前评分。'
    : '暂无巡检条款快照。'
}

function recordStandardVersion(record: InspectionRecord) {
  return record.originalStandardVersion || record.standardVersion || (record.standardVersionId ? `版本 ID ${record.standardVersionId}` : '历史标准')
}

function hasCategoryScores(record: InspectionRecord) {
  return [record.materialScore, record.hygieneScore, record.serviceScore].some((value) => value !== undefined && value !== null)
}

function recordRedLineCount(record: InspectionRecord) {
  if (record.redLineCount !== undefined && record.redLineCount !== null) return safeNumber(record.redLineCount)
  const canonicalCount = recordItemResults(record).filter((item) => item.riskLevel === 'RED' && Boolean(item.issueFound ?? item.redLineHit)).length
  if (canonicalCount) return canonicalCount
  if (record.resultCode === 'RED_LINE_FAILED') return 1
  return hasRepairMarker(record) ? 0 : redlineDetails(record).length
}

function recordYellowLineCount(record: InspectionRecord) {
  if (record.yellowLineCount !== undefined && record.yellowLineCount !== null) return safeNumber(record.yellowLineCount)
  return recordItemResults(record).filter((item) => item.riskLevel === 'YELLOW' && itemDeduction(item) > 0).length
}

function repairStatusLabel(record: InspectionRecord) {
  const status = String(record.repairStatus || '').toUpperCase()
  if (status === 'REPAIRED' || record.repaired) return '历史评分已迁移'
  if (status === 'MANUAL_REVIEW' || status === 'PENDING_REVIEW') return '待人工复核'
  return '无需迁移'
}

function repairStatusTone(record: InspectionRecord) {
  const status = String(record.repairStatus || '').toUpperCase()
  if (status === 'REPAIRED' || record.repaired) return 'repaired'
  if (status === 'MANUAL_REVIEW' || status === 'PENDING_REVIEW') return 'review'
  return 'original'
}

function requiresManualReview(record: InspectionRecord) {
  return ['MANUAL_REVIEW', 'PENDING_REVIEW'].includes(String(record.repairStatus || '').toUpperCase())
}

function hasMigrationAudit(record: InspectionRecord) {
  return Boolean(
    hasRepairMarker(record)
    && record.originalScore !== undefined
    && record.originalFullScore !== undefined,
  )
}

function migrationAuditText(record: InspectionRecord) {
  if (!hasMigrationAudit(record)) return ''
  const original = `${formatScore(record.originalScore)} / ${formatScore(record.originalFullScore)}`
  const current = inspectionScoreView(record).scoreText
  const audit = record.repairAuditId ? ` · 审计记录 #${record.repairAuditId}` : ''
  return `${original} → ${current}${audit}`
}

function recordCategoryScore(record: InspectionRecord, category: InspectionCategoryCode) {
  const direct = category === 'MATERIAL'
    ? record.displayMaterialScore ?? record.repairedMaterialScore ?? record.materialScore
    : category === 'HYGIENE'
      ? record.displayHygieneScore ?? record.repairedHygieneScore ?? record.hygieneScore
      : record.displayServiceScore ?? record.repairedServiceScore ?? record.serviceScore
  if (direct !== undefined && direct !== null) return roundScore(safeNumber(direct))
  return roundScore(recordItemResults(record)
    .filter((item) => item.categoryCode === category || categoryCodeForDimension(item.categoryName || item.dimension) === category)
    .reduce((sum, item) => sum + safeNumber(item.actualScore), 0))
}

function deductionCount(record: InspectionRecord) {
  return recordItemResults(record).filter((item) => itemDeduction(item) > 0 || item.issueFound || item.redLineHit).length
}

function recordScore(record: InspectionRecord) {
  return inspectionScoreView(record)
}

function resultLabel(record: InspectionRecord) {
  return recordScore(record).resultText
}

function resultIsFailed(record: InspectionRecord) {
  return recordScore(record).tone !== 'ok'
}

async function exportRecord(record: InspectionRecord) {
  exportingRecordId.value = String(record.id)
  errorMessage.value = ''
  actionMessage.value = ''
  try {
    const date = String(record.inspectionDate || '').replace(/-/g, '') || todayDate().replace(/-/g, '')
    const store = safeFilename(record.storeName || record.storeCode || record.storeId || '门店')
    await downloadInspectionExcel(String(record.id), `茹菓-${store}-巡检报告-${date}-${record.id}.xlsx`)
    actionMessage.value = '巡检报告已生成并开始下载。'
  } catch (error) {
    errorMessage.value = inspectionExportError(error)
  } finally {
    exportingRecordId.value = ''
  }
}

async function decidePersistedDetection(photo: DraftPhoto, action: 'confirm' | 'revoke') {
  const record = selectedRecord.value
  const result = photo.detection
  const key = detectionKey(result)
  if (!record || !key || isPersistedDecisionBusy(key)) return
  persistedDecisionBusyKeys.value = [...persistedDecisionBusyKeys.value, key]
  errorMessage.value = ''
  actionMessage.value = ''
  try {
    const response = action === 'confirm'
      ? await confirmInspectionDetection(String(record.id), key, detectionRevision(result))
      : await revokeInspectionDetection(String(record.id), key, detectionRevision(result))
    detailRecord.value = response.record
    const index = records.value.findIndex((item) => String(item.id) === String(response.record.id))
    if (index >= 0) records.value.splice(index, 1, response.record)
    actionMessage.value = action === 'confirm'
      ? (response.changed ? '已确认模型建议，最终扣分已由系统按正式条款计算。' : '该模型建议已经确认，无需重复操作。')
      : (response.changed ? '已撤销模型建议，系统已恢复该条款原评分。' : '该模型建议已处于撤销状态。')
  } catch (error) {
    errorMessage.value = friendlyError(error, action === 'confirm' ? '模型建议确认失败，请重试。' : '模型建议撤销失败，请重试。')
  } finally {
    persistedDecisionBusyKeys.value = persistedDecisionBusyKeys.value.filter((item) => item !== key)
  }
}

function isPersistedDecisionBusy(key: string) {
  return persistedDecisionBusyKeys.value.includes(key)
}

function safeFilename(value: string) {
  return value.replace(/[\\/:*?"<>|]/g, '-').slice(0, 60)
}

function riskLabel(value?: string) {
  if (value === 'RED') return '红线'
  if (value === 'YELLOW') return '黄线'
  return '常规'
}

async function handleAttachmentChange(event: Event) {
  const input = event.target as HTMLInputElement
  await uploadPhotos(Array.from(input.files || []))
  input.value = ''
}

async function handlePhotoDrop(event: DragEvent) {
  await uploadPhotos(Array.from(event.dataTransfer?.files || []))
}

async function refreshDetectionService() {
  checkingDetectionService.value = true
  try {
    detectionService.value = await getInspectionServiceHealth()
  } catch (error) {
    detectionService.value = {
      status: 'DOWN',
      configured: true,
      message: friendlyError(error, '识别服务不可用，请确认本机图片识别服务已启动。'),
    }
  } finally {
    checkingDetectionService.value = false
  }
}

async function uploadPhotos(files: File[]) {
  const imageFiles = files.filter((file) => file.type.startsWith('image/'))
  if (!imageFiles.length) return
  if (!standardReady.value) {
    errorMessage.value = '当前标准未通过校验，刷新并确认标准有效后才能上传巡检证据。'
    return
  }
  if (!draft.storeId) {
    errorMessage.value = '请先选择巡检门店后再上传照片。'
    return
  }
  uploading.value = true
  errorMessage.value = ''
  let detectedCount = 0
  try {
    for (const file of imageFiles) {
      const uploaded = await uploadInspectionAttachment(file, draft.storeId, `inspection-${draft.storeId}-draft`)
      draft.photos.push(toDraftPhoto(uploaded, file))
      const photo = draft.photos[draft.photos.length - 1]
      await runPhotoDetection(photo)
      if (photo.detectionStatus === 'success') detectedCount += 1
    }
    actionMessage.value = detectedCount > 0
      ? `已上传并识别 ${detectedCount} 张现场照片，请督导确认模型建议。`
      : ''
  } catch (error) {
    errorMessage.value = friendlyError(error, '照片上传失败。')
  } finally {
    uploading.value = false
  }
}

function toDraftPhoto(uploaded: InspectionAttachment, file: File): DraftPhoto {
  return {
    attachmentId: uploaded.id,
    fileName: uploaded.fileName || file.name,
    sourceFile: file,
    url: uploaded.url,
    previewUrl: URL.createObjectURL(file),
    contentType: uploaded.contentType || file.type,
    fileSize: uploaded.fileSize || file.size,
    detectionStatus: 'detecting',
    reviewStatus: 'pending',
  }
}

async function runPhotoDetection(photo: DraftPhoto) {
  if (!photo.sourceFile) {
    photo.detectionStatus = 'failed'
    photo.detectionError = '原始图片已释放，请移除后重新选择。'
    return
  }
  photo.detectionStatus = 'detecting'
  photo.detectionError = ''
  photo.detection = undefined
  photo.reviewStatus = 'pending'
  removeModelDeduction(photo)
  removeModelPhotoLink(photo)
  try {
    photo.detection = await detectInspectionPhoto(photo.sourceFile)
    photo.detectionStatus = 'success'
    if (!detectionServiceUp.value) void refreshDetectionService()
  } catch (error) {
    photo.detectionStatus = 'failed'
    photo.detectionError = friendlyError(error, '识别服务不可用，请启动服务后重试。')
    detectionService.value = {
      status: 'DOWN',
      configured: true,
      message: '识别服务不可用，请启动服务后重试。',
    }
  }
}

async function retryPhotoDetection(photo: DraftPhoto) {
  errorMessage.value = ''
  actionMessage.value = ''
  await runPhotoDetection(photo)
}

async function confirmModelIssue(photo: DraftPhoto) {
  const result = photo.detection
  if (!result || detectionCount(result) <= 0 || isDraftReviewBusy(photo)) return
  const key = detectionKey(result)
  if (!key) {
    errorMessage.value = '识别结果缺少服务端确认编号，请重新识别后再确认。'
    return
  }

  lockDraftReviewAction(photo)
  errorMessage.value = ''
  actionMessage.value = ''
  try {
    const confirmed = await confirmInspectionDetectionSuggestion(key, toDetectionBindingResult(photo))
    const clauseId = detectionClauseId(confirmed)
    if (!clauseId || safeNumber(confirmed.confirmedDeduction) <= 0) {
      throw new Error('该建议未匹配到可扣分的正式条款，请在条款评分区人工处理。')
    }
    const target = draft.itemResults.find((item) => item.standardItemId === clauseId)
    if (!target) throw new Error('模型匹配的正式条款不在当前标准中，请刷新标准后重新识别。')

    removeModelDeduction(photo)
    removeModelPhotoLink(photo)
    photo.detection = {
      ...result,
      ...confirmed,
      annotated_image: result.annotated_image,
    }
    if (photo.attachmentId) {
      const alreadyLinked = target.photoAttachmentIds.includes(photo.attachmentId)
      toggleItemPhoto(target, photo.attachmentId, true)
      photo.modelLinkedClauseId = clauseId
      photo.modelAddedPhotoLink = !alreadyLinked
    }
    photo.reviewStatus = 'accepted'
    actionMessage.value = '已通过服务端规则确认；预计扣分已展示，正式保存时服务端会再次按条款重算。'
  } catch (error) {
    photo.reviewStatus = 'pending'
    errorMessage.value = friendlyError(error, '模型建议确认失败，已保留待确认状态，请重试。')
  } finally {
    unlockDraftReviewAction(photo)
  }
}

function dismissModelIssue(photo: DraftPhoto) {
  if (isDraftReviewBusy(photo)) return
  lockDraftReviewAction(photo)
  removeModelDeduction(photo)
  removeModelPhotoLink(photo)
  photo.reviewStatus = 'dismissed'
  actionMessage.value = '已记录督导人工排除，保存时会写入识别审计。'
  unlockDraftReviewAction(photo)
}

function undoModelReview(photo: DraftPhoto) {
  if (isDraftReviewBusy(photo)) return
  lockDraftReviewAction(photo)
  removeModelDeduction(photo)
  removeModelPhotoLink(photo)
  if (photo.detection) {
    photo.detection = {
      ...photo.detection,
      confirmedDeduction: undefined,
      decisionStatus: 'PENDING',
    }
  }
  photo.reviewStatus = 'pending'
  actionMessage.value = '已撤销本次人工确认，请重新核对模型建议。'
  unlockDraftReviewAction(photo)
}

function lockDraftReviewAction(photo: DraftPhoto) {
  const key = draftReviewKey(photo)
  if (draftReviewBusyKeys.value.includes(key)) return
  draftReviewBusyKeys.value = [...draftReviewBusyKeys.value, key]
  const previous = draftReviewTimers.get(key)
  if (previous) window.clearTimeout(previous)
  draftReviewTimers.delete(key)
}

function unlockDraftReviewAction(photo: DraftPhoto) {
  const key = draftReviewKey(photo)
  const previous = draftReviewTimers.get(key)
  if (previous) window.clearTimeout(previous)
  const timer = window.setTimeout(() => {
    draftReviewBusyKeys.value = draftReviewBusyKeys.value.filter((item) => item !== key)
    draftReviewTimers.delete(key)
  }, 350)
  draftReviewTimers.set(key, timer)
}

function isDraftReviewBusy(photo: DraftPhoto) {
  return draftReviewBusyKeys.value.includes(draftReviewKey(photo))
}

function draftReviewKey(photo: DraftPhoto) {
  return detectionKey(photo.detection) || detectionImageId(photo.detection) || String(photo.attachmentId || photo.fileName)
}

function removeModelPhotoLink(photo: DraftPhoto) {
  if (!photo.modelAddedPhotoLink || !photo.modelLinkedClauseId || !photo.attachmentId) {
    photo.modelAddedPhotoLink = false
    photo.modelLinkedClauseId = undefined
    return
  }
  const target = draft.itemResults.find((item) => item.standardItemId === photo.modelLinkedClauseId)
  if (target) toggleItemPhoto(target, photo.attachmentId, false)
  photo.modelAddedPhotoLink = false
  photo.modelLinkedClauseId = undefined
}

function removeModelDeduction(photo: DraftPhoto) {
  const imageId = detectionImageId(photo.detection)
  if (!imageId) return
  const rows = draft.deductions.filter((item) => item.sourceImageId === imageId)
  rows.forEach(restoreDeduction)
  draft.deductions = draft.deductions.filter((item) => item.sourceImageId !== imageId)
}

function maxDetectionConfidence(items: InspectionDetectionItem[] = []) {
  return items.reduce((max, item) => Math.max(max, safeNumber(item.confidence)), 0)
}

function confidenceText(value?: number) {
  return `${Math.round(safeNumber(value) * 100)}%`
}

function detectionCount(result?: InspectionDetectionResult) {
  return Math.max(0, Math.trunc(safeNumber(result?.detection_count ?? result?.detectionCount ?? result?.detections?.length)))
}

function detectionKey(result?: InspectionDetectionResult) {
  return textValue(result?.detectionKey)
}

function detectionImageId(result?: InspectionDetectionResult) {
  return textValue(result?.image_id ?? result?.imageId)
}

function detectionClauseId(result?: InspectionDetectionResult) {
  const value = Number(result?.clauseId)
  return Number.isFinite(value) && value > 0 ? value : undefined
}

function detectionClauseLabel(result?: InspectionDetectionResult) {
  const code = textValue(result?.clauseCode)
  const title = textValue(result?.clauseTitle)
  const id = detectionClauseId(result)
  return [code, title, id ? `条款ID ${id}` : ''].filter(Boolean).join(' · ') || '未匹配正式条款'
}

function detectionFinalDeduction(result?: InspectionDetectionResult) {
  return Math.abs(safeNumber(result?.confirmedDeduction ?? result?.finalDeduction ?? result?.standardDeduction))
}

function detectionConfidence(result?: InspectionDetectionResult) {
  return safeNumber(result?.confidence) || maxDetectionConfidence(result?.detections)
}

function detectionRevision(result?: InspectionDetectionResult) {
  const revision = Number(result?.revision)
  return Number.isFinite(revision) && revision >= 0 ? revision : undefined
}

function detectionDecisionStatus(result?: InspectionDetectionResult) {
  return textValue(result?.decisionStatus ?? result?.review_status).toUpperCase() || 'PENDING'
}

function detectionLabel(item: InspectionDetectionItem) {
  const labels: Record<string, string> = {
    paper_scrap: '地面纸屑',
    stain: '地面污渍',
    floor_litter: '地面杂物',
    corner_dust: '角落积灰',
  }
  return labels[String(item.class_name || '').toLowerCase()] || item.class_name || '疑似问题'
}

function payloadPhoto(photo: DraftPhoto) {
  return {
    attachmentId: photo.attachmentId,
    fileName: photo.fileName,
    url: photo.url,
    contentType: photo.contentType,
    fileSize: photo.fileSize,
    detectionStatus: photo.detectionStatus,
    reviewStatus: photo.reviewStatus,
    detection: sanitizeDetectionForPersistence(photo.detection),
  }
}

function sanitizeDetectionForPersistence(detection?: InspectionDetectionResult) {
  if (!detection) return undefined
  const safeDetection = { ...detection }
  delete safeDetection.annotated_image
  delete safeDetection.clauseId
  delete safeDetection.clauseCode
  delete safeDetection.clauseTitle
  delete safeDetection.scoreScale
  delete safeDetection.persistedScoreScale
  delete safeDetection.legacyDeduction
  delete safeDetection.convertedDeduction200
  delete safeDetection.standardDeduction
  delete safeDetection.clauseDeduction
  delete safeDetection.scaleAdjustmentDeduction
  delete safeDetection.deductionPolicyVersion
  delete safeDetection.suggestedDeduction
  delete safeDetection.finalDeduction
  delete safeDetection.confirmedDeduction
  delete safeDetection.deduction_score
  delete safeDetection.decisionStatus
  delete safeDetection.revision
  return safeDetection
}

function photoHref(photo: DraftPhoto) {
  // Persisted evidence is read through fetchInspectionAttachment as an authenticated Blob.
  // Only an in-memory draft preview may be opened directly here.
  return photo.previewUrl || '#'
}

function removePhoto(index: number) {
  const photo = draft.photos[index]
  removeModelDeduction(photo)
  removeModelPhotoLink(photo)
  if (photo.attachmentId) {
    draft.itemResults.forEach((item) => {
      toggleItemPhoto(item, photo.attachmentId!, false)
    })
  }
  if (photo.previewUrl) URL.revokeObjectURL(photo.previewUrl)
  draft.photos.splice(index, 1)
}

function releaseDraftPhotos() {
  draft.photos.forEach((photo) => {
    if (photo.previewUrl) URL.revokeObjectURL(photo.previewUrl)
  })
}

function addDeduction() {
  errorMessage.value = ''
  actionMessage.value = ''
  const score = safeNumber(deductionForm.deduct)
  const clause = selectedClause.value
  const itemTitle = (clause?.item || deductionForm.manualItem).trim()

  if (!deductionForm.dimension) {
    errorMessage.value = '请选择问题维度。'
    return
  }
  if (!itemTitle) {
    errorMessage.value = '请填写或选择检查条款。'
    return
  }
  if (!score || score <= 0) {
    errorMessage.value = '扣分必须大于 0。'
    return
  }
  if (score > safeNumber(draft.fullScore)) {
    errorMessage.value = '扣分不能超过满分基准。'
    return
  }
  if (!deductionForm.issue.trim()) {
    errorMessage.value = '请写清楚现场问题，方便门店整改。'
    return
  }

  const target = clause ? draft.itemResults.find((item) => item.standardItemId === clause.id) : undefined
  if (!target) {
    errorMessage.value = '请从最新稽核标准中选择具体条款。'
    return
  }
  const appliedDeduction = Math.min(score, safeNumber(target.actualScore))
  target.actualScore = roundScore(safeNumber(target.actualScore) - appliedDeduction)
  target.deductionReason = [target.deductionReason, deductionForm.issue.trim()].filter(Boolean).join('；')
  normalizeItemScore(target)

  draft.deductions.push({
    id: `${Date.now()}-${draft.deductions.length}`,
    standardId: String(clause.id),
    standardTitle: clause?.item || itemTitle,
    standardDescription: clause?.method,
    suggestedScore: clause?.score,
    dim: clause.categoryName,
    categoryCode: clause.categoryCode,
    code: clause?.code,
    item: itemTitle,
    method: clause?.method,
    issue: deductionForm.issue.trim(),
    deduct: appliedDeduction,
    redline: clause.riskLevel === 'RED',
  })
  deductionForm.issue = ''
  deductionForm.manualItem = ''
  fillDeductionFromClause()
}

function removeDraftRow(kind: 'deduction' | 'redline', index: number) {
  if (kind === 'deduction') {
    const [removed] = draft.deductions.splice(index, 1)
    if (removed) restoreDeduction(removed)
  }
  else draft.redlines.splice(index, 1)
}

function restoreDeduction(detail: DeductionDetail) {
  const standardItemId = Number(detail.standardId)
  if (!Number.isFinite(standardItemId)) return
  const target = draft.itemResults.find((item) => item.standardItemId === standardItemId)
  if (!target) return
  target.actualScore = Math.min(safeNumber(target.standardScore), roundScore(safeNumber(target.actualScore) + safeNumber(detail.deduct)))
  const remaining = draft.deductions.filter((item) => item !== detail && Number(item.standardId) === standardItemId)
  target.deductionReason = remaining.map((item) => item.issue).filter(Boolean).join('；')
  normalizeItemScore(target)
}

async function submitRecord() {
  errorMessage.value = ''
  actionMessage.value = ''
  if (saveBlockedReason.value) {
    errorMessage.value = saveBlockedReason.value
    return
  }
  if (!draft.storeId) {
    errorMessage.value = '请选择巡检门店。'
    return
  }
  if (!draft.inspectionDate) {
    errorMessage.value = '请选择巡检日期。'
    return
  }
  if (!draft.inspector.trim()) {
    errorMessage.value = '请填写督导人姓名。'
    return
  }
  if (!Number.isFinite(Number(draft.fullScore)) || Number(draft.fullScore) <= 0) {
    errorMessage.value = '满分基准必须大于 0。'
    return
  }
  const missingReason = draft.itemResults.find((item) => (item.issueFound || itemDeduction(item) > 0) && !item.deductionReason?.trim())
  if (missingReason) {
    errorMessage.value = `请填写 ${missingReason.code || missingReason.title || '扣分条款'} 的扣分原因。`
    return
  }

  const hitRedLines = draft.itemResults.filter((item) => item.riskLevel === 'RED' && item.issueFound)
  const note = [
    draft.note.trim() ? `整改要求：${draft.note.trim()}` : '',
    redLineHit.value ? `红线项：${hitRedLines.map((item) => item.title || item.code).filter(Boolean).join('；')}` : '',
  ].filter(Boolean).join('\n')

  saving.value = true
  try {
    const savedRecord = await createInspectionRecord({
      storeId: draft.storeId,
      inspectionDate: draft.inspectionDate,
      inspector: draft.inspector.trim(),
      brand: draft.brandName,
      fullScore: INSPECTION_MAX_SCORE,
      score: manualCurrentScore.value,
      materialScore: manualCategoryScore('MATERIAL'),
      hygieneScore: manualCategoryScore('HYGIENE'),
      serviceScore: manualCategoryScore('SERVICE'),
      standardVersionId: draft.standardVersionId,
      itemResults: draft.itemResults.map(toItemResultPayload),
      deductionsJson: JSON.stringify(draft.itemResults.map(toLegacySnapshot)),
      redlinesJson: JSON.stringify(hitRedLines.map(toLegacySnapshot)),
      photosJson: JSON.stringify(draft.photos.map(payloadPhoto)),
      note: note || undefined,
    })

    const savedRecordId = String(savedRecord.id)
    actionMessage.value = `巡检已在一个事务中保存，图片识别扣分已由服务端按正式条款确认。最终得分 ${recordScore(savedRecord).scoreText}。`
    resetDraft()
    await refresh()
    await router.push({ path: '/operations/inspection/records', query: { recordId: savedRecordId } })
  } catch (error) {
    errorMessage.value = inspectionSaveError(error)
  } finally {
    saving.value = false
  }
}

function manualCategoryScore(code: InspectionCategoryCode) {
  const group = globalStandard.value.groups.find((item) => item.categoryCode === code)
  if (!group) return 0
  const itemScore = draft.itemResults
    .filter((item) => item.categoryCode === code)
    .reduce((sum, item) => sum + safeNumber(item.actualScore), 0)
  const additionalDeduction = draft.deductions
    .filter((item) => (item.categoryCode || categoryCodeForDimension(item.dim)) === code && !item.standardId)
    .reduce((sum, item) => sum + safeNumber(item.deduct), 0)
  return Math.max(0, roundScore(itemScore - additionalDeduction))
}

function toDetectionBindingResult(photo: DraftPhoto): Record<string, unknown> {
  const result = photo.detection
  if (!result) throw new Error(`${photo.fileName || '现场照片'} 缺少识别结果，请重新识别。`)
  return {
    image_id: detectionImageId(result),
    filename: photo.fileName || result.filename,
    attachmentId: photo.attachmentId,
    passed: result.passed,
    detection_count: detectionCount(result),
    detections: result.detections || [],
    detection_summary: result.detection_summary,
    auto_status: result.auto_status,
    deduction_project: result.deduction_project,
    deduction_content: result.deduction_content,
  }
}

function toItemResultPayload(item: InspectionItemResult): InspectionItemResult {
  return {
    standardItemId: item.standardItemId,
    actualScore: roundScore(safeNumber(item.actualScore)),
    issueFound: Boolean(item.issueFound),
    deductionReason: item.deductionReason?.trim() || undefined,
    photoAttachmentIds: [...item.photoAttachmentIds],
    beforePhotoAttachmentIds: [...(item.beforePhotoAttachmentIds || item.photoAttachmentIds)],
    afterPhotoAttachmentIds: [...(item.afterPhotoAttachmentIds || [])],
    responsiblePerson: item.responsiblePerson?.trim() || undefined,
    rectificationDeadline: item.rectificationDeadline || undefined,
    rectificationStatus: item.rectificationStatus || (itemNeedsRectification(item) ? '待整改' : '无需整改'),
    reviewResult: item.reviewResult?.trim() || undefined,
  }
}

function toLegacySnapshot(item: InspectionItemResult) {
  return {
    standard_id: item.standardItemId,
    standard_version: draft.standardVersion || undefined,
    standard_title: item.title,
    standard_description: item.description || item.checkMethod,
    suggested_score: safeNumber(item.standardScore),
    actual_score: safeNumber(item.actualScore),
    deduction_score: itemDeduction(item),
    actual_deduction_score: itemDeduction(item),
    red_line: item.riskLevel === 'RED',
    red_line_hit: item.riskLevel === 'RED' && Boolean(item.issueFound),
    issue_found: Boolean(item.issueFound),
    risk_level: item.riskLevel,
    dim: item.categoryName || item.dimension,
    code: item.code,
    item: item.title,
    issue: item.deductionReason,
    photo_attachment_ids: item.photoAttachmentIds,
    responsible_person: item.responsiblePerson,
    rectification_deadline: item.rectificationDeadline,
    rectification_status: item.rectificationStatus,
    review_result: item.reviewResult,
  }
}

function friendlyError(error: unknown, fallback: string) {
  const apiError = error as { status?: number; code?: string }
  const status = apiError.status
  const message = error instanceof Error ? error.message : ''
  if (status === 401) return '登录已过期，请重新登录后再操作。'
  if (status === 403 || message.includes('No permission')) return '当前账号没有巡检权限。'
  if (status === 404) return fallback
  if (apiError.code === 'INSPECTION_RECORD_CONFLICT') return '数据已发生变化，请刷新后重试'
  if (status === 409) return message || fallback
  if (message.includes('Inspection record not found')) return '没有找到这条巡检记录。'
  if (message.includes('Network') || message.includes('timeout')) return '网络连接不稳定，请稍后重试。'
  return message && !/[A-Za-z]{4,}/.test(message) ? message : fallback
}

function inspectionExportError(error: unknown) {
  const apiError = error as { code?: string }
  const message = error instanceof Error ? error.message.trim() : ''
  if (apiError.code === 'INSPECTION_SCORE_REPAIR_REQUIRED') {
    const detail = message
      .replace(/^评分数据待修复[：:]?\s*/, '')
      .replace(/^该巡检记录评分数据不完整[，,]?\s*/, '')
      .replace(/^缺失项[：:]?\s*/, '')
      .trim()
    return detail
      ? `该巡检记录评分数据不完整，缺失项：${detail}。需人工修复评分后导出。`
      : '该巡检记录评分数据不完整，需人工修复评分后导出。'
  }
  if (apiError.code === 'INSPECTION_RECORD_CONFLICT') return '数据已发生变化，请刷新后重试'
  return friendlyError(error, '巡检报告导出失败，请稍后重试。')
}

function inspectionSaveError(error: unknown) {
  const apiError = error as { status?: number; code?: string }
  if (apiError.code === 'INSPECTION_RECORD_CONFLICT') {
    return '这条巡检已被其他人更新，请刷新记录后再提交。'
  }
  if (apiError.code === 'INSPECTION_STANDARD_STALE') {
    return '巡检标准已更新，请点击“刷新标准”后重新评分。'
  }
  if (apiError.code === 'INSPECTION_STANDARD_INVALID') {
    return '当前巡检标准校验未通过，已禁止保存。请刷新标准后再试。'
  }
  if (apiError.code === 'INSPECTION_STANDARD_MISSING') {
    return '当前没有可用的巡检标准，请刷新标准或联系系统管理员。'
  }
  return friendlyError(error, '巡检没有保存成功，请检查后再试。')
}

watch(
  () => route.fullPath,
  () => applyRouteState(),
  { immediate: true },
)

watch(
  selectedRecordId,
  (recordId) => {
    if (recordId) {
      void loadSelectedRecord(recordId)
      return
    }
    detailRequestSequence += 1
    releaseDetailPhotoPreviews()
    historicalEvidenceDialog.value = null
    detailRecord.value = null
    detailError.value = ''
    detailLoading.value = false
  },
  { immediate: true },
)

watch(
  () => [draft.brandName, stores.value.length],
  () => {
    ensureDraftStore()
    ensureDeductionForm()
  },
)

watch(
  () => deductionForm.dimension,
  () => ensureDeductionForm(),
)

watch(
  () => deductionForm.clauseKey,
  () => fillDeductionFromClause(),
)

watch(detailPhotoPreview, (preview) => {
  if (preview) {
    window.addEventListener('keydown', handleDetailPhotoPreviewKeydown)
    void nextTick(() => detailPhotoPreviewDialog.value?.focus())
  } else window.removeEventListener('keydown', handleDetailPhotoPreviewKeydown)
})

onMounted(() => {
  void refresh()
  void refreshDetectionService()
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleDetailPhotoPreviewKeydown)
  releaseDraftPhotos()
  releaseDetailPhotoPreviews()
  clearDraftReviewTimers()
})
</script>

<template>
  <section class="page-panel inspection-workbench-page">
    <PageHeader>
      <template #actions>
        <div class="inspection-head-actions">
          <div class="inspection-segment" role="tablist" aria-label="督导巡店页签">
            <button
              v-for="tab in tabs"
              :key="tab.id"
              type="button"
              class="inspection-segment-button"
              :class="{ on: activeTab === tab.id }"
              :aria-selected="activeTab === tab.id"
              @click="switchTab(tab.id)"
            >{{ tab.label }}</button>
          </div>
          <button class="ghost-button" type="button" :disabled="loading" @click="refresh">
            <RefreshCw :size="16" />刷新
          </button>
        </div>
      </template>
    </PageHeader>

    <div v-if="errorMessage" class="error-box">{{ errorMessage }}</div>
    <div v-if="actionMessage" class="success-box">{{ actionMessage }}</div>

    <div v-if="activeTab === 'records'" class="inspection-records-view">
      <div class="inspection-metrics">
        <div class="content-card inspection-metric">
          <span class="lab">巡检总次数</span>
          <b class="val">{{ summary.total }}</b>
        </div>
        <div class="content-card inspection-metric">
          <span class="lab">本月巡检</span>
          <b class="val">{{ summary.monthCount }}</b>
        </div>
        <div class="content-card inspection-metric">
          <span class="lab">平均得分</span>
          <b class="val">{{ summary.averageScore === null ? '—' : formatScore(summary.averageScore) }}<small> / {{ INSPECTION_MAX_SCORE }}</small></b>
          <small v-if="summary.invalidScoreCount" class="metric-warning">{{ summary.invalidScoreCount }} 条评分数据待修复</small>
        </div>
        <div class="content-card inspection-metric">
          <span class="lab">红线门店次数</span>
          <b class="val red">{{ summary.redlineCount }}</b>
        </div>
      </div>

      <div class="inspection-filter-row">
        <div class="inspection-brand-filter" aria-label="品牌筛选">
          <button type="button" class="inspection-filter-chip" :class="{ on: !filterBrand }" @click="setBrandFilter('')">
            全部品牌
          </button>
          <button
            v-for="brand in brandOptions"
            :key="brand.name"
            type="button"
            class="inspection-filter-chip"
            :class="{ on: filterBrand === brand.name }"
            :style="{ '--brand-color': brand.color, '--brand-soft': brand.soft }"
            @click="setBrandFilter(brand.name)"
          >
            {{ brand.name }}
          </button>
        </div>
        <select class="inspection-month-select" aria-label="月份筛选" :value="filterMonth" @change="setMonthFilter(($event.target as HTMLSelectElement).value)">
          <option value="">全部月份</option>
          <option v-for="month in monthOptions" :key="month" :value="month">{{ month.slice(0, 4) }}年{{ month.slice(5) }}月</option>
        </select>
      </div>

      <section v-if="selectedRecordId && detailLoading" class="content-card inspection-detail-card">
        <div class="empty-state">正在读取巡检详情...</div>
      </section>

      <section v-else-if="selectedRecordId && detailError" class="content-card inspection-detail-card">
        <div class="error-state">{{ detailError }}</div>
        <div class="inspection-detail-actions">
          <button class="secondary-button" type="button" @click="closeRecordDetail">返回巡检记录</button>
          <button class="primary-button" type="button" @click="loadSelectedRecord(selectedRecordId)">重新加载</button>
        </div>
      </section>

      <section v-else-if="selectedRecord" class="content-card inspection-detail-card">
        <div class="inspection-detail-head">
          <div>
            <span class="inspection-section-title">巡检详情</span>
            <h3>{{ selectedRecord.storeName || selectedRecord.storeId }}</h3>
          </div>
          <div class="inspection-detail-actions">
            <button
              v-if="canSupplementHistoricalEvidence"
              class="secondary-button"
              type="button"
              @click="openHistoricalEvidenceDialog()"
            ><ImagePlus :size="16" />补传并关联证据</button>
            <button class="primary-button" type="button" :disabled="exportingRecordId === String(selectedRecord.id)" @click="exportRecord(selectedRecord)">
              <Download :size="16" />{{ exportingRecordId === String(selectedRecord.id) ? '正在生成...' : '导出Excel' }}
            </button>
            <button class="secondary-button" type="button" @click="closeRecordDetail">返回巡检记录</button>
          </div>
        </div>
        <div class="inspection-detail-grid">
          <div><span>门店</span><b>{{ selectedRecord.storeName || selectedRecord.storeId }}</b></div>
          <div><span>品牌</span><b>{{ recordBrandName(selectedRecord) || '—' }}</b></div>
          <div><span>巡检日期</span><b>{{ selectedRecord.inspectionDate || '—' }}</b></div>
          <div><span>督导</span><b>{{ selectedRecord.inspector || '—' }}</b></div>
          <div><span>标准版本</span><b>{{ recordStandardVersion(selectedRecord) }}</b></div>
          <div><span>满分 / 合格线</span><b>{{ formatScore(recordScore(selectedRecord).maxScore) }} / {{ formatScore(recordScore(selectedRecord).passScore) }}</b></div>
          <div><span>得分</span><b :class="{ danger: resultIsFailed(selectedRecord) }">{{ recordScore(selectedRecord).scoreText }}</b></div>
          <div><span>结果</span><b :class="{ danger: resultIsFailed(selectedRecord) }">{{ resultLabel(selectedRecord) }}</b></div>
          <div><span>红线 / 黄线</span><b>{{ recordRedLineCount(selectedRecord) }} / {{ recordYellowLineCount(selectedRecord) }}</b></div>
          <div><span>历史处理状态</span><b><span class="repair-status" :class="repairStatusTone(selectedRecord)">{{ repairStatusLabel(selectedRecord) }}</span></b></div>
          <template v-if="hasCategoryScores(selectedRecord)">
            <div><span>物料</span><b>{{ recordCategoryScore(selectedRecord, 'MATERIAL') }} / 37</b></div>
            <div><span>卫生</span><b>{{ recordCategoryScore(selectedRecord, 'HYGIENE') }} / 63</b></div>
            <div><span>服务</span><b>{{ recordCategoryScore(selectedRecord, 'SERVICE') }} / 100</b></div>
          </template>
          <div v-if="hasMigrationAudit(selectedRecord)"><span>历史迁移审计</span><b>{{ migrationAuditText(selectedRecord) }}</b></div>
          <div v-if="requiresManualReview(selectedRecord)"><span>自动修复</span><b class="danger">快照不完整，待人工复核</b></div>
        </div>
        <p v-if="!recordScore(selectedRecord).valid" class="inspection-repair-note danger">评分数据待修复：{{ recordScore(selectedRecord).error }}</p>
        <p v-if="selectedRecord.repairReason" class="inspection-repair-note">修复说明：{{ selectedRecord.repairReason }}</p>
        <div class="inspection-detail-section">
          <h4>扣分项明细</h4>
          <div v-if="!recordItemResults(selectedRecord).some((item) => itemDeduction(item) > 0)" class="empty-state compact">暂无扣分项。</div>
          <ul v-else class="inspection-detail-list">
            <li v-for="item in recordItemResults(selectedRecord).filter((row) => itemDeduction(row) > 0)" :key="item.standardItemId">
              {{ item.categoryName || item.dimension }} · {{ item.code || '未编号' }} {{ item.title }}：扣 {{ itemDeduction(item) }} 分；{{ item.deductionReason || '未填写扣分原因' }}
            </li>
          </ul>
        </div>
        <div class="inspection-detail-section">
          <h4>{{ recordSnapshotHeading(selectedRecord) }}</h4>
          <div v-if="!recordItemResults(selectedRecord).length" class="empty-state compact">{{ recordSnapshotEmptyText(selectedRecord) }}</div>
          <div v-else class="inspection-table-wrap">
            <table class="inspection-table snapshot-table">
              <thead><tr><th>条款</th><th class="r">标准分</th><th class="r">实得分</th><th class="r">实际扣分</th><th>扣分原因</th><th>现场证据</th><th>状态</th></tr></thead>
              <tbody>
                <tr v-for="item in recordItemResults(selectedRecord)" :key="`snapshot-${item.standardItemId}`">
                  <td class="snapshot-clause">
                    <b>{{ item.title || item.description || '—' }}</b>
                    <small>{{ item.categoryName || item.dimension || '未分类' }} · {{ item.code || '未编号' }} · {{ riskLabel(item.riskLevel) }}</small>
                  </td>
                  <td class="r">{{ formatScore(item.standardScore) }}</td>
                  <td class="r snapshot-actual-score">实得 {{ formatScore(item.actualScore) }} / {{ formatScore(item.standardScore) }}</td>
                  <td class="r"><span :class="{ 'snapshot-deducted': itemDeduction(item) > 0 }">{{ itemDeduction(item) > 0 ? `扣 ${formatScore(itemDeduction(item))} 分` : '未扣分' }}</span></td>
                  <td>{{ item.deductionReason || '—' }}</td>
                  <td>
                    <div v-if="recordClausePhotos(selectedRecord, item).length" class="inspection-evidence-list">
                      <article v-for="photo in recordClausePhotos(selectedRecord, item)" :key="`${item.standardItemId}-${photo.attachmentId || photo.fileName}`" class="inspection-evidence-item">
                        <button
                          class="inspection-evidence-thumb"
                          type="button"
                          :disabled="detailPhotoState(photo).status !== 'ready'"
                          :aria-label="`预览 ${photo.fileName || '现场证据'}`"
                          @click="openDetailPhotoPreview(photo, $event)"
                        >
                          <img
                            v-if="detailPhotoState(photo).status === 'ready' && detailPhotoState(photo).url"
                            :src="detailPhotoState(photo).url"
                            :alt="`${photo.fileName || '现场证据'} 缩略图`"
                            @error="markDetailPhotoFailed(photo)"
                          />
                          <LoaderCircle v-else-if="detailPhotoState(photo).status === 'loading'" class="spin" :size="18" />
                          <XCircle v-else :size="18" />
                        </button>
                        <span>
                          <b :title="photo.fileName">{{ photo.fileName || '现场照片' }}</b>
                          <small v-if="detailPhotoMessage(photo)" :class="`evidence-${detailPhotoState(photo).status}`">{{ detailPhotoMessage(photo) }}</small>
                        </span>
                        <button
                          v-if="['failed', 'missing'].includes(detailPhotoState(photo).status) && photo.attachmentId"
                          class="evidence-retry"
                          type="button"
                          @click="retryDetailPhoto(photo)"
                        >重试</button>
                      </article>
                    </div>
                    <small v-else class="evidence-unlinked">未关联证据</small>
                  </td>
                  <td><span class="evidence-status" :class="{ effective: itemDeduction(item) > 0, pending: clauseEvidenceStatus(selectedRecord, item).includes('未计分'), unlinked: clauseEvidenceStatus(selectedRecord, item) === '未关联证据' }">{{ clauseEvidenceStatus(selectedRecord, item) }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <div v-if="recordUnlinkedPhotos(selectedRecord).length" class="inspection-detail-section">
          <h4>未关联现场证据</h4>
          <p class="inspection-evidence-note">以下图片未在任何条款的图片关联中出现，系统不会自动归因或影响扣分；请由老板或督导人工选择历史条款。</p>
          <div class="inspection-evidence-list unlinked-evidence-list">
            <article v-for="photo in recordUnlinkedPhotos(selectedRecord)" :key="`unlinked-${photo.attachmentId || photo.fileName}`" class="inspection-evidence-item">
              <span class="inspection-evidence-thumb" :aria-label="`${photo.fileName || '未关联证据'} 尚未关联历史条款，不能预览原图`">
                <XCircle :size="18" />
              </span>
              <span><b>{{ photo.fileName || '现场照片' }}</b><small class="evidence-unlinked">{{ unlinkedPhotoMessage(photo) }}</small></span>
              <button
                v-if="canSupplementHistoricalEvidence"
                class="evidence-associate"
                type="button"
                :disabled="detailEvidenceCandidatesLoading"
                @click="openHistoricalEvidenceDialog(photo)"
              >
                <Link2 v-if="hasValidUnlinkedHistoricalEvidence(photo)" :size="14" />
                <ImagePlus v-else :size="14" />
                {{ detailEvidenceCandidatesLoading ? '正在核验证据' : unlinkedEvidenceActionLabel(photo) }}
              </button>
              <span v-else class="evidence-status unlinked">{{ unlinkedPhotoMessage(photo) }}</span>
            </article>
          </div>
        </div>
        <div v-if="recordPendingAiPhotos(selectedRecord).length" class="inspection-detail-section">
          <h4>AI 待确认识别结果（不计分）</h4>
          <p class="inspection-evidence-note">AI 建议独立展示；未匹配或待确认的结果不会写入本次得分、扣分明细或历史快照。</p>
          <div class="inspection-detail-detections">
            <article v-for="photo in recordPendingAiPhotos(selectedRecord)" :key="`ai-${detectionKey(photo.detection) || photo.attachmentId || photo.fileName}`" class="inspection-detail-detection ai-pending-card">
              <button v-if="recordPhotoIsExplicitlyLinked(selectedRecord, photo)" class="inspection-evidence-thumb" type="button" :disabled="detailPhotoState(photo).status !== 'ready'" :aria-label="`预览 ${photo.fileName || 'AI识别图片'}`" @click="openDetailPhotoPreview(photo, $event)">
                <img v-if="detailPhotoState(photo).status === 'ready' && detailPhotoState(photo).url" :src="detailPhotoState(photo).url" :alt="`${photo.fileName || 'AI识别图片'} 缩略图`" @error="markDetailPhotoFailed(photo)" />
                <LoaderCircle v-else-if="detailPhotoState(photo).status === 'loading'" class="spin" :size="18" />
                <XCircle v-else :size="18" />
              </button>
              <span v-else class="inspection-evidence-thumb" :aria-label="`${photo.fileName || 'AI识别图片'} 尚未关联历史条款，不能预览原图`"><XCircle :size="18" /></span>
              <div>
                <span>模型识别结果 · {{ photo.fileName || '现场图片' }}</span>
                <b>{{ detectionClauseLabel(photo.detection) }}</b>
                <small>{{ detectionCount(photo.detection) ? `识别到 ${detectionCount(photo.detection)} 个疑似问题` : '未识别到明确问题' }} · 置信度 {{ confidenceText(detectionConfidence(photo.detection)) }}</small>
                <small class="inspection-model-only-hint">{{ photoAiStatus(photo) }}</small>
                <small v-if="recordPhotoIsExplicitlyLinked(selectedRecord, photo) && detailPhotoMessage(photo)" :class="`evidence-${detailPhotoState(photo).status}`">{{ detailPhotoMessage(photo) }}</small>
                <small v-else-if="!recordPhotoIsExplicitlyLinked(selectedRecord, photo)" class="evidence-unlinked">待人工关联历史条款，不能预览原图</small>
              </div>
              <div class="inspection-detail-decision">
                <span class="decision-pending">{{ photoAiStatus(photo) }}</span>
                <button
                  v-if="canManageInspection && !hasRepairMarker(selectedRecord) && detectionDecisionStatus(photo.detection) === 'PENDING'"
                  class="primary-button"
                  type="button"
                  :disabled="!detectionClauseId(photo.detection) || isPersistedDecisionBusy(detectionKey(photo.detection))"
                  @click="decidePersistedDetection(photo, 'confirm')"
                >人工确认并计分</button>
              </div>
            </article>
          </div>
        </div>
        <div class="inspection-detail-section">
          <h4>整改要求</h4>
          <p>{{ selectedRecord.note || (!hasRepairMarker(selectedRecord) ? deductionDetails(selectedRecord)[0]?.requirement : '') || '暂无整改要求。' }}</p>
        </div>
      </section>

      <section v-else class="content-card inspection-table-card">
        <div class="inspection-table-head">
          <span class="inspection-section-title">
            {{ filterBrand || filterMonth ? `筛选后 ${filteredRecords.length} 次` : `共 ${filteredRecords.length} 次巡检` }}
          </span>
        </div>
        <div v-if="loading && !records.length" class="empty-state">正在读取巡检记录...</div>
        <div v-else-if="!filteredRecords.length" class="empty-state">
          <b>{{ filterBrand || filterMonth ? '当前筛选下没有巡检记录' : '暂无巡检记录' }}</b>
        </div>
        <div v-else class="inspection-table-wrap">
          <table class="inspection-table">
            <thead>
              <tr>
                <th>日期</th>
                <th>门店</th>
                <th>品牌</th>
                <th>督导</th>
                <th class="r">得分</th>
                <th>结果</th>
                <th class="r">扣分项</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="record in filteredRecords" :key="record.id" class="clickable-row" @click="openRecordDetail(String(record.id))">
                <td>{{ record.inspectionDate || '—' }}</td>
                <td><b>{{ record.storeName || record.storeId }}</b></td>
                <td>
                  <span class="brand-pill" :style="{ background: brandMeta(recordBrandName(record)).soft, color: brandMeta(recordBrandName(record)).color }">
                    <span class="dotc" :style="{ background: brandMeta(recordBrandName(record)).color }" />
                    {{ recordBrandName(record) || '—' }}
                  </span>
                </td>
                <td>{{ record.inspector || '—' }}</td>
                <td class="r score-cell" :class="{ danger: resultIsFailed(record) }" :title="recordScore(record).error">{{ recordScore(record).scoreText }}</td>
                <td><span class="result-text" :class="{ danger: resultIsFailed(record) }">{{ resultLabel(record) }}</span></td>
                <td class="r muted-cell">{{ deductionCount(record) }}项</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <div v-else-if="activeTab === 'create'" class="inspection-create-view">
      <section class="content-card inspection-form-card">
        <div class="inspection-card-title">
          <div>
            <span class="inspection-section-title">基础信息</span>
            <h3>本次巡检门店</h3>
          </div>
        </div>
        <div class="inspection-create-form">
          <label>
            <span>品牌</span>
            <select v-model="draft.brandName" @change="handleDraftBrandChange">
              <option v-for="brand in brandOptions" :key="brand.name" :value="brand.name">{{ brand.name }}</option>
            </select>
          </label>
          <label>
            <span>门店</span>
            <select v-model="draft.storeId">
              <option v-if="!createStoreOptions.length" value="">该品牌暂无门店</option>
              <option v-for="store in createStoreOptions" :key="store.id" :value="store.id">{{ store.name }}</option>
            </select>
          </label>
          <label>
            <span>巡检日期</span>
            <input v-model="draft.inspectionDate" type="date" />
          </label>
          <label>
            <span>督导人</span>
            <input v-model.trim="draft.inspector" placeholder="填写巡检人姓名" />
          </label>
          <label>
            <span>满分基准</span>
              <input :value="INSPECTION_MAX_SCORE" type="number" readonly aria-readonly="true" />
          </label>
          <div class="inspection-standard-note" :class="{ muted: standardReady, invalid: hasGlobalStandard && !standardReady }">
            <div>
              <span v-if="standardReady">{{ globalStandard.title }} {{ globalStandard.version }} · 105条（物料40 / 卫生47 / 服务18） · {{ globalStandardStats.fullScore }}分（满分200） · 合格线{{ globalStandardStats.passScore }}分 · 红线 {{ globalStandardStats.redlineCount }} 条 · 黄线 {{ globalStandardStats.yellowLineCount }} 条</span>
              <span v-else-if="globalStandard.validationError" class="danger">当前标准未通过校验，只能只读查看，不能保存巡检。</span>
              <span v-else>暂无稽核标准</span>
            </div>
            <button class="secondary-button" type="button" :disabled="refreshingStandard" @click="refreshStandard">
              <RefreshCw :size="15" />{{ refreshingStandard ? '刷新中...' : '刷新标准' }}
            </button>
          </div>
          <ul v-if="hasGlobalStandard && !standardReady" class="inspection-standard-diagnostics compact">
            <li v-for="item in invalidStandardDiagnostics" :key="`${item.categoryCode || item.categoryName}-${item.message}`">
              <b>{{ item.categoryName }}</b><span>{{ item.message }}</span>
            </li>
          </ul>
        </div>
      </section>

      <section class="content-card inspection-upload-box" @dragover.prevent @drop.prevent="handlePhotoDrop">
        <div class="inspection-card-title">
          <div>
            <span class="inspection-section-title">现场照片证据 / 拍照识别（辅助）</span>
            <h3>上传后自动识别，督导最终确认</h3>
          </div>
          <div class="inspection-upload-actions">
            <button class="detection-service-status" :class="detectionServiceUp ? 'up' : 'down'" type="button" :disabled="checkingDetectionService" @click="refreshDetectionService">
              <CheckCircle2 v-if="detectionServiceUp" :size="15" />
              <AlertTriangle v-else :size="15" />
              {{ detectionServiceMessage }}
            </button>
            <label class="primary-button upload-button" :class="{ disabled: !standardReady }">
              <ImagePlus :size="16" />
              {{ uploading ? '上传识别中...' : '拍照/选图' }}
              <input type="file" accept="image/*" capture="environment" multiple :disabled="uploading || !standardReady" @change="handleAttachmentChange" />
            </label>
          </div>
        </div>
        <div v-if="!draft.photos.length" class="inspection-detection-empty">
          <Sparkles :size="22" />
          <div>
            <b>待识别</b>
            <span>选择现场照片后，系统会先保存附件，再调用本地模型生成标注图和扣分建议。</span>
          </div>
        </div>
        <div v-else class="inspection-detection-list" aria-live="polite">
          <article v-for="(photo, index) in draft.photos" :key="photo.attachmentId || photo.url || photo.fileName" class="inspection-detection-item">
            <header>
              <div>
                <b>{{ photo.fileName || '现场照片' }}</b>
                <a :href="photoHref(photo)" target="_blank" rel="noreferrer">查看原图</a>
              </div>
              <button class="icon-button" type="button" aria-label="移除照片" @click="removePhoto(index)"><Trash2 :size="14" /></button>
            </header>

            <div v-if="photo.detectionStatus === 'detecting'" class="inspection-detection-progress">
              <LoaderCircle :size="20" class="spin" />
              <div><b>模型识别中</b><span>正在生成标注图片和问题建议，请稍候。</span></div>
            </div>

            <div v-else-if="photo.detectionStatus === 'failed'" class="inspection-detection-failed">
              <XCircle :size="20" />
              <div><b>识别服务不可用</b><span>{{ photo.detectionError || '本次识别失败，不能按合格处理。' }}</span></div>
              <button class="secondary-button" type="button" @click="retryPhotoDetection(photo)"><RotateCw :size="15" />重新识别</button>
            </div>

            <div v-else-if="photo.detection" class="inspection-detection-result">
              <div class="inspection-detection-preview">
                <img v-if="photo.detection.annotated_image" :src="photo.detection.annotated_image" :alt="`${photo.fileName} 模型标注结果`" />
                <div v-else class="inspection-preview-missing">模型未返回标注图</div>
              </div>
              <div class="inspection-detection-content">
                <div class="inspection-model-summary">
                  <span class="detection-count" :class="{ clear: detectionCount(photo.detection) === 0 }">
                    {{ detectionCount(photo.detection) ? `发现 ${detectionCount(photo.detection)} 个疑似问题` : '未识别到明显问题' }}
                  </span>
                  <span>模型结论：{{ photo.detection.auto_status || photo.detection.review_status || '待人工确认' }}</span>
                </div>
                <div v-if="detectionCount(photo.detection)" class="inspection-detection-rule">
                  <div class="inspection-clause-match" :class="{ unmatched: !detectionClauseId(photo.detection) }">
                    <span>匹配正式条款</span>
                    <b>{{ detectionClauseLabel(photo.detection) }}</b>
                  </div>
                  <dl class="inspection-deduction-metrics">
                    <div><dt>200分制建议扣分</dt><dd>{{ detectionClauseId(photo.detection) ? `${formatScore(detectionFinalDeduction(photo.detection))} 分` : '待匹配' }}</dd></div>
                    <div><dt>识别置信度</dt><dd>{{ confidenceText(detectionConfidence(photo.detection)) }}</dd></div>
                  </dl>
                  <p class="inspection-model-only-hint">模型仅建议；最终扣分由服务端按正式条款规则计算，需督导确认。</p>
                </div>
                <ul v-if="photo.detection.detections?.length" class="inspection-model-issues">
                  <li v-for="(item, detectionIndex) in photo.detection.detections" :key="`${item.class_name}-${detectionIndex}`">
                    <b>{{ detectionLabel(item) }}</b>
                    <span>置信度 {{ confidenceText(item.confidence) }}</span>
                    <span>{{ item.on_floor ? '地面区域' : item.class_name === 'corner_dust' ? '边角区域' : '现场区域' }}</span>
                  </li>
                </ul>
                <p v-if="photo.detection.deduction_content" class="inspection-model-advice">{{ photo.detection.deduction_content }}</p>
                <div class="inspection-review-actions">
                  <span v-if="photo.reviewStatus === 'pending'" class="review-pending">模型结果仅供参考，请督导确认</span>
                  <span v-else-if="photo.reviewStatus === 'accepted'" class="review-confirmed"><CheckCircle2 :size="15" />督导已确认，保存后按条款扣分</span>
                  <span v-else class="review-dismissed"><CheckCircle2 :size="15" />督导已确认无问题</span>
                  <button
                    v-if="detectionCount(photo.detection) > 0 && photo.reviewStatus === 'pending'"
                    class="primary-button"
                    type="button"
                    :disabled="!detectionClauseId(photo.detection) || isDraftReviewBusy(photo) || saving"
                    :title="detectionClauseId(photo.detection) ? '确认后将在保存时由服务端计算扣分' : '尚未匹配正式条款，不能直接确认'"
                    @click="confirmModelIssue(photo)"
                  >
                    确认问题并加入扣分
                  </button>
                  <button v-if="photo.reviewStatus === 'pending'" class="secondary-button" type="button" :disabled="isDraftReviewBusy(photo) || saving" @click="dismissModelIssue(photo)">
                    {{ detectionCount(photo.detection) > 0 ? '人工确认无问题' : '确认未发现问题' }}
                  </button>
                  <button v-else class="secondary-button" type="button" :disabled="isDraftReviewBusy(photo) || saving" @click="undoModelReview(photo)">
                    撤销本次确认
                  </button>
                </div>
              </div>
            </div>
          </article>
        </div>
        <div v-if="draft.photos.length" class="inspection-assistant-note">
          <AlertTriangle :size="15" />模型仅提供疑似问题；确认请求不会提交前端计算的最终扣分，正式分值由服务端按条款规则生成。
        </div>
      </section>

      <section class="content-card inspection-score-bar">
        <div v-for="category in categoryScores" :key="category.code" class="category-score">
          <span>{{ category.name }}得分</span>
          <b>{{ category.score }}<small> 分（满分{{ category.fullScore }}）</small></b>
        </div>
        <div>
          <span>总分</span>
          <b :class="scoreTone">{{ scoreDisplay }}<small v-if="recognitionReady"> / {{ INSPECTION_MAX_SCORE }}</small></b>
        </div>
        <div>
          <span>扣分合计</span>
          <b :class="recognitionReady ? 'bad' : 'pending'">{{ recognitionReady ? `-${deductionTotal}` : '—' }}</b>
        </div>
        <div>
          <span>结果</span>
          <b :class="scoreTone">{{ inspectionResultText }}</b>
          <small>合格线 {{ passLine }} 分，最终以保存后服务端判定为准</small>
        </div>
        <div v-if="yellowRiskCount" class="yellow-risk-summary">
          <span>黄线风险</span>
          <b>{{ yellowRiskCount }} 项</b>
          <small>黄线仅扣分，不触发一票否决</small>
        </div>
        <button class="primary-button save-button" type="button" :disabled="saving || uploading || Boolean(saveBlockedReason)" :title="saveBlockedReason" @click="submitRecord">
          <Upload :size="16" />
          {{ saving ? '保存中...' : '保存巡检' }}
        </button>
        <small v-if="saveBlockedReason" class="inspection-save-hint">{{ saveBlockedReason }}</small>
      </section>

      <section
        v-for="group in globalStandard.groups"
        :key="group.categoryCode"
        class="content-card inspection-item-results-card"
        :class="{ 'is-readonly': !standardReady }"
        :data-category="group.categoryCode"
      >
        <div class="inspection-card-title inspection-category-head">
          <div>
            <span class="inspection-section-title">{{ group.dim }}标准</span>
            <h3>{{ group.items.length }} 条完整检查项</h3>
          </div>
          <strong>{{ categoryScores.find((item) => item.code === group.categoryCode)?.score || 0 }} / {{ group.fullScore }} 分（200分制）</strong>
        </div>
        <div class="inspection-table-wrap">
          <table class="inspection-table inspection-item-table">
            <thead>
              <tr>
                <th>条款编号</th>
                <th>检查内容 / 方法</th>
                <th>风险</th>
                <th class="r">标准分</th>
                <th>实际分</th>
                <th>扣分原因</th>
                <th>现场照片</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="item in draftItemsForCategory(group.categoryCode)" :key="item.standardItemId">
                <tr :class="[`risk-${String(item.riskLevel || 'NORMAL').toLowerCase()}`, { deducted: itemDeduction(item) > 0 || item.issueFound }]">
                  <td><b>{{ item.code || '—' }}</b></td>
                  <td>
                    <b>{{ item.title }}</b>
                    <small v-if="item.checkMethod">检查方法：{{ item.checkMethod }}</small>
                  </td>
                  <td><span class="risk-chip" :class="String(item.riskLevel || 'NORMAL').toLowerCase()">{{ riskLabel(item.riskLevel) }}</span></td>
                  <td class="r">{{ safeNumber(item.standardScore) }}</td>
                  <td>
                    <label v-if="item.riskLevel === 'RED'" class="redline-found-toggle">
                      <input type="checkbox" :checked="item.issueFound" :disabled="!standardReady" @change="toggleRedLineIssue(item, ($event.target as HTMLInputElement).checked)" />
                      {{ item.issueFound ? '发现问题' : '未命中' }}
                    </label>
                    <input
                      v-else
                      v-model.number="item.actualScore"
                      class="item-score-input"
                      type="number"
                      min="0"
                      :max="safeNumber(item.standardScore)"
                      step="0.01"
                      :aria-label="`${item.code || item.title}实际分`"
                      :data-standard-code="item.code"
                      :disabled="!standardReady"
                      @change="normalizeItemScore(item)"
                    />
                    <small v-if="itemDeduction(item) > 0" class="deduction-value">扣 {{ itemDeduction(item) }} 分</small>
                  </td>
                  <td>
                    <input
                      v-model.trim="item.deductionReason"
                      class="item-reason-input"
                      :class="{ invalid: (item.issueFound || itemDeduction(item) > 0) && !item.deductionReason?.trim() }"
                      :placeholder="item.issueFound || itemDeduction(item) > 0 ? '必填：写清现场问题' : '无扣分'"
                      :aria-label="`${item.code || item.title}扣分原因`"
                      :disabled="!standardReady"
                      @blur="normalizeItemScore(item)"
                    />
                  </td>
                  <td>
                    <div v-if="draft.photos.some((photo) => photo.attachmentId)" class="item-photo-options">
                      <label v-for="photo in draft.photos.filter((row) => row.attachmentId)" :key="photo.attachmentId">
                        <input
                          type="checkbox"
                          :checked="itemPhotoSelected(item, photo.attachmentId)"
                          :disabled="!standardReady"
                          @change="toggleItemPhoto(item, photo.attachmentId, ($event.target as HTMLInputElement).checked)"
                        />
                        问题：{{ photo.fileName }}
                      </label>
                      <label v-for="photo in draft.photos.filter((row) => row.attachmentId)" :key="`after-${photo.attachmentId}`">
                        <input
                          type="checkbox"
                          :checked="itemAfterPhotoSelected(item, photo.attachmentId)"
                          :disabled="!standardReady"
                          @change="toggleAfterPhoto(item, photo.attachmentId, ($event.target as HTMLInputElement).checked)"
                        />
                        整改后：{{ photo.fileName }}
                      </label>
                    </div>
                    <small v-else>未关联照片</small>
                  </td>
                </tr>
                <tr v-if="itemNeedsRectification(item)" class="inspection-rectification-row">
                  <td colspan="7">
                    <div class="inspection-rectification-fields">
                      <label><span>负责人</span><input v-model.trim="item.responsiblePerson" :disabled="!standardReady" placeholder="整改负责人" /></label>
                      <label><span>整改期限</span><input v-model="item.rectificationDeadline" :disabled="!standardReady" type="date" /></label>
                      <label>
                        <span>整改状态</span>
                        <select v-model="item.rectificationStatus" :disabled="!standardReady">
                          <option value="待整改">待整改</option>
                          <option value="整改中">整改中</option>
                          <option value="待复核">待复核</option>
                          <option value="已完成">已完成</option>
                        </select>
                      </label>
                      <label><span>复核结果</span><input v-model.trim="item.reviewResult" :disabled="!standardReady" placeholder="待复核或填写结果" /></label>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </section>

      <section class="content-card inspection-deduction-card">
        <div class="inspection-card-title">
          <div>
            <span class="inspection-section-title">人工 / 模型调整记录（{{ draftRows.length }}）</span>
            <h3>已应用到完整条款的扣分</h3>
          </div>
        </div>
        <div v-if="!draftRows.length" class="empty-state compact">
          暂无扣分项。
        </div>
        <div v-else class="inspection-table-wrap">
          <table class="inspection-table deduction-table">
            <thead>
              <tr>
                <th>维度</th>
                <th>检查条款</th>
                <th class="r">扣分</th>
                <th>问题描述</th>
                <th>是否红线项</th>
                <th class="r">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in draftRows" :key="row.item.id || `${row.kind}-${row.index}`">
                <td>{{ row.item.dim || '—' }}</td>
                <td>
                  <b>{{ row.item.code ? `${row.item.code} ` : '' }}{{ row.item.item || '现场问题' }}</b>
                  <small v-if="row.item.method">{{ row.item.method }}</small>
                </td>
                <td class="r">{{ row.item.redline ? '—' : `${safeNumber(row.item.deduct)}分` }}</td>
                <td>{{ row.item.issue || '—' }}</td>
                <td><span class="result-text" :class="{ danger: row.item.redline }">{{ row.item.redline ? '是' : '否' }}</span></td>
                <td class="r">
                  <button class="icon-button" type="button" aria-label="删除扣分项" @click="removeDraftRow(row.kind, row.index)">
                    <Trash2 :size="15" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="content-card inspection-add-card">
        <div class="inspection-card-title">
          <div>
            <span class="inspection-section-title">添加扣分项</span>
            <h3>把现场问题记清楚</h3>
          </div>
        </div>
        <div class="inspection-add-form">
          <label>
            <span>维度</span>
            <select v-model="deductionForm.dimension">
              <option v-for="dimension in draftDimensions" :key="dimension" :value="dimension">{{ dimension }}</option>
            </select>
          </label>
          <label v-if="clausesForDimension.length">
            <span>检查条款</span>
            <select v-model="deductionForm.clauseKey">
              <option v-for="(clause, index) in clausesForDimension" :key="`${clause.code}-${index}`" :value="String(index)">
                {{ clause.code ? `${clause.code} ` : '' }}{{ clause.item }}（建议{{ clause.score }}分）
              </option>
            </select>
          </label>
          <label v-else>
            <span>检查条款</span>
            <input v-model.trim="deductionForm.manualItem" placeholder="例如：吧台地面有水渍" />
          </label>
          <label>
            <span>扣分</span>
            <input v-model.number="deductionForm.deduct" type="number" min="1" :max="draft.fullScore" placeholder="填写扣分" />
          </label>
          <label class="wide">
            <span>问题描述</span>
            <input v-model.trim="deductionForm.issue" placeholder="写给门店看的问题，例如：开封物料未贴时效标签" />
          </label>
          <button class="primary-button add-button" type="button" :disabled="!standardReady" @click="addDeduction">
            <Plus :size="16" />
            添加
          </button>
        </div>
      </section>

      <section class="content-card inspection-note-card">
        <label>
          <span>整改要求 / 备注</span>
          <textarea v-model.trim="draft.note" rows="3" placeholder="写清楚整改要求、责任人或复查时间" />
        </label>
        <div class="inspection-form-actions">
          <button class="secondary-button" type="button" @click="resetDraft">清空表单</button>
          <button class="primary-button" type="button" :disabled="saving || uploading || Boolean(saveBlockedReason)" :title="saveBlockedReason" @click="submitRecord">{{ saving ? '保存中...' : '保存巡检' }}</button>
        </div>
      </section>
    </div>

    <div v-else class="inspection-standards-view">
      <section class="content-card inspection-standard-summary">
        <div>
          <span>标准版本</span>
          <b>{{ selectedStandard.version || '未启用' }}</b>
        </div>
        <div>
          <span>红线项</span>
          <b>{{ selectedStandardStats.redlineCount }} 条</b>
        </div>
        <div>
          <span>黄线项</span>
          <b>{{ selectedStandardStats.yellowLineCount }} 条</b>
        </div>
        <div>
          <span>评分条款</span>
          <b>{{ selectedStandardStats.clauseCount }} 条</b>
        </div>
        <div>
          <span>满分基准</span>
          <b>{{ selectedStandardStats.fullScore }} 分</b>
        </div>
        <div>
          <span>合格线</span>
          <b>{{ selectedStandardStats.passScore }} 分</b>
        </div>
      </section>

      <section v-if="selectedStandard.validationError" class="content-card inspection-standard-error" role="alert">
        <div class="inspection-standard-error-head">
          <div>
            <b>标准校验未通过，以下条款仅供核对</b>
            <span>系统不会隐藏原始条款，也不会允许用错误标准保存新巡检。</span>
          </div>
          <button class="secondary-button" type="button" :disabled="refreshingStandard" @click="refreshStandard">
            <RefreshCw :size="15" />{{ refreshingStandard ? '刷新中...' : '刷新标准' }}
          </button>
        </div>
        <ul class="inspection-standard-diagnostics">
          <li v-for="item in invalidStandardDiagnostics" :key="`${item.categoryCode || item.categoryName}-${item.message}`">
            <b>{{ item.categoryName }}</b>
            <span>{{ item.message }}</span>
          </li>
        </ul>
      </section>

      <section v-if="hasGlobalStandard" class="inspection-standard-category-audit" aria-label="标准分类核对">
        <article
          v-for="item in selectedStandardStats.categoryStats"
          :key="item.categoryCode"
          :class="{ invalid: item.actualCount !== item.expectedCount || item.actualScore !== item.expectedScore }"
        >
          <div><b>{{ item.categoryName }}</b><span>{{ item.actualCount }} 条 / {{ item.actualScore }} 分</span></div>
          <small>权威标准：{{ item.expectedCount }} 条 / {{ item.expectedScore }} 分</small>
        </article>
      </section>

      <section v-if="!hasGlobalStandard" class="content-card">
        <div class="empty-state">
          <b>暂无稽核标准</b>
          <button class="secondary-button" type="button" :disabled="refreshingStandard" @click="refreshStandard">
            <RefreshCw :size="15" />{{ refreshingStandard ? '刷新中...' : '刷新标准' }}
          </button>
        </div>
      </section>

      <template v-if="hasGlobalStandard">
        <section class="content-card inspection-standard-groups">
          <div class="inspection-card-title">
            <div>
              <span class="inspection-section-title">完整评分条款</span>
              <h3>按物料、卫生、服务查看</h3>
            </div>
          </div>
          <div class="dimension-filter">
            <button type="button" :class="{ on: !standardDimension }" @click="standardDimension = ''">全部维度</button>
            <button
              v-for="group in selectedStandard.groups"
              :key="group.dim"
              type="button"
              :class="{ on: standardDimension === group.dim }"
              @click="standardDimension = group.dim"
            >
              {{ group.dim }}
            </button>
          </div>
          <div class="standard-group-stack">
            <div v-for="group in selectedStandardGroups" :key="group.dim" class="standard-group">
              <div class="standard-group-head">
                <h4>{{ group.dim }}</h4>
                <span>{{ group.items.length }} 条 · 合计 {{ group.items.reduce((sum: number, item: InspectionStandardClause) => sum + safeNumber(item.score), 0) }} 分</span>
              </div>
              <div class="inspection-table-wrap">
                <table class="inspection-table standards-table" :aria-label="`${group.dim}标准条款`">
                  <thead>
                    <tr>
                      <th>条款编号</th>
                      <th>条款标题</th>
                      <th>判定说明</th>
                      <th class="r">标准分</th>
                      <th>风险级别</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(clause, index) in group.items" :key="`${group.dim}-${clause.code}-${index}`" :class="`risk-${clause.riskLevel.toLowerCase()}`">
                      <td class="muted-cell">{{ clause.code || '—' }}</td>
                      <td><b>{{ clause.item }}</b></td>
                      <td>{{ clause.method || '—' }}</td>
                      <td class="r">{{ clause.score }}分</td>
                      <td><span class="risk-chip" :class="clause.riskLevel.toLowerCase()">{{ riskLabel(clause.riskLevel) }}</span></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </section>
      </template>
    </div>
  </section>

  <Teleport to="body">
    <InspectionHistoricalEvidenceDialog
      v-if="historicalEvidenceDialog && selectedRecord && canSupplementHistoricalEvidence"
      :record="selectedRecord"
      :items="recordItemResults(selectedRecord)"
      :initial-attachment-id="historicalEvidenceDialog.attachmentId"
      :source-photo-index="historicalEvidenceDialog.sourcePhotoIndex"
      :initial-mode="historicalEvidenceDialog.mode"
      @close="closeHistoricalEvidenceDialog"
      @saved="onHistoricalEvidenceSaved"
    />
  </Teleport>

  <Teleport to="body">
    <div v-if="detailPhotoPreview" class="inspection-image-preview-backdrop" @click.self="closeDetailPhotoPreview">
      <section ref="detailPhotoPreviewDialog" class="inspection-image-preview-dialog" tabindex="-1" role="dialog" aria-modal="true" aria-label="现场图片预览">
        <header>
          <b>{{ detailPhotoPreview.fileName }}</b>
          <button type="button" class="icon-button" aria-label="关闭图片预览" @click="closeDetailPhotoPreview"><XCircle :size="20" /></button>
        </header>
        <img :src="detailPhotoPreview.url" :alt="detailPhotoPreview.fileName" />
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.inspection-workbench-page {
  display: grid;
  gap: 16px;
  max-width: 1180px;
}

.inspection-head {
  align-items: flex-start;
}

.inspection-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.inspection-workbench-page .primary-button {
  width: auto;
  margin-top: 0;
}

.inspection-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.inspection-metric {
  min-width: 0;
}

.inspection-metric .lab {
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
}

.inspection-metric .val {
  display: block;
  margin-top: 3px;
  color: var(--ink);
  font-size: 25px;
  line-height: 1.15;
  font-weight: 900;
  font-variant-numeric: tabular-nums;
}

.inspection-metric .val small,
.metric-warning {
  font-size: 12px;
  font-weight: 700;
}

.metric-warning {
  display: block;
  margin-top: 5px;
  color: var(--warn);
}

.inspection-metric:first-child {
  border-color: var(--primary);
  background: var(--primary);
}

.inspection-metric:first-child .lab,
.inspection-metric:first-child .val {
  color: #fff;
}

.inspection-metric:nth-child(2) .val {
  color: var(--good);
}

.inspection-section-title {
  display: block;
  margin: 0 0 6px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 700;
}

.brand-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 22px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}

.brand-pill .dotc {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.inspection-segment,
.inspection-brand-filter,
.dimension-filter {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  border-radius: 12px;
  background: #eef1f6;
}

.inspection-segment-button,
.inspection-filter-chip,
.dimension-filter button {
  min-height: 34px;
  padding: 7px 12px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
  cursor: pointer;
  white-space: nowrap;
}

.inspection-segment-button.on {
  background: #fff;
  color: var(--primary-dark);
  box-shadow: 0 8px 20px -12px rgba(0, 0, 0, 0.4);
}

.inspection-filter-chip.on,
.dimension-filter button.on {
  background: var(--brand-soft, #fff);
  color: var(--brand-color, var(--primary-dark));
  box-shadow: 0 8px 20px -14px currentColor;
}

.inspection-records-view,
.inspection-create-view,
.inspection-standards-view,
.standard-group-stack {
  display: grid;
  gap: 14px;
}

.inspection-metrics .inspection-metric:nth-child(4) .val.red {
  color: var(--bad);
}

.inspection-filter-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.inspection-month-select {
  min-width: 140px;
}

.inspection-table-card,
.inspection-detail-card,
.inspection-form-card,
.inspection-upload-box,
.inspection-item-results-card,
.inspection-deduction-card,
.inspection-add-card,
.inspection-redline-card,
.inspection-note-card {
  overflow: hidden;
}

.inspection-table-wrap {
  width: 100%;
  overflow-x: auto;
}

.inspection-table {
  width: 100%;
  min-width: 760px;
  border-collapse: collapse;
}

.inspection-table .r {
  text-align: right;
}

.clickable-row {
  cursor: pointer;
}

.score-cell {
  color: var(--good);
  font-weight: 900;
  font-variant-numeric: tabular-nums;
}

.muted-cell {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.result-text {
  color: var(--good);
  font-weight: 900;
}

.result-text.danger,
.danger,
.bad {
  color: var(--bad);
}

.good {
  color: var(--good);
}

.warn {
  color: var(--warn);
}

.inspection-table-head {
  margin-bottom: 6px;
}

.inspection-workbench-page .empty-state.compact {
  min-height: 0;
  margin-top: 0;
  padding: 12px 0;
  place-items: start;
  text-align: left;
}

.inspection-detail-head,
.inspection-card-title,
.standard-group-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.inspection-detail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.inspection-card-title h3,
.inspection-detail-head h3,
.standard-group-head h4 {
  margin: 0;
  font-size: 18px;
  font-weight: 900;
}

.inspection-detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.inspection-detail-grid div {
  min-height: 66px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fafbfc;
}

.inspection-detail-grid span,
.inspection-score-bar span,
.inspection-standard-summary span {
  display: block;
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
}

.inspection-detail-grid b {
  display: block;
  margin-top: 4px;
  color: var(--ink);
  font-size: 14px;
}

.repair-status {
  display: inline-flex !important;
  align-items: center;
  min-height: 24px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary) !important;
  font-size: 12px !important;
}

.repair-status.repaired {
  background: var(--ds-success-soft);
  color: var(--good) !important;
}

.repair-status.review {
  background: var(--ds-warning-soft);
  color: #77440d !important;
}

.inspection-repair-note {
  margin: -2px 0 14px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  line-height: 1.55;
}

.inspection-detail-section {
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.inspection-detail-section + .inspection-detail-section {
  margin-top: 12px;
}

.inspection-detail-section h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.inspection-detail-section p {
  margin: 0;
  color: var(--muted);
  white-space: pre-wrap;
}

.inspection-detail-list {
  margin: 0;
  padding-left: 18px;
  color: var(--ink);
}

.inspection-photo-list,
.inspection-photo-grid {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.inspection-photo-list a,
.inspection-photo-item {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  gap: 6px;
  padding: 4px 9px;
  border-radius: 99px;
  background: #f1f5f9;
  color: var(--ink);
  font-size: 12px;
  font-weight: 800;
  text-decoration: none;
}

.inspection-photo-item button,
.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  color: var(--bad);
  cursor: pointer;
}

.inspection-create-form,
.inspection-add-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.inspection-create-form label,
.inspection-add-form label,
.inspection-note-card label {
  display: grid;
  gap: 6px;
}

.inspection-create-form label span,
.inspection-add-form label span,
.inspection-note-card label span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.inspection-standard-note,
.inspection-add-form .wide {
  grid-column: 1 / -1;
}

.inspection-standard-note {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 9px 12px;
  border: 1px solid rgba(215, 131, 34, 0.24);
  border-radius: 10px;
  background: #fff7ed;
  color: var(--warn);
  font-size: 13px;
  font-weight: 800;
  justify-content: space-between;
}

.inspection-standard-note.muted {
  border-color: rgba(30, 158, 106, 0.22);
  background: #eaf8f0;
  color: var(--good);
}

.inspection-upload-box {
  border-style: dashed;
  border-width: 1.5px;
  background: #f8fafc;
}

.upload-button {
  width: fit-content;
  overflow: hidden;
  position: relative;
}

.upload-button input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.inspection-upload-actions,
.inspection-review-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.detection-service-status {
  display: inline-flex;
  min-height: 34px;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.detection-service-status.up {
  border-color: rgba(25, 135, 84, 0.3);
  background: var(--ds-success-soft);
  color: var(--good);
}

.inspection-standard-note.invalid {
  border-color: rgba(217, 79, 61, 0.32);
  background: var(--ds-danger-soft);
  color: var(--bad);
}

.inspection-standard-note > div {
  min-width: 0;
  flex: 1;
}

.inspection-standard-note .secondary-button {
  flex: none;
}

.inspection-standard-diagnostics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 8px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.inspection-standard-diagnostics.compact {
  grid-column: 1 / -1;
  margin-top: 0;
}

.inspection-standard-diagnostics li {
  display: grid;
  gap: 3px;
  padding: 9px 10px;
  border: 1px solid rgba(217, 79, 61, 0.25);
  border-radius: 8px;
  background: #fff;
}

.inspection-standard-diagnostics b {
  color: var(--bad);
  font-size: 13px;
}

.inspection-standard-diagnostics span {
  color: var(--ds-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.upload-button.disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.detection-service-status.down {
  border-color: rgba(220, 53, 69, 0.3);
  background: var(--ds-danger-soft);
  color: var(--bad);
}

.inspection-detection-empty,
.inspection-detection-progress,
.inspection-detection-failed,
.inspection-assistant-note {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 8px;
}

.inspection-detection-empty {
  border: 1px dashed var(--ds-line-strong);
  background: #fff;
  color: var(--primary-dark);
}

.inspection-detection-empty b,
.inspection-detection-empty span,
.inspection-detection-progress b,
.inspection-detection-progress span,
.inspection-detection-failed b,
.inspection-detection-failed span {
  display: block;
}

.inspection-detection-empty span,
.inspection-detection-progress span,
.inspection-detection-failed span {
  margin-top: 3px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.inspection-detection-list {
  display: grid;
  gap: 12px;
}

.inspection-detection-item {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}

.inspection-detection-item > header {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
}

.inspection-detection-item > header > div {
  min-width: 0;
}

.inspection-detection-item > header b {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-detection-item > header a {
  color: var(--primary-dark);
  font-size: 12px;
  font-weight: 700;
}

.inspection-detection-progress,
.inspection-detection-failed {
  min-height: 88px;
  border-radius: 0;
}

.inspection-detection-progress {
  color: var(--primary-dark);
}

.inspection-detection-failed {
  background: var(--ds-danger-soft);
  color: var(--bad);
}

.inspection-detection-failed > div {
  min-width: 0;
  flex: 1;
}

.spin {
  animation: inspection-spin 0.9s linear infinite;
}

@keyframes inspection-spin {
  to { transform: rotate(360deg); }
}

.inspection-detection-result {
  display: grid;
  grid-template-columns: minmax(260px, 0.9fr) minmax(320px, 1.1fr);
  min-width: 0;
}

.inspection-detection-preview {
  min-height: 250px;
  background: #edf2f1;
}

.inspection-detection-preview img {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 250px;
  max-height: 420px;
  object-fit: contain;
}

.inspection-preview-missing {
  display: grid;
  min-height: 250px;
  place-items: center;
  color: var(--muted);
}

.inspection-detection-content {
  min-width: 0;
  padding: 14px;
}

.inspection-model-summary {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.inspection-model-summary span,
.inspection-model-issues li {
  padding: 5px 8px;
  border-radius: 6px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  font-size: 12px;
  font-weight: 700;
}

.inspection-model-summary .detection-count {
  background: var(--ds-warning-soft);
  color: var(--warn);
}

.inspection-model-summary .detection-count.clear {
  background: var(--ds-success-soft);
  color: var(--good);
}

.inspection-detection-rule {
  display: grid;
  gap: 10px;
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfdfd;
}

.inspection-clause-match {
  display: grid;
  gap: 3px;
}

.inspection-clause-match span,
.inspection-deduction-metrics dt {
  color: var(--muted);
  font-size: 12px;
}

.inspection-clause-match b {
  color: var(--ink);
  font-size: 14px;
}

.inspection-clause-match.unmatched b {
  color: var(--bad);
}

.inspection-deduction-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
}

.inspection-deduction-metrics > div {
  min-width: 0;
  padding: 8px;
  border-radius: 6px;
  background: var(--ds-surface-muted);
}

.inspection-deduction-metrics dt,
.inspection-deduction-metrics dd {
  margin: 0;
}

.inspection-deduction-metrics dd {
  margin-top: 4px;
  color: var(--ink);
  font-weight: 900;
  font-variant-numeric: tabular-nums;
}

.inspection-model-only-hint {
  margin: 0;
  color: #77440d;
  font-size: 12px;
  line-height: 1.5;
}

.inspection-model-issues {
  display: grid;
  gap: 6px;
  margin: 12px 0;
  padding: 0;
  list-style: none;
}

.inspection-model-issues li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.inspection-model-issues li b {
  color: var(--ink);
}

.inspection-model-advice {
  margin: 12px 0;
  color: var(--ink);
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.inspection-review-actions {
  justify-content: flex-start;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.inspection-review-actions > span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-right: auto;
  font-size: 12px;
  font-weight: 800;
}

.review-pending { color: var(--warn); }
.review-confirmed { color: var(--bad); }
.review-dismissed { color: var(--good); }

.inspection-assistant-note {
  margin-top: 12px;
  background: var(--ds-warning-soft);
  color: #77440d;
  font-size: 12px;
  font-weight: 700;
}

.inspection-detail-detections {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.inspection-detail-detection {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fbfdfd;
}

.inspection-detail-detection > div:first-child {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.inspection-detail-detection span,
.inspection-detail-detection small {
  color: var(--muted);
  font-size: 12px;
}

.inspection-detail-decision {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.inspection-detail-decision .decision-confirmed { color: var(--bad); }
.inspection-detail-decision .decision-revoked { color: var(--good); }

@media (max-width: 860px) {
  .inspection-deduction-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .inspection-detail-detection { align-items: flex-start; flex-direction: column; }
  .ai-pending-card { grid-template-columns: 50px minmax(0, 1fr); }
  .ai-pending-card .inspection-detail-decision { grid-column: 1 / -1; }
}

.pending {
  color: var(--muted);
}

.inspection-score-bar {
  position: relative;
}

.inspection-save-hint {
  grid-column: 1 / -1;
  color: var(--warn);
  text-align: right;
}

.inspection-score-bar,
.inspection-standard-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  align-items: center;
  gap: 12px;
}

.inspection-score-bar > div,
.inspection-standard-summary > div {
  min-height: 72px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fafbfc;
}

.inspection-score-bar b,
.inspection-standard-summary b {
  display: block;
  margin-top: 4px;
  font-size: 24px;
  line-height: 1.1;
  font-weight: 900;
  font-variant-numeric: tabular-nums;
}

.inspection-score-bar small {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
}

.inspection-score-bar b small,
.inspection-category-head strong {
  display: inline;
  color: var(--muted);
  font-size: 13px;
  font-weight: 700;
}

.yellow-risk-summary {
  border-color: #e7c36b !important;
  background: var(--ds-warning-soft) !important;
}

.yellow-risk-summary b {
  color: var(--warn);
}

.inspection-category-head {
  align-items: center;
}

.inspection-category-head strong {
  color: var(--ink);
  font-size: 18px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.inspection-item-table {
  min-width: 1260px;
}

.inspection-item-table th:nth-child(2),
.inspection-item-table td:nth-child(2),
.inspection-item-table th:nth-child(6),
.inspection-item-table td:nth-child(6) {
  white-space: normal;
}

.inspection-item-table td {
  vertical-align: top;
}

.inspection-item-results-card.is-readonly input,
.inspection-item-results-card.is-readonly select {
  cursor: not-allowed;
}

.inspection-item-results-card.is-readonly .inspection-category-head::after {
  content: '只读核对';
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--ds-warning-soft);
  color: #77440d;
  font-size: 12px;
  font-weight: 800;
}

.inspection-item-table td small {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  line-height: 1.45;
}

.inspection-item-table tr.risk-red.deducted,
.inspection-item-table tr.risk-red:has(.redline-found-toggle input:checked) {
  background: var(--ds-danger-soft);
}

.inspection-item-table tr.risk-yellow.deducted {
  background: var(--ds-warning-soft);
}

.risk-chip {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.risk-chip.red {
  background: var(--ds-danger-soft);
  color: var(--bad);
}

.risk-chip.yellow {
  background: var(--ds-warning-soft);
  color: var(--warn);
}

.item-score-input {
  width: 86px;
  min-height: 36px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.item-reason-input {
  width: 100%;
  min-width: 210px;
}

.item-reason-input.invalid {
  border-color: var(--bad);
  background: var(--ds-danger-soft);
}

.deduction-value {
  color: var(--bad) !important;
  font-weight: 800;
}

.redline-found-toggle,
.item-photo-options label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink);
  font-size: 12px;
  font-weight: 700;
}

.redline-found-toggle {
  min-height: 36px;
  white-space: nowrap;
}

.item-photo-options {
  display: grid;
  gap: 5px;
  min-width: 190px;
  max-height: 116px;
  overflow-y: auto;
}

.inspection-rectification-row td {
  padding-top: 8px;
  background: var(--ds-surface-muted);
}

.inspection-rectification-fields {
  display: grid;
  grid-template-columns: repeat(4, minmax(170px, 1fr));
  gap: 10px;
}

.inspection-rectification-fields label {
  display: grid;
  gap: 5px;
}

.inspection-rectification-fields span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
}

.snapshot-table {
  min-width: 1240px;
}

.snapshot-clause {
  min-width: 180px;
}

.snapshot-clause b,
.snapshot-clause small {
  display: block;
}

.snapshot-clause small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 12px;
}

.snapshot-actual-score,
.snapshot-deducted {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.snapshot-deducted {
  color: var(--bad);
  font-weight: 800;
}

.inspection-evidence-list {
  display: grid;
  min-width: 210px;
  gap: 7px;
}

.inspection-evidence-item {
  display: grid;
  grid-template-columns: 50px minmax(0, 1fr) auto;
  align-items: center;
  gap: 7px;
  min-height: 54px;
  padding: 5px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fff;
}

.inspection-evidence-thumb {
  display: grid;
  width: 50px;
  height: 42px;
  place-items: center;
  overflow: hidden;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: var(--ds-surface-muted);
  color: var(--muted);
}

.inspection-evidence-thumb:not(:disabled):hover,
.inspection-evidence-thumb:not(:disabled):focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.inspection-evidence-thumb:disabled {
  cursor: default;
  opacity: 1;
}

.inspection-evidence-thumb img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.inspection-evidence-item > span {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.inspection-evidence-item b,
.inspection-evidence-item small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-evidence-item b {
  font-size: 12px;
}

.inspection-evidence-item small,
.inspection-evidence-note {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.inspection-evidence-item .evidence-forbidden,
.inspection-evidence-item .evidence-missing,
.inspection-evidence-item .evidence-failed {
  color: var(--bad);
}

.evidence-retry {
  min-height: 28px;
  padding: 0 6px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--primary-dark);
  font-size: 12px;
  font-weight: 700;
}

.evidence-retry:hover,
.evidence-retry:focus-visible {
  background: var(--primary-soft);
}

.evidence-associate {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 28px;
  padding: 0 7px;
  border: 1px solid var(--primary);
  border-radius: 5px;
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.evidence-associate:hover,
.evidence-associate:focus-visible {
  background: #d8efed;
}

.evidence-unlinked {
  color: var(--warn);
  font-size: 12px;
  font-weight: 700;
}

.evidence-status {
  display: inline-flex;
  max-width: 230px;
  padding: 4px 7px;
  border-radius: 5px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  font-size: 12px;
  font-weight: 700;
  line-height: 1.45;
}

.evidence-status.effective {
  background: var(--ds-danger-soft);
  color: var(--bad);
}

.evidence-status.pending,
.evidence-status.unlinked {
  background: var(--ds-warning-soft);
  color: #77440d;
}

.inspection-evidence-note {
  margin: -2px 0 10px;
}

.unlinked-evidence-list {
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
}

.unlinked-evidence-list .evidence-status {
  justify-self: end;
}

.ai-pending-card {
  display: grid;
  grid-template-columns: 50px minmax(0, 1fr) auto;
}

.inspection-image-preview-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1510;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(9, 14, 14, .72);
}

.inspection-image-preview-dialog {
  display: grid;
  width: min(960px, calc(100vw - 48px));
  max-height: calc(100vh - 48px);
  overflow: hidden;
  border-radius: 8px;
  background: #fff;
}

.inspection-image-preview-dialog header {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
}

.inspection-image-preview-dialog header b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-image-preview-dialog > img {
  display: block;
  max-width: 100%;
  max-height: calc(100vh - 102px);
  margin: auto;
  object-fit: contain;
}

.inspection-standard-error {
  border-color: var(--bad);
  background: var(--ds-danger-soft);
}

.inspection-standard-error-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.inspection-standard-error-head b,
.inspection-standard-error-head span {
  display: block;
}

.inspection-standard-error-head b {
  color: var(--bad);
}

.inspection-standard-error-head span {
  margin-top: 4px;
  color: #6f2b25;
  font-size: 13px;
}

.inspection-standard-category-audit {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.inspection-standard-category-audit article {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
}

.inspection-standard-category-audit article.invalid {
  border-color: rgba(217, 79, 61, 0.32);
  background: var(--ds-danger-soft);
}

.inspection-standard-category-audit article div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.inspection-standard-category-audit article span {
  color: var(--ink);
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.inspection-standard-category-audit article small {
  display: block;
  margin-top: 4px;
  color: var(--muted);
}

.save-button,
.add-button {
  min-height: 46px;
}

.deduction-table small {
  margin-top: 4px;
  color: var(--muted);
}

.deduction-table {
  min-width: 920px;
}

.deduction-table th,
.deduction-table td {
  white-space: nowrap;
}

.deduction-table th:nth-child(2),
.deduction-table td:nth-child(2),
.deduction-table th:nth-child(4),
.deduction-table td:nth-child(4) {
  white-space: normal;
}

.deduction-table th:nth-child(1),
.deduction-table td:nth-child(1) {
  width: 100px;
}

.deduction-table th:nth-child(5),
.deduction-table td:nth-child(5) {
  width: 92px;
  text-align: center;
}

.inspection-redline-list,
.standard-redline-list {
  display: grid;
  gap: 6px;
}

.inspection-redline-list {
  max-height: 360px;
  overflow: auto;
  padding-right: 3px;
}

.redline-option,
.standard-redline-row {
  display: flex;
  gap: 9px;
  padding: 9px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}

.redline-option.on {
  border-color: rgba(217, 79, 61, 0.35);
  background: #fff4f2;
}

.redline-option input {
  margin-top: 4px;
}

.redline-option b,
.standard-redline-row b {
  display: block;
  font-size: 13px;
  line-height: 1.45;
}

.redline-option small,
.standard-redline-row small {
  display: block;
  margin-top: 3px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.45;
}

.inspection-note-card textarea {
  resize: vertical;
}

.inspection-form-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.standard-redline-row {
  display: grid;
}

.standard-group {
  display: grid;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.standard-group:first-child {
  padding-top: 0;
  border-top: 0;
}

.standard-group-head {
  margin-bottom: 0;
}

.standard-group-head span {
  color: var(--muted);
  font-size: 13px;
  font-weight: 800;
}

.standards-table td {
  line-height: 1.45;
}

@media (max-width: 900px) {
  .inspection-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .inspection-create-form,
  .inspection-add-form,
  .inspection-detail-grid,
  .inspection-score-bar,
  .inspection-standard-summary,
  .inspection-standard-category-audit {
    grid-template-columns: 1fr;
  }

  .inspection-filter-row {
    align-items: stretch;
  }

  .inspection-detection-result {
    grid-template-columns: 1fr;
  }

  .inspection-rectification-fields {
    grid-template-columns: repeat(2, minmax(170px, 1fr));
  }
}

@media (max-width: 720px) {
  .inspection-metrics {
    grid-template-columns: 1fr;
  }

  .inspection-head-actions,
  .inspection-segment,
  .inspection-brand-filter,
  .dimension-filter,
  .inspection-month-select,
  .save-button {
    width: 100%;
  }

  .inspection-segment,
  .inspection-brand-filter,
  .dimension-filter {
    overflow-x: auto;
  }

  .inspection-card-title,
  .inspection-detail-head,
  .standard-group-head,
  .inspection-standard-error-head,
  .inspection-standard-note {
    display: grid;
  }

  .inspection-upload-actions,
  .inspection-detail-actions,
  .detection-service-status,
  .upload-button,
  .inspection-review-actions .primary-button,
  .inspection-review-actions .secondary-button {
    width: 100%;
  }

  .inspection-detail-actions,
  .inspection-rectification-fields {
    grid-template-columns: 1fr;
  }

  .inspection-detail-actions {
    display: grid;
  }

  .inspection-model-issues li {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .spin { animation: none; }
}
</style>
