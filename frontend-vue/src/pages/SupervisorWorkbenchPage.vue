<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { AlertTriangle, CheckCircle2, ClipboardList, ImagePlus, RefreshCw, XCircle } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'
import InspectionHistoricalEvidenceDialog from '../components/inspection/InspectionHistoricalEvidenceDialog.vue'
import InspectionHistoricalEvidencePanel from '../components/inspection/InspectionHistoricalEvidencePanel.vue'
import InspectionRecordDetailSummary from '../components/inspection/InspectionRecordDetailSummary.vue'
import InspectionStandardReadinessNotice from '../components/inspection/InspectionStandardReadinessNotice.vue'
import InspectionPhotoDetectionList from '../components/inspection/InspectionPhotoDetectionList.vue'
import InspectionScoreSummary from '../components/inspection/InspectionScoreSummary.vue'
import InspectionClauseEditor from '../components/inspection/InspectionClauseEditor.vue'
import InspectionDeductionRecords from '../components/inspection/InspectionDeductionRecords.vue'
import InspectionManualDeductionForm from '../components/inspection/InspectionManualDeductionForm.vue'
import InspectionDraftActions from '../components/inspection/InspectionDraftActions.vue'
import InspectionRecordSnapshotTable from '../components/inspection/InspectionRecordSnapshotTable.vue'
import InspectionStandardCatalog from '../components/inspection/InspectionStandardCatalog.vue'
import InspectionRecordList from '../components/inspection/InspectionRecordList.vue'
import InspectionRecordMetrics from '../components/inspection/InspectionRecordMetrics.vue'
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
  toInspectionStandardSet,
  type InspectionStandardSet,
} from '../data/inspectionStandards'
import { formatScore, inspectionScoreView, INSPECTION_MAX_SCORE } from '../utils/inspectionScore'
import {
  useInspectionDraft,
  type InspectionDeductionDetail,
  type InspectionDraftPhoto,
} from '../composables/useInspectionDraft'
import {
  compareInspectionRecords as compareInspectionRecord,
  currentInspectionMonth as currentMonth,
  inspectionBrandMeta as brandMeta,
  inspectionCategoryCodeForDimension as categoryCodeForDimension,
  inspectionItemDeduction as itemDeduction,
  inspectionItemNeedsRectification as itemNeedsRectification,
  isInspectionItemAfterPhotoSelected as itemAfterPhotoSelected,
  isInspectionItemPhotoSelected as itemPhotoSelected,
  normalizeInspectionBrandName as normalizeBrandName,
  normalizeInspectionItemScore as normalizeItemScore,
  roundInspectionScore as roundScore,
  safeInspectionNumber as safeNumber,
  todayInspectionDate as todayDate,
  toggleInspectionItemAfterPhoto as toggleAfterPhoto,
  toggleInspectionItemPhoto as toggleItemPhoto,
  toggleInspectionRedLineIssue as toggleRedLineIssue,
} from '../utils/inspectionDraft'

type InspectionTab = 'records' | 'create' | 'standards'

type DraftPhoto = InspectionDraftPhoto
type PersistedDraftPhoto = InspectionDraftPhoto & { attachmentId: number }
type DeductionDetail = InspectionDeductionDetail

type DetailPhotoLoadStatus = 'loading' | 'ready' | 'forbidden' | 'missing' | 'failed'

interface DetailPhotoPreview {
  status: DetailPhotoLoadStatus
  url?: string
  message?: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

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
const inspectionStandard = ref<InspectionStandardSet>(emptyInspectionStandard)

const canManageInspection = computed(() => auth.hasPermission(PERMISSIONS.INSPECTION_MANAGE))
const canReadDailyLoss = computed(() => auth.hasPermission(PERMISSIONS.DAILY_LOSS_READ))
// The server remains authoritative. This only prevents a non-manager from seeing an
// action they cannot complete; the backend remains authoritative for every permission check.
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

const {
  draft,
  deductionForm,
  brandOptions,
  createStoreOptions,
  globalStandard,
  globalStandardStats,
  draftDimensions,
  clausesForDimension,
  selectedClause,
  hasGlobalStandard,
  standardReady,
  passLine,
  invalidStandardDiagnostics,
  categoryScores,
  manualCurrentScore,
  deductionTotal,
  redLineHit,
  yellowRiskCount,
  recognitionReady,
  scoreDisplay,
  inspectionResultText,
  scoreTone,
  saveBlockedReason,
  draftRows,
  ensureDraftStore,
  ensureDeductionForm,
  ensureDraftItemResults,
  fillDeductionFromClause,
  handleDraftBrandChange,
  resetDraft,
  draftItemsForCategory,
  manualCategoryScore,
} = useInspectionDraft({
  records,
  stores,
  backendBrands,
  inspectionStandard,
  filterBrand,
  detectionKey,
  detectionClauseId,
  releaseDraftPhotos: () => releaseDraftPhotos(),
  resetDraftReviewState: () => {
    draftReviewBusyKeys.value = []
    clearDraftReviewTimers()
  },
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

/**
 * 督导巡店的固定经营规则：每个营业门店每自然周至少完成 2 次巡检。
 * 周一为统计起点；门店状态和巡检记录均来自后端，页面只做展示汇总。
 */
const weeklyInspectionTarget = computed(() => {
  const today = new Date()
  const mondayOffset = (today.getDay() + 6) % 7
  const weekStart = new Date(today)
  weekStart.setDate(today.getDate() - mondayOffset)
  weekStart.setHours(0, 0, 0, 0)
  const weekEnd = new Date(weekStart)
  weekEnd.setDate(weekStart.getDate() + 6)
  const formatDate = (date: Date) => {
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
  }
  const start = formatDate(weekStart)
  const end = formatDate(weekEnd)
  const activeStores = stores.value.filter((store) => (store.status || '营业中') === '营业中')
  const visitCountByStore = new Map<string, number>()
  records.value
    .filter((record) => record.inspectionDate >= start && record.inspectionDate <= end)
    .forEach((record) => visitCountByStore.set(record.storeId, (visitCountByStore.get(record.storeId) || 0) + 1))
  const completed = Array.from(visitCountByStore.values()).reduce((sum, count) => sum + Math.min(count, 2), 0)
  const required = activeStores.length * 2
  const pendingStoreNames = activeStores
    .filter((store) => (visitCountByStore.get(store.id) || 0) < 2)
    .map((store) => store.name)
  return { start, end, activeStoreCount: activeStores.length, completed, required, remaining: Math.max(required - completed, 0), pendingStoreNames }
})

const selectedRecord = computed(() => {
  if (!selectedRecordId.value) return null
  return detailRecord.value && String(detailRecord.value.id) === selectedRecordId.value
    ? detailRecord.value
    : null
})

const detectionServiceUp = computed(() => detectionService.value?.status === 'UP')
const detectionServiceMessage = computed(() => {
  if (checkingDetectionService.value) return '正在检查识别服务...'
  if (detectionServiceUp.value) return '识别服务正常，模型已就绪'
  return detectionService.value?.message || '尚未检查识别服务'
})
const selectedStandard = computed(() => globalStandard.value)
const selectedStandardStats = computed(() => globalStandardStats.value)
function recordStore(record: InspectionRecord) {
  return stores.value.find((store) => store.id === record.storeId)
}

function recordBrandName(record: InspectionRecord) {
  return normalizeBrandName(record.brandName || record.brand || recordStore(record)?.brandName)
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

function currentRecordPhotoIsExplicitlyLinked(photo: DraftPhoto) {
  const record = selectedRecord.value
  return Boolean(record && recordPhotoIsExplicitlyLinked(record, photo))
}

function canConfirmCurrentRecordAiDecision(photo: DraftPhoto) {
  const record = selectedRecord.value
  return Boolean(
    record
    && canManageInspection.value
    && !hasRepairMarker(record)
    && detectionDecisionStatus(photo.detection) === 'PENDING'
    && detectionClauseId(photo.detection),
  )
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
    photo.detection = await detectInspectionPhoto(photo.sourceFile, draft.storeId)
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

  if (!clause) {
    errorMessage.value = '请从最新稽核标准中选择具体条款。'
    return
  }
  const target = draft.itemResults.find((item) => item.standardItemId === clause.id)
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
          <RouterLink v-if="canReadDailyLoss" class="ghost-button inspection-daily-loss-link" to="/daily-loss">
            <ClipboardList :size="16" />每日报损
          </RouterLink>
        </div>
      </template>
    </PageHeader>

    <div v-if="errorMessage" class="error-box">{{ errorMessage }}</div>
    <div v-if="actionMessage" class="success-box">{{ actionMessage }}</div>

    <div v-if="activeTab === 'records'" class="inspection-records-view">
      <section class="content-card weekly-inspection-target" aria-label="本周巡店目标">
        <div>
          <p class="eyebrow">本周巡店目标（{{ weeklyInspectionTarget.start }} 至 {{ weeklyInspectionTarget.end }}）</p>
          <h3>每家营业门店每周巡检 2 次</h3>
          <p>{{ weeklyInspectionTarget.activeStoreCount }} 家营业门店，已完成 {{ weeklyInspectionTarget.completed }} / {{ weeklyInspectionTarget.required }} 次，还需 {{ weeklyInspectionTarget.remaining }} 次。</p>
        </div>
        <p v-if="weeklyInspectionTarget.pendingStoreNames.length" class="weekly-inspection-pending">
          未达标：{{ weeklyInspectionTarget.pendingStoreNames.join('、') }}
        </p>
        <p v-else class="weekly-inspection-complete">本周所有营业门店均已达标</p>
      </section>
      <InspectionRecordMetrics
        :total="summary.total"
        :month-count="summary.monthCount"
        :average-score="summary.averageScore"
        :invalid-score-count="summary.invalidScoreCount"
        :redline-count="summary.redlineCount"
        :max-score="INSPECTION_MAX_SCORE"
        :format-score="formatScore"
      />


      <InspectionRecordList
        :brand-options="brandOptions"
        :month-options="monthOptions"
        :filter-brand="filterBrand"
        :filter-month="filterMonth"
        :selected-record-id="selectedRecordId"
        :records="filteredRecords"
        :loading="loading"
        :record-brand-name="recordBrandName"
        :brand-meta="brandMeta"
        :record-score="recordScore"
        :result-is-failed="resultIsFailed"
        :result-label="resultLabel"
        :deduction-count="deductionCount"
        @update:brand="setBrandFilter"
        @update:month="setMonthFilter"
        @select="openRecordDetail"
      >
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
        <InspectionRecordDetailSummary
          :record="selectedRecord"
          :can-supplement="canSupplementHistoricalEvidence"
          :exporting="exportingRecordId === String(selectedRecord.id)"
          :brand-name="recordBrandName"
          :standard-version="recordStandardVersion"
          :score="recordScore"
          :format-score="formatScore"
          :result-is-failed="resultIsFailed"
          :result-label="resultLabel"
          :red-line-count="recordRedLineCount"
          :yellow-line-count="recordYellowLineCount"
          :repair-status-label="repairStatusLabel"
          :repair-status-tone="repairStatusTone"
          :has-category-scores="hasCategoryScores"
          :category-score="recordCategoryScore"
          :has-migration-audit="hasMigrationAudit"
          :migration-audit-text="migrationAuditText"
          :requires-manual-review="requiresManualReview"
          :deduction-items="recordItemResults(selectedRecord).filter((item) => itemDeduction(item) > 0)"
          :item-deduction="itemDeduction"
          @supplement="openHistoricalEvidenceDialog()"
          @export="exportRecord(selectedRecord)"
          @close="closeRecordDetail"
        />
        <InspectionRecordSnapshotTable
          :record="selectedRecord"
          :items="recordItemResults(selectedRecord)"
          :heading="recordSnapshotHeading(selectedRecord)"
          :empty-text="recordSnapshotEmptyText(selectedRecord)"
          :format-score="formatScore"
          :item-deduction="itemDeduction"
          :risk-label="riskLabel"
          :clause-photos="recordClausePhotos"
          :clause-evidence-status="clauseEvidenceStatus"
          :photo-state="detailPhotoState"
          :photo-message="detailPhotoMessage"
          @preview="openDetailPhotoPreview"
          @retry="retryDetailPhoto"
          @image-error="markDetailPhotoFailed"
        />
        <InspectionHistoricalEvidencePanel
          :unlinked-photos="recordUnlinkedPhotos(selectedRecord)"
          :pending-ai-photos="recordPendingAiPhotos(selectedRecord)"
          :can-supplement="canSupplementHistoricalEvidence"
          :candidates-loading="detailEvidenceCandidatesLoading"
          :is-explicitly-linked="currentRecordPhotoIsExplicitlyLinked"
          :has-valid-historical-evidence="hasValidUnlinkedHistoricalEvidence"
          :action-label="unlinkedEvidenceActionLabel"
          :unlinked-message="unlinkedPhotoMessage"
          :photo-state="detailPhotoState"
          :photo-message="detailPhotoMessage"
          :ai-status="photoAiStatus"
          :detection-key="detectionKey"
          :detection-clause-label="detectionClauseLabel"
          :detection-count="detectionCount"
          :detection-confidence-text="(photo) => confidenceText(detectionConfidence(photo))"
          :can-confirm-ai-decision="canConfirmCurrentRecordAiDecision"
          :is-ai-decision-busy="(photo) => isPersistedDecisionBusy(detectionKey(photo.detection))"
          @supplement="openHistoricalEvidenceDialog"
          @preview="openDetailPhotoPreview"
          @retry="retryDetailPhoto"
          @image-error="markDetailPhotoFailed"
          @confirm-ai-decision="(photo) => decidePersistedDetection(photo, 'confirm')"
        />
        <div class="inspection-detail-section">
          <h4>整改要求</h4>
          <p>{{ selectedRecord.note || (!hasRepairMarker(selectedRecord) ? deductionDetails(selectedRecord)[0]?.requirement : '') || '暂无整改要求。' }}</p>
        </div>
      </section>

      </InspectionRecordList>

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
          <InspectionStandardReadinessNotice
            :standard="globalStandard"
            :stats="globalStandardStats"
            :ready="standardReady"
            :has-standard="hasGlobalStandard"
            :diagnostics="invalidStandardDiagnostics"
            :refreshing="refreshingStandard"
            @refresh="refreshStandard"
          />
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
        <InspectionPhotoDetectionList
          :photos="draft.photos"
          :saving="saving"
          :photo-href="photoHref"
          :detection-count="detectionCount"
          :detection-clause-id="detectionClauseId"
          :detection-clause-label="detectionClauseLabel"
          :detection-final-deduction="detectionFinalDeduction"
          :detection-confidence="detectionConfidence"
          :confidence-text="confidenceText"
          :detection-label="detectionLabel"
          :format-score="formatScore"
          :review-busy="isDraftReviewBusy"
          @remove="removePhoto"
          @retry="retryPhotoDetection"
          @confirm="confirmModelIssue"
          @dismiss="dismissModelIssue"
          @undo="undoModelReview"
        />
      </section>

      <InspectionScoreSummary
        :category-scores="categoryScores"
        :score-tone="scoreTone"
        :score-display="scoreDisplay"
        :recognition-ready="recognitionReady"
        :deduction-total="deductionTotal"
        :result-text="inspectionResultText"
        :pass-line="passLine"
        :yellow-risk-count="yellowRiskCount"
        :saving="saving"
        :uploading="uploading"
        :save-blocked-reason="saveBlockedReason"
        @save="submitRecord"
      />

      <InspectionDeductionRecords
        :rows="draftRows"
        :safe-number="safeNumber"
        @remove="removeDraftRow"
      />


      <InspectionManualDeductionForm
        :form="deductionForm"
        :dimensions="draftDimensions"
        :clauses="clausesForDimension"
        :full-score="draft.fullScore"
        :standard-ready="standardReady"
        @add="addDeduction"
      />


      <InspectionDraftActions
        :note="draft.note"
        :saving="saving"
        :uploading="uploading"
        :save-blocked-reason="saveBlockedReason"
        @update:note="draft.note = $event"
        @reset="resetDraft"
        @save="submitRecord"
      />

      <InspectionClauseEditor
        :groups="globalStandard.groups"
        :category-scores="categoryScores"
        :photos="draft.photos"
        :standard-ready="standardReady"
        :items-for-category="draftItemsForCategory"
        :safe-number="safeNumber"
        :risk-label="riskLabel"
        :item-deduction="itemDeduction"
        :item-needs-rectification="itemNeedsRectification"
        :item-photo-selected="itemPhotoSelected"
        :item-after-photo-selected="itemAfterPhotoSelected"
        @toggle-redline="toggleRedLineIssue"
        @normalize-score="normalizeItemScore"
        @toggle-photo="toggleItemPhoto"
        @toggle-after-photo="toggleAfterPhoto"
      />

    </div>

    <InspectionStandardCatalog
      :standard="selectedStandard"
      :stats="selectedStandardStats"
      :has-standard="hasGlobalStandard"
      :diagnostics="invalidStandardDiagnostics"
      :refreshing="refreshingStandard"
      :safe-number="safeNumber"
      :risk-label="riskLabel"
      @refresh="refreshStandard"
    />

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

.inspection-daily-loss-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  text-decoration: none;
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
.standard-group-head h4 {
  margin: 0;
  font-size: 18px;
  font-weight: 900;
}

.inspection-score-bar span,
.inspection-standard-summary span {
  display: block;
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
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
  min-height: 44px;
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

.weekly-inspection-target {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, .7fr);
  gap: 18px;
  align-items: center;
  margin-bottom: 16px;
  border-color: color-mix(in srgb, var(--primary) 22%, var(--border));
}

.weekly-inspection-target h3,
.weekly-inspection-target p { margin: 0; }
.weekly-inspection-target h3 { margin: 4px 0 7px; }
.weekly-inspection-target > div > p:not(.eyebrow) { color: var(--muted); }
.weekly-inspection-pending { color: #a75a12; font-weight: 700; line-height: 1.6; }
.weekly-inspection-complete { color: #14775d; font-weight: 700; }

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
  .inspection-score-bar,
  .inspection-standard-summary,
  .inspection-standard-category-audit {
    grid-template-columns: 1fr;
  }

  .weekly-inspection-target { grid-template-columns: 1fr; }

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
