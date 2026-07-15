import { computed, reactive, type Ref } from 'vue'
import type { BrandInfo, StoreInfo } from '../api/operations'
import type { InspectionCategoryCode, InspectionDetectionResult, InspectionItemResult, InspectionRecord } from '../api/inspection'
import {
  getInspectionDimensions,
  inspectionStandardStats,
  type InspectionStandardClause,
  type InspectionStandardSet,
} from '../data/inspectionStandards'
import {
  inspectionBrandPalette,
  inspectionCanonicalBrandNames,
  inspectionCategoryCodeForDimension,
  normalizeInspectionBrandName,
  roundInspectionScore,
  safeInspectionNumber,
  todayInspectionDate,
} from '../utils/inspectionDraft'
import { INSPECTION_MAX_SCORE, INSPECTION_PASS_SCORE } from '../utils/inspectionScore'

export interface InspectionDraftPhoto {
  attachmentId?: number
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

export interface InspectionDeductionDetail {
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

export interface InspectionDeductionForm {
  dimension: string
  clauseKey: string
  manualItem: string
  deduct: number | null
  issue: string
}

export interface InspectionBrandOption {
  name: string
  color: string
  soft: string
}

export function useInspectionDraft(options: {
  records: Ref<InspectionRecord[]>
  stores: Ref<StoreInfo[]>
  backendBrands: Ref<BrandInfo[]>
  inspectionStandard: Ref<InspectionStandardSet>
  filterBrand: Ref<string>
  detectionKey: (result?: InspectionDetectionResult) => string
  detectionClauseId: (result?: InspectionDetectionResult) => number | undefined
  releaseDraftPhotos: () => void
  resetDraftReviewState: () => void
}) {
  const draft = reactive({
    standardVersionId: undefined as number | undefined,
    standardVersion: '',
    brandName: '茹菓',
    storeId: '',
    inspectionDate: todayInspectionDate(),
    inspector: '',
    fullScore: INSPECTION_MAX_SCORE,
    note: '',
    photos: [] as InspectionDraftPhoto[],
    deductions: [] as InspectionDeductionDetail[],
    redlines: [] as InspectionDeductionDetail[],
    itemResults: [] as InspectionItemResult[],
  })

  const deductionForm = reactive<InspectionDeductionForm>({
    dimension: '',
    clauseKey: '',
    manualItem: '',
    deduct: null,
    issue: '',
  })

  const brandOptions = computed<InspectionBrandOption[]>(() => {
    const names = new Set<string>()
    inspectionCanonicalBrandNames.forEach((name) => names.add(name))
    options.backendBrands.value.forEach((brand) => names.add(normalizeInspectionBrandName(brand.name)))
    options.stores.value.forEach((store) => names.add(normalizeInspectionBrandName(store.brandName)))
    options.records.value.forEach((record) => {
      const store = options.stores.value.find((item) => item.id === record.storeId)
      names.add(normalizeInspectionBrandName(record.brandName || record.brand || store?.brandName))
    })
    return Array.from(names)
      .filter((name) => inspectionCanonicalBrandNames.includes(name))
      .sort((a, b) => inspectionCanonicalBrandNames.indexOf(a) - inspectionCanonicalBrandNames.indexOf(b))
      .map((name) => ({ name, ...(inspectionBrandPalette[name] || { color: '#64748b', soft: '#f1f5f9' }) }))
  })

  const createStoreOptions = computed(() => options.stores.value
    .filter((store) => normalizeInspectionBrandName(store.brandName) === draft.brandName)
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN')))

  const globalStandard = computed(() => options.inspectionStandard.value)
  const globalStandardStats = computed(() => inspectionStandardStats(globalStandard.value))
  const draftDimensions = computed(() => getInspectionDimensions(globalStandard.value))
  const clausesForDimension = computed(() => (
    globalStandard.value.groups.find((group) => group.dim === deductionForm.dimension)?.items || []
  ))
  const selectedClause = computed<InspectionStandardClause | undefined>(() => (
    clausesForDimension.value[Number(deductionForm.clauseKey)]
  ))
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
      const key = options.detectionKey(photo.detection)
      const clauseId = options.detectionClauseId(photo.detection)
      const confirmed = Math.abs(safeInspectionNumber(photo.detection.confirmedDeduction))
      if (!key || seenDetectionKeys.has(key) || !clauseId || confirmed <= 0) return
      seenDetectionKeys.add(key)
      result.set(clauseId, Math.max(result.get(clauseId) || 0, confirmed))
    })
    return result
  })

  const categoryScores = computed(() => globalStandard.value.groups.map((group) => {
    const itemScore = draft.itemResults
      .filter((item) => item.categoryCode === group.categoryCode)
      .reduce((sum, item) => {
        const actualScore = safeInspectionNumber(item.actualScore)
        const existingDeduction = Math.max(0, safeInspectionNumber(item.standardScore) - actualScore)
        const modelDeduction = acceptedModelDeductionByClause.value.get(item.standardItemId) || 0
        const previewAdjustment = Math.max(0, modelDeduction - existingDeduction)
        return sum + actualScore - previewAdjustment
      }, 0)
    const additionalDeduction = draft.deductions
      .filter((item) => (item.categoryCode || inspectionCategoryCodeForDimension(item.dim)) === group.categoryCode && !item.standardId)
      .reduce((sum, item) => sum + safeInspectionNumber(item.deduct), 0)
    return {
      code: group.categoryCode,
      name: group.dim,
      fullScore: group.fullScore,
      score: Math.max(0, roundInspectionScore(itemScore - additionalDeduction)),
    }
  }))

  const manualCurrentScore = computed(() => roundInspectionScore(globalStandard.value.groups.reduce((sum, group) => {
    const itemScore = draft.itemResults
      .filter((item) => item.categoryCode === group.categoryCode)
      .reduce((categorySum, item) => categorySum + safeInspectionNumber(item.actualScore), 0)
    const additionalDeduction = draft.deductions
      .filter((item) => (item.categoryCode || inspectionCategoryCodeForDimension(item.dim)) === group.categoryCode && !item.standardId)
      .reduce((categorySum, item) => categorySum + safeInspectionNumber(item.deduct), 0)
    return sum + Math.max(0, itemScore - additionalDeduction)
  }, 0)))

  const currentScore = computed(() => roundInspectionScore(categoryScores.value.reduce((sum, category) => sum + category.score, 0)))
  const deductionTotal = computed(() => Math.max(0, roundInspectionScore(INSPECTION_MAX_SCORE - currentScore.value)))
  const redLineHit = computed(() => draft.itemResults.some((item) => (
    item.riskLevel === 'RED' && Boolean(item.issueFound)
  )) || draft.redlines.length > 0)
  const yellowRiskCount = computed(() => draft.itemResults.filter((item) => (
    item.riskLevel === 'YELLOW' && safeInspectionNumber(item.actualScore) < safeInspectionNumber(item.standardScore)
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
    return unlinkedCount > 0 ? `还有 ${unlinkedCount} 张照片未关联具体检查条款` : ''
  })
  const draftRows = computed(() => [
    ...draft.deductions.map((item, index) => ({ kind: 'deduction' as const, index, item })),
    ...draft.redlines.map((item, index) => ({ kind: 'redline' as const, index, item })),
  ])

  function ensureDraftStore() {
    if (!brandOptions.value.find((brand) => brand.name === draft.brandName)) {
      draft.brandName = brandOptions.value[0]?.name || '茹菓'
    }
    const optionsForBrand = createStoreOptions.value
    if (!optionsForBrand.find((store) => store.id === draft.storeId)) {
      draft.storeId = optionsForBrand[0]?.id || ''
    }
  }

  function fillDeductionFromClause() {
    if (selectedClause.value) deductionForm.deduct = selectedClause.value.score || 1
  }

  function ensureDeductionForm() {
    const dimensions = draftDimensions.value
    if (!dimensions.includes(deductionForm.dimension)) deductionForm.dimension = dimensions[0] || ''
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

  function handleDraftBrandChange() {
    draft.storeId = ''
    ensureDraftStore()
    ensureDeductionForm()
  }

  function resetDraft() {
    const nextBrand = options.filterBrand.value || draft.brandName || brandOptions.value[0]?.name || '茹菓'
    draft.brandName = nextBrand
    draft.storeId = ''
    draft.inspectionDate = todayInspectionDate()
    draft.inspector = ''
    draft.fullScore = INSPECTION_MAX_SCORE
    draft.note = ''
    options.releaseDraftPhotos()
    draft.photos = []
    draft.deductions = []
    draft.redlines = []
    draft.itemResults = []
    options.resetDraftReviewState()
    deductionForm.manualItem = ''
    deductionForm.issue = ''
    deductionForm.deduct = null
    ensureDraftStore()
    ensureDeductionForm()
    ensureDraftItemResults(true)
  }

  function draftItemsForCategory(categoryCode: InspectionCategoryCode) {
    return draft.itemResults.filter((item) => item.categoryCode === categoryCode)
  }

  function manualCategoryScore(code: InspectionCategoryCode) {
    const group = globalStandard.value.groups.find((item) => item.categoryCode === code)
    if (!group) return 0
    const itemScore = draft.itemResults
      .filter((item) => item.categoryCode === code)
      .reduce((sum, item) => sum + safeInspectionNumber(item.actualScore), 0)
    const additionalDeduction = draft.deductions
      .filter((item) => (item.categoryCode || inspectionCategoryCodeForDimension(item.dim)) === code && !item.standardId)
      .reduce((sum, item) => sum + safeInspectionNumber(item.deduct), 0)
    return Math.max(0, roundInspectionScore(itemScore - additionalDeduction))
  }

  return {
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
    acceptedModelDeductionByClause,
    categoryScores,
    manualCurrentScore,
    currentScore,
    deductionTotal,
    redLineHit,
    yellowRiskCount,
    detectingPhotoCount,
    failedPhotoCount,
    pendingReviewCount,
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
  }
}
