<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ImagePlus, Plus, RefreshCw, Trash2, Upload } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  createInspectionRecord,
  getInspectionRecords,
  getInspectionStandard,
  uploadInspectionAttachment,
  type InspectionAttachment,
  type InspectionRecord,
} from '../api/inspection'
import { getBrands, getStores, type BrandInfo, type StoreInfo } from '../api/operations'
import {
  emptyInspectionStandard,
  getInspectionDimensions,
  inspectionStandardStats,
  toInspectionStandardSet,
  type InspectionStandardClause,
  type InspectionStandardRedline,
  type InspectionStandardSet,
} from '../data/inspectionStandards'

type InspectionLegacyTab = 'records' | 'create' | 'standards'

interface BrandOption {
  name: string
  color: string
  soft: string
}

interface DraftPhoto {
  fileName: string
  url?: string
  storagePath?: string
  contentType?: string
  fileSize?: number
}

interface DeductionDetail {
  id?: string
  standardId?: string
  standardTitle?: string
  standardDescription?: string
  suggestedScore?: number
  dim?: string
  code?: string
  item?: string
  issue?: string
  deduct?: number
  requirement?: string
  redline?: boolean
  method?: string
}

interface DeductionForm {
  dimension: string
  clauseKey: string
  manualItem: string
  deduct: number | null
  issue: string
}

const route = useRoute()
const router = useRouter()

const passLine = 80
const canonicalBrandNames = ['茹菓', '霸王茶姬', '瑞幸咖啡']
const brandPalette: Record<string, { color: string; soft: string }> = {
  茹菓: { color: '#ef7d3c', soft: '#fff1e8' },
  霸王茶姬: { color: '#9c2f3e', soft: '#fff0f2' },
  瑞幸咖啡: { color: '#2458c7', soft: '#edf4ff' },
}

const activeTab = ref<InspectionLegacyTab>('records')
const records = ref<InspectionRecord[]>([])
const stores = ref<StoreInfo[]>([])
const backendBrands = ref<BrandInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const errorMessage = ref('')
const actionMessage = ref('')
const selectedRecordId = ref('')
const filterBrand = ref('')
const filterMonth = ref('')
const standardDimension = ref('')
const inspectionStandard = ref<InspectionStandardSet>(emptyInspectionStandard)

const tabs: Array<{ id: InspectionLegacyTab; label: string }> = [
  { id: 'records', label: '巡检记录' },
  { id: 'create', label: '发起巡检' },
  { id: 'standards', label: '稽核标准' },
]

const draft = reactive({
  brandName: '茹菓',
  storeId: '',
  inspectionDate: todayDate(),
  inspector: '',
  fullScore: 100,
  note: '',
  photos: [] as DraftPhoto[],
  deductions: [] as DeductionDetail[],
  redlines: [] as DeductionDetail[],
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
  const scoreSource = source.filter((record) => Number.isFinite(Number(record.score)))
  const averageScore = scoreSource.length
    ? Math.round(scoreSource.reduce((sum, record) => sum + Number(record.score || 0), 0) / scoreSource.length)
    : 0
  const redlineCount = source.filter((record) => redlineDetails(record).length > 0).length
  return { total, monthCount, averageScore, redlineCount }
})

const createStoreOptions = computed(() => {
  return stores.value
    .filter((store) => normalizeBrandName(store.brandName) === draft.brandName)
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
})

const selectedRecord = computed(() => {
  if (!selectedRecordId.value) return null
  return records.value.find((record) => String(record.id) === selectedRecordId.value) || null
})

const globalStandard = computed(() => inspectionStandard.value)
const globalStandardStats = computed(() => inspectionStandardStats(globalStandard.value))
const draftDimensions = computed(() => getInspectionDimensions(globalStandard.value))
const clausesForDimension = computed(() => {
  return globalStandard.value.groups.find((group) => group.dim === deductionForm.dimension)?.items || []
})
const selectedClause = computed(() => clausesForDimension.value[Number(deductionForm.clauseKey)] || null)
const hasGlobalStandard = computed(() => globalStandard.value.groups.length > 0 || globalStandard.value.redlines.length > 0)

const deductionTotal = computed(() => {
  return roundScore(draft.deductions.reduce((sum, item) => sum + safeNumber(item.deduct), 0))
})

const currentScore = computed(() => Math.max(0, roundScore(safeNumber(draft.fullScore) - deductionTotal.value)))
const redLineHit = computed(() => draft.redlines.length > 0 || draft.deductions.some((item) => item.redline))
const inspectionPassed = computed(() => !redLineHit.value && currentScore.value >= passLine)
const inspectionResultText = computed(() => {
  if (redLineHit.value) return '不合格 / 红线项'
  return inspectionPassed.value ? '合格' : '不合格'
})
const scoreTone = computed(() => {
  if (redLineHit.value || currentScore.value < passLine) return 'bad'
  if (currentScore.value < 90) return 'warn'
  return 'good'
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

function tabFromRoute(): InspectionLegacyTab {
  const tab = Array.isArray(route.query.tab) ? route.query.tab[0] : route.query.tab
  const metaTab = route.meta.inspectionTab
  const raw = String(tab || metaTab || '').toLowerCase()
  if (['create', 'tasks', 'new'].includes(raw)) return 'create'
  if (['standards', 'standard', 'rules', 'std'].includes(raw)) return 'standards'
  if (route.path.includes('/tasks') || route.path.includes('/create')) return 'create'
  if (route.path.includes('/rules') || route.path.includes('/standards')) return 'standards'
  return 'records'
}

function applyRouteState() {
  activeTab.value = tabFromRoute()
  const brand = Array.isArray(route.query.brand) ? route.query.brand[0] : route.query.brand
  const month = Array.isArray(route.query.month) ? route.query.month[0] : route.query.month
  filterBrand.value = brand ? normalizeBrandName(String(brand)) : ''
  filterMonth.value = month ? String(month) : ''
  selectedRecordId.value = ''
}

function switchTab(tab: InspectionLegacyTab) {
  actionMessage.value = ''
  errorMessage.value = ''
  const query: Record<string, string> = { tab }
  if (tab === 'records') {
    if (filterBrand.value) query.brand = filterBrand.value
    if (filterMonth.value) query.month = filterMonth.value
  }
  void router.push({ path: '/inspection', query })
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
  const query: Record<string, string> = { tab: 'records' }
  if (filterBrand.value) query.brand = filterBrand.value
  if (filterMonth.value) query.month = filterMonth.value
  void router.replace({ path: '/inspection', query })
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
  draft.fullScore = globalStandard.value.fullScore || 100
  draft.note = ''
  draft.photos = []
  draft.deductions = []
  draft.redlines = []
  deductionForm.manualItem = ''
  deductionForm.issue = ''
  deductionForm.deduct = null
  ensureDraftStore()
  ensureDeductionForm()
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
    code: textValue(value.code),
    item: title || '现场问题',
    issue,
    deduct,
    requirement: textValue(value.requirement ?? value.rectificationRequirement ?? value.rectification_requirement),
    redline: redline || Boolean(value.redline ?? value.red_line),
    method: textValue(value.method),
  }
}

function textValue(value: unknown) {
  return String(value ?? '').trim()
}

function deductionDetails(record: InspectionRecord) {
  return parseJsonArray(record.deductionsJson).map((item) => normalizeDeductionDetail(item, false))
}

function redlineDetails(record: InspectionRecord) {
  return parseJsonArray(record.redlinesJson).map((item) => normalizeDeductionDetail(item, true))
}

function recordPhotos(record: InspectionRecord) {
  return parseJsonArray<DraftPhoto>(record.photosJson)
}

function deductionCount(record: InspectionRecord) {
  return deductionDetails(record).length + redlineDetails(record).length
}

function recordScore(record: InspectionRecord) {
  const score = Number(record.score ?? 0)
  return Number.isFinite(score) ? roundScore(score) : 0
}

function resultLabel(record: InspectionRecord) {
  if (redlineDetails(record).length) return '不合格 / 红线项'
  return record.passed ? '合格' : '不合格'
}

function detailText(item: DeductionDetail) {
  const title = [item.dim, item.code, item.item].filter(Boolean).join(' · ') || '扣分项'
  const issue = item.issue ? `：${item.issue}` : ''
  const deduct = safeNumber(item.deduct) > 0 ? `（扣 ${item.deduct} 分）` : item.redline ? '（红线项）' : ''
  return `${title}${issue}${deduct}`
}

function standardSnapshotText(item: DeductionDetail) {
  const title = item.standardTitle || item.item || '现场问题'
  const suggested = item.redline ? '红线项' : `建议扣 ${safeNumber(item.suggestedScore || item.deduct)} 分`
  const actual = item.redline ? '实际判定不合格' : `实际扣 ${safeNumber(item.deduct)} 分`
  return `${item.dim || '—'} · ${title} · ${suggested} · ${actual}`
}

async function handleAttachmentChange(event: Event) {
  const input = event.target as HTMLInputElement
  await uploadPhotos(Array.from(input.files || []))
  input.value = ''
}

async function handlePhotoDrop(event: DragEvent) {
  await uploadPhotos(Array.from(event.dataTransfer?.files || []))
}

async function uploadPhotos(files: File[]) {
  const imageFiles = files.filter((file) => file.type.startsWith('image/'))
  if (!imageFiles.length) return
  if (!draft.storeId) {
    errorMessage.value = '请先选择巡检门店后再上传照片。'
    return
  }
  uploading.value = true
  errorMessage.value = ''
  try {
    for (const file of imageFiles) {
      const uploaded = await uploadInspectionAttachment(file, draft.storeId, `inspection-${draft.storeId}-draft`)
      draft.photos.push(toDraftPhoto(uploaded, file))
    }
    actionMessage.value = `已上传 ${imageFiles.length} 张现场照片`
  } catch (error) {
    errorMessage.value = friendlyError(error, '照片上传失败。')
  } finally {
    uploading.value = false
  }
}

function toDraftPhoto(uploaded: InspectionAttachment, file: File): DraftPhoto {
  return {
    fileName: uploaded.fileName || file.name,
    url: uploaded.url,
    storagePath: uploaded.storagePath,
    contentType: uploaded.contentType || file.type,
    fileSize: uploaded.fileSize || file.size,
  }
}

function photoHref(photo: DraftPhoto) {
  return photo.url || photo.storagePath || '#'
}

function removePhoto(index: number) {
  draft.photos.splice(index, 1)
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

  draft.deductions.push({
    id: `${Date.now()}-${draft.deductions.length}`,
    standardId: clause?.id,
    standardTitle: clause?.item || itemTitle,
    standardDescription: clause?.method,
    suggestedScore: clause?.score,
    dim: deductionForm.dimension,
    code: clause?.code,
    item: itemTitle,
    method: clause?.method,
    issue: deductionForm.issue.trim(),
    deduct: score,
    redline: false,
  })
  deductionForm.issue = ''
  deductionForm.manualItem = ''
  fillDeductionFromClause()
}

function removeDraftRow(kind: 'deduction' | 'redline', index: number) {
  if (kind === 'deduction') draft.deductions.splice(index, 1)
  else draft.redlines.splice(index, 1)
}

function redlineSelected(redline: InspectionStandardRedline) {
  return draft.redlines.some((item) => item.item === redline.item)
}

function toggleRedline(redline: InspectionStandardRedline, checked: boolean) {
  if (checked) {
    if (!redlineSelected(redline)) {
      draft.redlines.push({
        id: `redline-${Date.now()}-${draft.redlines.length}`,
        standardId: redline.id,
        standardTitle: redline.item,
        standardDescription: redline.method,
        suggestedScore: 0,
        dim: '红线项',
        code: redline.code,
        item: redline.item,
        issue: redline.method || '命中红线项',
        deduct: 0,
        redline: true,
        method: redline.method,
      })
    }
    return
  }
  draft.redlines = draft.redlines.filter((item) => item.item !== redline.item)
}

async function submitRecord() {
  errorMessage.value = ''
  actionMessage.value = ''
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

  const note = [
    draft.note.trim() ? `整改要求：${draft.note.trim()}` : '',
    redLineHit.value ? `红线项：${draft.redlines.map((item) => item.item).join('；')}` : '',
  ].filter(Boolean).join('\n')

  saving.value = true
  try {
    await createInspectionRecord({
      storeId: draft.storeId,
      inspectionDate: draft.inspectionDate,
      inspector: draft.inspector.trim(),
      brand: draft.brandName,
      fullScore: safeNumber(draft.fullScore),
      score: currentScore.value,
      passed: inspectionPassed.value,
      deductionsJson: JSON.stringify(draft.deductions.map(toPayloadDeduction)),
      redlinesJson: JSON.stringify(draft.redlines.map(toPayloadDeduction)),
      photosJson: JSON.stringify(draft.photos),
      note: note || undefined,
    })
    actionMessage.value = '巡检已保存，记录可以在列表中查看。'
    resetDraft()
    await refresh()
    await router.push({ path: '/inspection', query: { tab: 'records' } })
  } catch (error) {
    errorMessage.value = friendlyError(error, '巡检没有保存成功，请检查后再试。')
  } finally {
    saving.value = false
  }
}

function toPayloadDeduction(item: DeductionDetail) {
  return {
      standard_id: item.standardId,
      standard_version: globalStandard.value.version || undefined,
    standard_title: item.standardTitle || item.item,
    standard_description: item.standardDescription || item.method,
    suggested_score: safeNumber(item.suggestedScore ?? item.deduct),
    deduction_score: safeNumber(item.deduct),
    actual_deduction_score: safeNumber(item.deduct),
    red_line: Boolean(item.redline),
    dim: item.dim,
    code: item.code,
    item: item.item,
    issue: item.issue,
    deduct: safeNumber(item.deduct),
    redline: Boolean(item.redline),
    method: item.method,
    requirement: item.requirement,
  }
}

function friendlyError(error: unknown, fallback: string) {
  const status = (error as { status?: number })?.status
  const message = error instanceof Error ? error.message : ''
  if (status === 401) return '登录已过期，请重新登录后再操作。'
  if (status === 403 || message.includes('No permission')) return '当前账号没有巡检权限。'
  if (status === 404) return fallback
  if (message.includes('Inspection record not found')) return '没有找到这条巡检记录。'
  if (message.includes('Network') || message.includes('timeout')) return '网络连接不稳定，请稍后重试。'
  return message && !/[A-Za-z]{4,}/.test(message) ? message : fallback
}

watch(
  () => route.fullPath,
  () => applyRouteState(),
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

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel legacy-page inspection-legacy-page">
    <div class="legacy-page-head page-head inspection-head">
      <div>
        <h2>督导巡店</h2>
      </div>
      <div class="legacy-head-actions inspection-head-actions">
        <div class="inspection-segment" role="tablist" aria-label="督导巡店页签">
          <button
            v-for="tab in tabs"
            :key="tab.id"
            type="button"
            class="inspection-segment-button"
            :class="{ on: activeTab === tab.id }"
            @click="switchTab(tab.id)"
          >
            {{ tab.label }}
          </button>
        </div>
        <button class="legacy-button ghost-button" type="button" :disabled="loading" @click="refresh">
          <RefreshCw :size="16" />
          刷新
        </button>
      </div>
    </div>

    <div v-if="errorMessage" class="error-box">{{ errorMessage }}</div>
    <div v-if="actionMessage" class="success-box">{{ actionMessage }}</div>

    <div v-if="activeTab === 'records'" class="inspection-records-view">
      <div class="legacy-kpis inspection-kpis">
        <div class="legacy-card legacy-kpi">
          <span class="lab">巡检总次数</span>
          <b class="val">{{ summary.total }}</b>
        </div>
        <div class="legacy-card legacy-kpi">
          <span class="lab">本月巡检</span>
          <b class="val">{{ summary.monthCount }}</b>
        </div>
        <div class="legacy-card legacy-kpi">
          <span class="lab">平均得分</span>
          <b class="val">{{ summary.averageScore }}<small>分</small></b>
        </div>
        <div class="legacy-card legacy-kpi">
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
        <select class="legacy-select inspection-month-select" aria-label="月份筛选" :value="filterMonth" @change="setMonthFilter(($event.target as HTMLSelectElement).value)">
          <option value="">全部月份</option>
          <option v-for="month in monthOptions" :key="month" :value="month">{{ month.slice(0, 4) }}年{{ month.slice(5) }}月</option>
        </select>
      </div>

      <section v-if="selectedRecord" class="legacy-card inspection-detail-card">
        <div class="inspection-detail-head">
          <div>
            <span class="legacy-section-t">巡检详情</span>
            <h3>{{ selectedRecord.storeName || selectedRecord.storeId }}</h3>
          </div>
          <button class="legacy-button" type="button" @click="selectedRecordId = ''">返回巡检记录</button>
        </div>
        <div class="inspection-detail-grid">
          <div><span>门店</span><b>{{ selectedRecord.storeName || selectedRecord.storeId }}</b></div>
          <div><span>品牌</span><b>{{ recordBrandName(selectedRecord) || '—' }}</b></div>
          <div><span>巡检日期</span><b>{{ selectedRecord.inspectionDate || '—' }}</b></div>
          <div><span>督导</span><b>{{ selectedRecord.inspector || '—' }}</b></div>
          <div><span>得分</span><b>{{ recordScore(selectedRecord) }} 分</b></div>
          <div><span>结果</span><b :class="{ danger: !selectedRecord.passed }">{{ resultLabel(selectedRecord) }}</b></div>
        </div>
        <div class="inspection-detail-section">
          <h4>扣分项明细</h4>
          <div v-if="!deductionDetails(selectedRecord).length && !redlineDetails(selectedRecord).length" class="legacy-empty compact">暂无扣分项。</div>
          <ul v-else class="inspection-detail-list">
            <li v-for="(item, index) in [...deductionDetails(selectedRecord), ...redlineDetails(selectedRecord)]" :key="index">
              {{ detailText(item) }}
            </li>
          </ul>
        </div>
        <div class="inspection-detail-section">
          <h4>当时使用的标准快照</h4>
          <div v-if="!deductionDetails(selectedRecord).length && !redlineDetails(selectedRecord).length" class="legacy-empty compact">暂无标准快照。</div>
          <ul v-else class="inspection-detail-list">
            <li v-for="(item, index) in [...deductionDetails(selectedRecord), ...redlineDetails(selectedRecord)]" :key="`snapshot-${index}`">
              {{ standardSnapshotText(item) }}
            </li>
          </ul>
        </div>
        <div class="inspection-detail-section">
          <h4>现场照片证据</h4>
          <div v-if="!recordPhotos(selectedRecord).length" class="legacy-empty compact">暂无现场照片。</div>
          <div v-else class="inspection-photo-list">
            <a v-for="photo in recordPhotos(selectedRecord)" :key="photo.url || photo.storagePath || photo.fileName" :href="photoHref(photo)" target="_blank" rel="noreferrer">
              {{ photo.fileName || '现场照片' }}
            </a>
          </div>
        </div>
        <div class="inspection-detail-section">
          <h4>整改要求</h4>
          <p>{{ selectedRecord.note || deductionDetails(selectedRecord)[0]?.requirement || '暂无整改要求。' }}</p>
        </div>
      </section>

      <section v-else class="legacy-card legacy-table-card inspection-table-card">
        <div class="inspection-table-head">
          <span class="legacy-section-t">
            {{ filterBrand || filterMonth ? `筛选后 ${filteredRecords.length} 次` : `共 ${filteredRecords.length} 次巡检` }}
          </span>
        </div>
        <div v-if="loading && !records.length" class="legacy-empty">正在读取巡检记录...</div>
        <div v-else-if="!filteredRecords.length" class="legacy-empty">
          <b>{{ filterBrand || filterMonth ? '当前筛选下没有巡检记录' : '暂无巡检记录' }}</b>
        </div>
        <div v-else class="legacy-table-wrap">
          <table class="legacy-table inspection-table">
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
              <tr v-for="record in filteredRecords" :key="record.id" class="clickable-row" @click="selectedRecordId = String(record.id)">
                <td>{{ record.inspectionDate || '—' }}</td>
                <td><b>{{ record.storeName || record.storeId }}</b></td>
                <td>
                  <span class="legacy-tag" :style="{ background: brandMeta(recordBrandName(record)).soft, color: brandMeta(recordBrandName(record)).color }">
                    <span class="dotc" :style="{ background: brandMeta(recordBrandName(record)).color }" />
                    {{ recordBrandName(record) || '—' }}
                  </span>
                </td>
                <td>{{ record.inspector || '—' }}</td>
                <td class="r score-cell">{{ recordScore(record) }}</td>
                <td><span class="result-text" :class="{ danger: !record.passed }">{{ resultLabel(record) }}</span></td>
                <td class="r muted-cell">{{ deductionCount(record) }}项</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <div v-else-if="activeTab === 'create'" class="inspection-create-view">
      <section class="legacy-card inspection-form-card">
        <div class="inspection-card-title">
          <div>
            <span class="legacy-section-t">基础信息</span>
            <h3>本次巡检门店</h3>
          </div>
        </div>
        <div class="inspection-create-form">
          <label>
            <span>品牌</span>
            <select v-model="draft.brandName" class="legacy-select" @change="handleDraftBrandChange">
              <option v-for="brand in brandOptions" :key="brand.name" :value="brand.name">{{ brand.name }}</option>
            </select>
          </label>
          <label>
            <span>门店</span>
            <select v-model="draft.storeId" class="legacy-select">
              <option v-if="!createStoreOptions.length" value="">该品牌暂无门店</option>
              <option v-for="store in createStoreOptions" :key="store.id" :value="store.id">{{ store.name }}</option>
            </select>
          </label>
          <label>
            <span>巡检日期</span>
            <input v-model="draft.inspectionDate" class="legacy-input" type="date" />
          </label>
          <label>
            <span>督导人</span>
            <input v-model.trim="draft.inspector" class="legacy-input" placeholder="填写巡检人姓名" />
          </label>
          <label>
            <span>满分基准</span>
            <input v-model.number="draft.fullScore" class="legacy-input" type="number" min="1" />
          </label>
          <div class="inspection-standard-note" :class="{ muted: hasGlobalStandard }">
            <span v-if="hasGlobalStandard">全门店通用标准 · 红线 {{ globalStandardStats.redlineCount }} 条 · 评分条款 {{ globalStandardStats.clauseCount }} 条</span>
            <span v-else>暂无稽核标准</span>
          </div>
        </div>
      </section>

      <section class="legacy-card inspection-upload-box" @dragover.prevent @drop.prevent="handlePhotoDrop">
        <div class="inspection-card-title">
          <div>
            <span class="legacy-section-t">现场照片证据 / 拍照识别（辅助）</span>
            <h3>保存现场证据</h3>
          </div>
          <label class="legacy-button primary upload-button">
            <ImagePlus :size="16" />
            {{ uploading ? '上传中...' : '拍照/选图' }}
            <input type="file" accept="image/*" capture="environment" multiple :disabled="uploading" @change="handleAttachmentChange" />
          </label>
        </div>
        <div v-if="draft.photos.length" class="inspection-photo-grid">
          <div v-for="(photo, index) in draft.photos" :key="photo.url || photo.storagePath || photo.fileName" class="inspection-photo-item">
            <a :href="photoHref(photo)" target="_blank" rel="noreferrer">{{ photo.fileName || '现场照片' }}</a>
            <button type="button" aria-label="移除照片" @click="removePhoto(index)"><Trash2 :size="14" /></button>
          </div>
        </div>
      </section>

      <section class="legacy-card inspection-score-bar">
        <div>
          <span>当前得分</span>
          <b :class="scoreTone">{{ currentScore }}</b>
        </div>
        <div>
          <span>扣分合计</span>
          <b class="bad">-{{ deductionTotal }}</b>
        </div>
        <div>
          <span>结果</span>
          <b :class="{ bad: !inspectionPassed, good: inspectionPassed }">{{ inspectionResultText }}</b>
          <small>合格线 {{ passLine }} 分</small>
        </div>
        <button class="legacy-button primary save-button" type="button" :disabled="saving || uploading" @click="submitRecord">
          <Upload :size="16" />
          {{ saving ? '保存中...' : '保存巡检' }}
        </button>
      </section>

      <section class="legacy-card inspection-deduction-card">
        <div class="inspection-card-title">
          <div>
            <span class="legacy-section-t">扣分项清单（{{ draftRows.length }}）</span>
            <h3>本次发现的问题</h3>
          </div>
        </div>
        <div v-if="!draftRows.length" class="legacy-empty compact">
          暂无扣分项。
        </div>
        <div v-else class="legacy-table-wrap">
          <table class="legacy-table inspection-table deduction-table">
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

      <section class="legacy-card inspection-add-card">
        <div class="inspection-card-title">
          <div>
            <span class="legacy-section-t">添加扣分项</span>
            <h3>把现场问题记清楚</h3>
          </div>
        </div>
        <div class="inspection-add-form">
          <label>
            <span>维度</span>
            <select v-model="deductionForm.dimension" class="legacy-select">
              <option v-for="dimension in draftDimensions" :key="dimension" :value="dimension">{{ dimension }}</option>
            </select>
          </label>
          <label v-if="clausesForDimension.length">
            <span>检查条款</span>
            <select v-model="deductionForm.clauseKey" class="legacy-select">
              <option v-for="(clause, index) in clausesForDimension" :key="`${clause.code}-${index}`" :value="String(index)">
                {{ clause.code ? `${clause.code} ` : '' }}{{ clause.item }}（建议{{ clause.score }}分）
              </option>
            </select>
          </label>
          <label v-else>
            <span>检查条款</span>
            <input v-model.trim="deductionForm.manualItem" class="legacy-input" placeholder="例如：吧台地面有水渍" />
          </label>
          <label>
            <span>扣分</span>
            <input v-model.number="deductionForm.deduct" class="legacy-input" type="number" min="1" :max="draft.fullScore" placeholder="填写扣分" />
          </label>
          <label class="wide">
            <span>问题描述</span>
            <input v-model.trim="deductionForm.issue" class="legacy-input" placeholder="写给门店看的问题，例如：开封物料未贴时效标签" />
          </label>
          <button class="legacy-button primary add-button" type="button" @click="addDeduction">
            <Plus :size="16" />
            添加
          </button>
        </div>
      </section>

      <section v-if="globalStandard.redlines.length" class="legacy-card inspection-redline-card">
        <div class="inspection-card-title">
          <div>
            <span class="legacy-section-t">红线项（一票否决）</span>
            <h3>有命中就勾选</h3>
          </div>
        </div>
        <div class="inspection-redline-list">
          <label
            v-for="(redline, index) in globalStandard.redlines"
            :key="`${redline.item}-${index}`"
            class="redline-option"
            :class="{ on: redlineSelected(redline) }"
          >
            <input type="checkbox" :checked="redlineSelected(redline)" @change="toggleRedline(redline, ($event.target as HTMLInputElement).checked)" />
            <span>
              <b>{{ redline.item }}</b>
              <small v-if="redline.method">判定：{{ redline.method }}</small>
            </span>
          </label>
        </div>
      </section>

      <section class="legacy-card inspection-note-card">
        <label>
          <span>整改要求 / 备注</span>
          <textarea v-model.trim="draft.note" class="legacy-input" rows="3" placeholder="写清楚整改要求、责任人或复查时间" />
        </label>
        <div class="inspection-form-actions">
          <button class="legacy-button" type="button" @click="resetDraft">清空表单</button>
          <button class="legacy-button primary" type="button" :disabled="saving || uploading" @click="submitRecord">{{ saving ? '保存中...' : '保存巡检' }}</button>
        </div>
      </section>
    </div>

    <div v-else class="inspection-standards-view">
      <section class="legacy-card inspection-standard-summary">
        <div>
          <span>标准范围</span>
          <b>全门店通用</b>
        </div>
        <div>
          <span>红线项</span>
          <b>{{ selectedStandardStats.redlineCount }} 条</b>
        </div>
        <div>
          <span>评分条款</span>
          <b>{{ selectedStandardStats.clauseCount }} 条</b>
        </div>
        <div>
          <span>满分基准</span>
          <b>{{ selectedStandardStats.fullScore }} 分</b>
        </div>
      </section>

      <section v-if="!hasGlobalStandard" class="legacy-card">
        <div class="legacy-empty">
          <b>暂无稽核标准</b>
        </div>
      </section>

      <template v-else>
        <section class="legacy-card inspection-standards-redlines">
          <div class="inspection-card-title">
            <div>
              <span class="legacy-section-t">红线项（一票否决）</span>
              <h3>全门店通用红线标准</h3>
            </div>
          </div>
          <div class="standard-redline-list">
            <div v-for="(redline, index) in selectedStandard.redlines" :key="`${redline.item}-${index}`" class="standard-redline-row">
              <b>{{ redline.item }}</b>
              <small v-if="redline.method">判定：{{ redline.method }}</small>
            </div>
          </div>
        </section>

        <section class="legacy-card inspection-standard-groups">
          <div class="inspection-card-title">
            <div>
              <span class="legacy-section-t">普通评分条款</span>
              <h3>按维度查看</h3>
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
              <div class="legacy-table-wrap">
                <table class="legacy-table inspection-table standards-table">
                  <thead>
                    <tr>
                      <th>条款编号</th>
                      <th>条款标题</th>
                      <th>判定说明</th>
                      <th class="r">建议扣分</th>
                      <th>是否红线项</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(clause, index) in group.items" :key="`${group.dim}-${clause.code}-${index}`">
                      <td class="muted-cell">{{ clause.code || '—' }}</td>
                      <td><b>{{ clause.item }}</b></td>
                      <td>{{ clause.method || '—' }}</td>
                      <td class="r">{{ clause.score }}分</td>
                      <td>否</td>
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
</template>

<style scoped>
.inspection-legacy-page {
  gap: 16px;
}

.inspection-head {
  align-items: flex-start;
}

.inspection-head-actions {
  justify-content: flex-end;
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

.inspection-kpis :deep(.legacy-kpi:nth-child(4) .val.red) {
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
.inspection-deduction-card,
.inspection-add-card,
.inspection-redline-card,
.inspection-note-card {
  overflow: hidden;
}

.legacy-table-wrap {
  width: 100%;
  overflow-x: auto;
}

.inspection-table {
  min-width: 760px;
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

.legacy-empty {
  display: grid;
  gap: 6px;
  text-align: center;
}

.legacy-empty.compact {
  padding: 12px 0;
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

.inspection-card-title h3,
.inspection-detail-head h3,
.standard-group-head h4 {
  margin: 0;
  font-size: 18px;
  font-weight: 900;
}

.inspection-detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
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

.inspection-score-bar,
.inspection-standard-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
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

.legacy-button.primary {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
}

.inspection-standards-redlines {
  border-left: 4px solid var(--bad);
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
  .inspection-create-form,
  .inspection-add-form,
  .inspection-detail-grid,
  .inspection-score-bar,
  .inspection-standard-summary {
    grid-template-columns: 1fr;
  }

  .inspection-filter-row {
    align-items: stretch;
  }
}

@media (max-width: 720px) {
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
  .standard-group-head {
    display: grid;
  }
}
</style>
