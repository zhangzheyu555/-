<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import {
  confirmMobileInspectionSuggestion,
  detectMobileInspectionPhoto,
  getMobileInspectionRecord,
  getMobileInspectionRecords,
  getMobileInspectionStandard,
  getMobileStores,
  getSupervisorInspectionTodos,
  saveMobileInspectionRecord,
  uploadMobileInspectionAttachment,
} from '../../api/business'
import SafeActionBar from '../../components/SafeActionBar.vue'
import { chooseMedia } from '../../platform'
import { canUseMobileCapability, useSessionStore } from '../../stores'
import {
  MOBILE_PERMISSIONS,
  type InspectionDetection,
  type InspectionItemResult,
  type InspectionRecord,
  type InspectionStandard,
  type InspectionStandardItem,
  type RoleTodoItem,
  type StorageAttachment,
  type StoreInfo,
} from '../../types/business'

interface DraftItem extends InspectionItemResult {
  title: string
  code?: string
  suggestedScore: number
  redLine: boolean
}

interface DraftPhoto {
  id: string
  path: string
  fileName: string
  attachment: StorageAttachment
  detection?: InspectionDetection
  detectionError?: string
  decision: 'PENDING' | 'AI_ACCEPTED' | 'MANUAL_LINKED'
  linkedClauseId?: number
  appliedDeduction: number
  reason: string
  selectedClauseId?: number
  manualDeduction: string
}

const session = useSessionStore()
const stores = ref<StoreInfo[]>([])
const standard = ref<InspectionStandard | null>(null)
const draftItems = ref<DraftItem[]>([])
const photos = ref<DraftPhoto[]>([])
const todos = ref<RoleTodoItem[]>([])
const records = ref<InspectionRecord[]>([])
const selectedStoreId = ref('')
const inspectionDate = ref(localDate())
const inspector = ref('')
const note = ref('')
const loading = ref(false)
const uploading = ref(false)
const saving = ref(false)
const busyPhotoId = ref('')
const expandedRecordId = ref('')
const errorMessage = ref('')
const actionMessage = ref('')
const draftRecordId = ref('')
const recordKeyword = ref('')
const filterStoreId = ref('')
const filterDateFrom = ref('')
const filterDateTo = ref('')
const filterResultIndex = ref(0)
const resultOptions = [{ label: '全部结果', value: '' }, { label: '巡检通过', value: 'true' }, { label: '需要整改', value: 'false' }]

const hasCapability = computed(() => canUseMobileCapability(session.user, 'inspection'))
const canRead = computed(() => hasCapability.value && session.hasPermission(MOBILE_PERMISSIONS.inspectionRead))
const canManage = computed(() => hasCapability.value && session.hasPermission(MOBILE_PERMISSIONS.inspectionManage))
const canUpload = computed(() => canManage.value && session.hasPermission(MOBILE_PERMISSIONS.attachmentWrite))
const canReadTodos = computed(() => session.hasPermission(MOBILE_PERMISSIONS.todoRead))
const canReadStores = computed(() => session.hasPermission(MOBILE_PERMISSIONS.storeRead))
const enabledClauses = computed(() => (standard.value?.items || []).filter((item) => item.enabled))
const storeNames = computed(() => stores.value.map((store) => store.name))
const selectedStoreName = computed(() => stores.value.find((store) => store.id === selectedStoreId.value)?.name || selectedStoreId.value)
const draftScore = computed(() => draftItems.value.reduce((sum, item) => sum + item.actualScore, 0))
const filterStores = computed(() => [{ id: '', name: '全部授权门店' }, ...stores.value])
const filterStoreIndex = computed(() => Math.max(0, filterStores.value.findIndex((store) => store.id === filterStoreId.value)))
const filteredRecords = computed(() => {
  const query = recordKeyword.value.trim().toLowerCase()
  if (!query) return records.value
  return records.value.filter((record) => [record.id, record.storeName, record.storeId, record.inspector, record.note, ...recordIssues(record).map((item) => `${item.title || ''} ${item.deductionReason || ''}`)].some((value) => String(value || '').toLowerCase().includes(query)))
})
const saveBlocked = computed(() => {
  if (!canManage.value) return '当前账号没有保存巡检的权限。'
  if (!canUpload.value) return '当前账号没有上传巡检附件的权限。'
  if (!selectedStoreId.value) return '请选择巡检门店。'
  if (!standard.value || !enabledClauses.value.length) return '当前没有可用的巡检标准。'
  if (!photos.value.length) return '请至少拍摄或选择一张现场照片。'
  if (photos.value.some((photo) => !photo.linkedClauseId)) return '每张照片都必须人工确认并关联正式巡检条款。'
  return ''
})

onShow(() => {
  if (!hasCapability.value) return denyAndReturn()
  void refresh()
})
onPullDownRefresh(async () => {
  await refresh()
  uni.stopPullDownRefresh()
})

async function refresh() {
  if ((!canRead.value && !canManage.value) || loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [standardResult, recordResult, todoResult, storeResult] = await Promise.all([
      getMobileInspectionStandard(),
      canRead.value ? getMobileInspectionRecords({ dateFrom: filterDateFrom.value || undefined, dateTo: filterDateTo.value || undefined, storeId: filterStoreId.value || undefined, passed: resultOptions[filterResultIndex.value]?.value === '' ? undefined : resultOptions[filterResultIndex.value]?.value === 'true' }) : Promise.resolve([]),
      canReadTodos.value ? getSupervisorInspectionTodos() : Promise.resolve(null),
      canReadStores.value ? getMobileStores() : Promise.resolve([]),
    ])
    standard.value = standardResult
    records.value = recordResult
    todos.value = todoResult?.items || []
    stores.value = scopedStores(storeResult)
    if (!selectedStoreId.value || !stores.value.some((store) => store.id === selectedStoreId.value)) {
      selectedStoreId.value = session.user?.boundStoreId || stores.value[0]?.id || ''
    }
    inspector.value ||= session.user?.displayName || ''
    if (!draftItems.value.length) initializeDraftItems()
  } catch (error) {
    errorMessage.value = friendlyError(error, '巡检工作台暂时无法加载，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function scopedStores(apiStores: StoreInfo[]) {
  if (apiStores.length) return apiStores.filter((store) => !store.status || store.status === 'ACTIVE')
  const scope = session.dataScope('INSPECTION')
  const scopedIds = scope?.storeIds || []
  const fallback = scopedIds.map((id) => ({ id, code: id, name: id, brandId: 0 }))
  if (session.user?.boundStoreId && !fallback.some((store) => store.id === session.user?.boundStoreId)) {
    fallback.unshift({
      id: session.user.boundStoreId,
      code: session.user.boundStoreId,
      name: session.user.boundStoreName || session.user.boundStoreId,
      brandId: Number(session.user.brandId || 0),
    })
  }
  return fallback
}

function initializeDraftItems() {
  draftItems.value = enabledClauses.value.map((item) => ({
    standardItemId: item.id,
    title: item.title,
    code: item.code,
    suggestedScore: number(item.suggestedScore),
    actualScore: number(item.suggestedScore),
    issueFound: false,
    photoAttachmentIds: [],
    beforePhotoAttachmentIds: [],
    afterPhotoAttachmentIds: [],
    rectificationStatus: '无需整改',
    redLine: Boolean(item.redLine),
  }))
}

function selectStore(event: { detail?: { value?: number | string } }) {
  const index = Number(event.detail?.value || 0)
  selectedStoreId.value = stores.value[index]?.id || ''
}

function selectFilterStore(event:{detail?:{value?:number|string}}){filterStoreId.value=filterStores.value[Number(event.detail?.value||0)]?.id||''}
function selectFilterResult(event:{detail?:{value?:number|string}}){filterResultIndex.value=Number(event.detail?.value||0)}
async function applyRecordFilters(){if(filterDateFrom.value&&filterDateTo.value&&filterDateFrom.value>filterDateTo.value){errorMessage.value='开始日期不能晚于结束日期。';return}await refresh()}
async function resetRecordFilters(){recordKeyword.value='';filterStoreId.value='';filterDateFrom.value='';filterDateTo.value='';filterResultIndex.value=0;await refresh()}

async function addPhotos() {
  if (!canUpload.value || uploading.value) return
  if (!selectedStoreId.value) {
    errorMessage.value = '请先选择巡检门店。'
    return
  }
  uploading.value = true
  errorMessage.value = ''
  actionMessage.value = ''
  try {
    const assets = await chooseMedia({ count: Math.max(1, 3 - photos.value.length), source: 'both', kinds: ['image'] })
    for (const asset of assets) {
      const businessId = `inspection-${selectedStoreId.value}-draft`
      const attachment = await uploadMobileInspectionAttachment(asset.path, selectedStoreId.value, businessId)
      const photo: DraftPhoto = {
        id: `${attachment.id}-${Date.now()}`,
        path: asset.path,
        fileName: attachment.fileName || asset.name || '现场照片',
        attachment,
        decision: 'PENDING',
        appliedDeduction: 0,
        reason: '',
        manualDeduction: '0',
      }
      photos.value.push(photo)
      try {
        photo.detection = await detectMobileInspectionPhoto(asset.path)
      } catch {
        photo.detectionError = 'AI 识别暂不可用，请人工选择条款关联照片。'
      }
    }
  } catch (error) {
    errorMessage.value = friendlyError(error, '照片选择或上传失败，请检查网络后重试。')
  } finally {
    uploading.value = false
  }
}

async function acceptAiSuggestion(photo: DraftPhoto) {
  if (!photo.detection || busyPhotoId.value) return
  const key = detectionKey(photo.detection)
  if (!key) {
    photo.detectionError = '识别结果缺少确认编号，请改为人工关联条款。'
    return
  }
  busyPhotoId.value = photo.id
  errorMessage.value = ''
  try {
    const confirmed = await confirmMobileInspectionSuggestion(key, detectionEvidence(photo))
    const clauseId = Number(confirmed.clauseId || 0)
    if (!clauseId || !draftItems.value.some((item) => item.standardItemId === clauseId)) {
      throw new Error('AI 建议未匹配当前正式条款')
    }
    const deduction = Math.max(0, number(confirmed.confirmedDeduction || confirmed.suggestedDeduction))
    photo.detection = { ...photo.detection, ...confirmed }
    linkPhoto(photo, clauseId, deduction, confirmed.clauseTitle || confirmed.deduction_content || 'AI 建议经人工确认')
    photo.decision = 'AI_ACCEPTED'
    actionMessage.value = 'AI 建议已人工确认；正式保存时后端会按当前条款再次重算。'
  } catch {
    photo.detectionError = 'AI 建议无法直接确认，请人工选择条款并关联。'
  } finally {
    busyPhotoId.value = ''
  }
}

function selectClause(photo: DraftPhoto, event: { detail?: { value?: number | string } }) {
  const index = Number(event.detail?.value || 0)
  photo.selectedClauseId = enabledClauses.value[index]?.id
}

function setManualDeduction(photo: DraftPhoto, event: unknown) {
  photo.manualDeduction = String((event as { detail?: { value?: string } })?.detail?.value || '0')
}

function linkManually(photo: DraftPhoto) {
  const clauseId = photo.selectedClauseId || enabledClauses.value[0]?.id
  const clause = enabledClauses.value.find((item) => item.id === clauseId)
  if (!clause) {
    errorMessage.value = '请选择照片对应的正式巡检条款。'
    return
  }
  const deduction = Math.max(0, number(photo.manualDeduction))
  if (deduction > number(clause.suggestedScore)) {
    errorMessage.value = `扣分不能超过该条款的 ${number(clause.suggestedScore)} 分。`
    return
  }
  linkPhoto(photo, clause.id, deduction, photo.reason.trim() || (deduction > 0 ? '督导人工确认现场问题' : '现场留证，未发现扣分问题'))
  photo.decision = 'MANUAL_LINKED'
  actionMessage.value = '照片已人工关联正式条款；最终得分由后端重算。'
}

function linkPhoto(photo: DraftPhoto, clauseId: number, deduction: number, reason: string) {
  const previousClauseId = photo.linkedClauseId
  photo.linkedClauseId = clauseId
  photo.appliedDeduction = deduction
  photo.reason = reason
  if (previousClauseId && previousClauseId !== clauseId) rebuildClause(previousClauseId)
  rebuildClause(clauseId)
}

function rebuildClause(clauseId: number) {
  const item = draftItems.value.find((row) => row.standardItemId === clauseId)
  if (!item) return
  const linked = photos.value.filter((photo) => photo.linkedClauseId === clauseId)
  const deduction = linked.reduce((sum, photo) => sum + photo.appliedDeduction, 0)
  item.actualScore = Math.max(0, number(item.suggestedScore) - deduction)
  item.issueFound = deduction > 0
  item.deductionReason = linked.filter((photo) => photo.appliedDeduction > 0).map((photo) => photo.reason).join('；') || undefined
  item.photoAttachmentIds = linked.map((photo) => photo.attachment.id)
  item.beforePhotoAttachmentIds = [...item.photoAttachmentIds]
  item.rectificationStatus = item.issueFound ? '待整改' : '无需整改'
}

async function saveInspection() {
  if (saving.value) return
  if (saveBlocked.value) {
    errorMessage.value = saveBlocked.value
    return
  }
  saving.value = true
  errorMessage.value = ''
  actionMessage.value = ''
  if (!draftRecordId.value) draftRecordId.value = createInspectionId()
  const recordId = draftRecordId.value
  const payload = {
    storeId: selectedStoreId.value,
    inspectionDate: inspectionDate.value,
    inspector: inspector.value.trim() || session.user?.displayName || undefined,
    fullScore: number(standard.value?.fullScore),
    standardVersionId: standard.value?.id,
    standardVersion: standard.value?.version,
    itemResults: draftItems.value.map((item) => ({
      standardItemId: item.standardItemId,
      actualScore: item.actualScore,
      issueFound: item.issueFound,
      deductionReason: item.deductionReason,
      photoAttachmentIds: [...item.photoAttachmentIds],
      beforePhotoAttachmentIds: [...item.beforePhotoAttachmentIds],
      afterPhotoAttachmentIds: [],
      rectificationStatus: item.rectificationStatus,
    })),
    photosJson: JSON.stringify(photos.value.map(photoPayload)),
    note: note.value.trim() || undefined,
  }
  try {
    const saved = await saveMobileInspectionRecord(recordId, payload)
    finishSavedInspection(saved, false)
  } catch (error) {
    // PUT 使用本次业务意图的稳定记录 ID。若保存成功但响应在弱网中丢失，先读取同一 ID，
    // 确认服务端结果后再清空表单，绝不自动创建第二条巡检。
    try {
      const saved = await getMobileInspectionRecord(recordId)
      finishSavedInspection(saved, true)
    } catch {
      errorMessage.value = friendlyError(error, '巡检未保存成功，请检查照片条款关联并重试；重试会沿用同一巡检编号。')
    }
  } finally {
    saving.value = false
  }
}

function finishSavedInspection(saved: InspectionRecord, recovered: boolean) {
  actionMessage.value = `${recovered ? '已确认弱网前的保存结果，' : ''}巡检已保存，后端最终得分 ${number(saved.displayScore ?? saved.score)} 分。`
  draftRecordId.value = ''
  photos.value = []
  note.value = ''
  initializeDraftItems()
  void refresh()
}

function createInspectionId() {
  const random = Math.random().toString(36).slice(2, 10)
  return `MOBINSP-${Date.now()}-${random}`
}

function photoPayload(photo: DraftPhoto) {
  return {
    attachmentId: photo.attachment.id,
    fileName: photo.fileName,
    contentType: photo.attachment.contentType,
    fileSize: photo.attachment.fileSize || photo.attachment.sizeBytes,
    detectionStatus: photo.detection ? 'success' : 'failed',
    reviewStatus: photo.decision === 'AI_ACCEPTED' ? 'accepted' : 'dismissed',
    detection: sanitizeDetection(photo.detection),
  }
}

function sanitizeDetection(detection?: InspectionDetection) {
  if (!detection) return undefined
  const safe = { ...detection }
  ;['clauseId', 'clauseCode', 'clauseTitle', 'suggestedDeduction', 'confirmedDeduction', 'decisionStatus', 'revision', 'annotated_image'].forEach((key) => delete safe[key])
  return safe
}

function detectionEvidence(photo: DraftPhoto) {
  const detection = photo.detection || {}
  return {
    image_id: detection.image_id || detection.imageId,
    filename: photo.fileName,
    attachmentId: photo.attachment.id,
    passed: detection.passed,
    detection_count: detection.detection_count ?? detection.detectionCount,
    detections: detection.detections || [],
    detection_summary: detection.detection_summary,
    auto_status: detection.auto_status,
    deduction_project: detection.deduction_project,
    deduction_content: detection.deduction_content,
  }
}

function detectionKey(detection: InspectionDetection) {
  return String(detection.detectionKey || detection.detection_key || '').trim()
}

function detectionSummary(photo: DraftPhoto) {
  if (photo.detectionError) return photo.detectionError
  if (!photo.detection) return '正在等待识别结果'
  return String(photo.detection.detection_summary || photo.detection.deduction_content || (photo.detection.passed ? '未识别到问题' : '发现疑似问题'))
}

function suggestedClause(photo: DraftPhoto) {
  return String(photo.detection?.clauseTitle || photo.detection?.clauseCode || '待人工选择条款')
}

function toggleRecord(recordId: string) {
  expandedRecordId.value = expandedRecordId.value === recordId ? '' : recordId
}

function openRecordDetail(recordId:string){uni.navigateTo({url:`/pkg-inspection/detail/index?id=${encodeURIComponent(recordId)}`})}

function recordIssues(record: InspectionRecord) {
  return (record.itemResults || []).filter((item) => item.issueFound || item.rectificationStatus === '待整改')
}

function number(value: unknown) {
  const parsed = Number(value || 0)
  return Number.isFinite(parsed) ? Math.round(parsed * 100) / 100 : 0
}

function localDate() {
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60_000
  return new Date(now.getTime() - offset).toISOString().slice(0, 10)
}

function friendlyError(error: unknown, fallback: string) {
  const status = Number((error as { status?: number })?.status || 0)
  const code = String((error as { code?: string })?.code || '')
  if (status === 403) return '当前账号无权访问该门店或执行巡检操作。'
  if (status === 401) return '登录已过期，请重新登录。'
  if (code === 'INSPECTION_EVIDENCE_UNLINKED') return '每张现场照片都必须关联至少一个正式巡检条款。'
  if (code === 'INSPECTION_STANDARD_STALE') return '巡检标准已更新，请刷新后重新确认。'
  return fallback
}

function denyAndReturn() {
  uni.showToast({ title: '移动巡检仅向督导开放', icon: 'none' })
  setTimeout(() => uni.reLaunch({ url: '/pages/home/index' }), 500)
}
</script>

<template>
  <view class="page">
    <view class="page-head">
      <view><text class="eyebrow">督导工作台</text><text class="title">移动巡检</text></view>
      <button class="ghost-button" :loading="loading" :disabled="loading" @click="refresh">刷新</button>
    </view>

    <view v-if="!canRead && !canManage" class="state-card">移动巡检仅向督导开放，并需具备后端巡检权限与门店范围。</view>
    <template v-else>
      <view v-if="errorMessage" class="message error">{{ errorMessage }}</view>
      <view v-if="actionMessage" class="message success">{{ actionMessage }}</view>

      <view class="section-head"><text class="section-title">今日任务</text><text class="muted">{{ todos.length }} 项</text></view>
      <view v-if="!canReadTodos" class="state-card small">未授权读取待办；巡检记录仍按巡检权限展示。</view>
      <view v-for="todo in todos.slice(0, 6)" :key="todo.id" class="todo-card">
        <view class="card-head"><text class="card-title">{{ todo.title }}</text><text class="priority">P{{ todo.priority }}</text></view>
        <text class="muted">{{ todo.storeName || todo.storeId || '门店' }} · {{ todo.status }}</text>
        <text class="summary">{{ todo.summary }}</text>
      </view>

      <view v-if="canManage" class="draft-card">
        <view class="section-head inner"><text class="section-title">新建巡检</text><text class="muted">{{ selectedStoreName || '请选择门店' }}</text></view>
        <text class="field-label">巡检门店</text>
        <picker :range="storeNames" :disabled="!stores.length" @change="selectStore">
          <view class="picker-field">{{ selectedStoreName || '请选择巡检门店' }}</view>
        </picker>
        <view class="two-columns">
          <view class="field-block">
            <text class="field-label">巡检日期</text>
            <picker mode="date" :value="inspectionDate" @change="inspectionDate = String($event.detail.value)"><view class="picker-field">{{ inspectionDate }}</view></picker>
          </view>
          <view class="field-block">
            <text class="field-label">督导人</text>
            <input v-model="inspector" class="text-input" placeholder="姓名" :adjust-position="true" :cursor-spacing="140">
          </view>
        </view>

        <button class="camera-button" :loading="uploading" :disabled="uploading || !canUpload || photos.length >= 3" @click="addPhotos">
          {{ photos.length ? '继续拍照或选图' : '拍照或从相册选择' }}
        </button>
        <text class="hint">照片先进入正式附件服务；必须人工确认并关联条款后才能保存。</text>

        <view v-for="photo in photos" :key="photo.id" class="photo-card">
          <image class="photo" :src="photo.path" mode="aspectFill" />
          <view class="photo-content">
            <text class="card-title">{{ photo.fileName }}</text>
            <text class="summary">{{ detectionSummary(photo) }}</text>
            <view v-if="photo.detection" class="ai-suggestion">
              <text class="field-label">AI 建议</text>
              <text class="summary">{{ suggestedClause(photo) }} · 建议扣 {{ number(photo.detection.suggestedDeduction) }} 分</text>
              <button class="secondary-button" :loading="busyPhotoId === photo.id" :disabled="Boolean(busyPhotoId)" @click="acceptAiSuggestion(photo)">人工确认 AI 建议</button>
            </view>
            <view class="manual-box">
              <text class="field-label">人工条款关联</text>
              <picker :range="enabledClauses" range-key="title" @change="selectClause(photo, $event)">
                <view class="picker-field compact">{{ enabledClauses.find((item) => item.id === photo.selectedClauseId)?.title || '选择正式条款' }}</view>
              </picker>
              <view class="manual-row">
                <input class="deduction-input" type="digit" :value="photo.manualDeduction" placeholder="扣分" @input="setManualDeduction(photo, $event)">
                <input v-model="photo.reason" class="reason-input" placeholder="问题或留证说明" :adjust-position="true" :cursor-spacing="140">
              </view>
              <button class="secondary-button" @click="linkManually(photo)">确认人工关联</button>
            </view>
            <text v-if="photo.linkedClauseId" class="linked">已关联：{{ enabledClauses.find((item) => item.id === photo.linkedClauseId)?.title }}</text>
          </view>
        </view>

        <text class="field-label">整改要求（选填）</text>
        <textarea v-model="note" class="note-input" maxlength="500" placeholder="填写责任人、整改期限或复核要求" :adjust-position="true" :cursor-spacing="140" />
        <view class="score-strip"><text>页面预估</text><text>{{ draftScore }} / {{ standard?.fullScore || 0 }} 分</text></view>
        <text class="hint">页面分数仅用于填写；服务端会按正式标准、照片关联和人工决定重新计算。</text>
      </view>

      <view class="section-head records-head"><text class="section-title">巡检与整改</text><text class="muted">{{ filteredRecords.length }} 条</text></view>
      <view class="filter-card"><input v-model="recordKeyword" class="filter-input" placeholder="搜索门店、巡检人、问题或编号"><view class="filter-grid"><picker :range="filterStores" range-key="name" :value="filterStoreIndex" @change="selectFilterStore"><view class="picker-field compact">{{filterStores[filterStoreIndex]?.name}}</view></picker><picker :range="resultOptions" range-key="label" :value="filterResultIndex" @change="selectFilterResult"><view class="picker-field compact">{{resultOptions[filterResultIndex]?.label}}</view></picker><picker mode="date" :value="filterDateFrom" @change="filterDateFrom=String($event.detail.value)"><view class="picker-field compact">{{filterDateFrom||'开始日期'}}</view></picker><picker mode="date" :value="filterDateTo" @change="filterDateTo=String($event.detail.value)"><view class="picker-field compact">{{filterDateTo||'结束日期'}}</view></picker></view><view class="filter-actions"><button @click="resetRecordFilters">重置</button><button class="filter-primary" @click="applyRecordFilters">查询</button></view></view>
      <view v-if="!filteredRecords.length && !loading" class="state-card">暂无符合条件的巡检记录</view>
      <view v-for="record in filteredRecords.slice(0, 30)" :key="record.id" class="record-card" @click="toggleRecord(record.id)">
        <view class="card-head"><text class="card-title">{{ record.storeName || record.storeId }}</text><text class="score">{{ number(record.displayScore ?? record.score) }} 分</text></view>
        <text class="muted">{{ record.inspectionDate }} · {{ record.displayPassed ?? record.passed ? '通过' : '需整改' }}</text>
        <view v-if="expandedRecordId === record.id" class="issue-list">
          <view v-for="(issue, index) in recordIssues(record)" :key="`${issue.standardItemId}-${index}`" class="issue-row">
            <text>{{ issue.deductionReason || '现场问题' }}</text><text class="status-chip">{{ issue.rectificationStatus || '待整改' }}</text>
          </view>
          <text v-if="!recordIssues(record).length" class="muted">该记录暂无待整改条款。</text>
          <button class="secondary-button" @click.stop="openRecordDetail(record.id)">查看历史证据与结论</button>
        </view>
      </view>
    </template>

    <text v-if="canManage && saveBlocked" class="bar-hint">{{ saveBlocked }}</text>
    <SafeActionBar
      v-if="canManage"
      primary-text="保存巡检"
      :loading="saving"
      :disabled="saving || Boolean(saveBlocked)"
      @primary="saveInspection"
    />
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx 24rpx calc(190rpx + env(safe-area-inset-bottom)); background: #f2f6f5; color: #1c1d22; }
.page-head, .section-head, .card-head, .two-columns, .manual-row, .score-strip { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; }
.page-head { margin-bottom: 22rpx; }
.eyebrow, .title, .section-title, .muted, .card-title, .summary, .field-label, .hint, .linked { display: block; }
.eyebrow { color: #71807d; font-size: 24rpx; letter-spacing: 0; }
.title { margin-top: 6rpx; font-size: 38rpx; font-weight: 700; }
.section-head { margin: 28rpx 2rpx 14rpx; }
.section-head.inner { margin-top: 0; }
.section-title, .card-title { font-size: 30rpx; font-weight: 700; }
.todo-card, .draft-card, .photo-card, .record-card, .state-card, .filter-card { margin-bottom: 16rpx; padding: 24rpx; border: 1px solid #d9e6e3; border-radius: 16rpx; background: #fff; box-shadow: 0 8rpx 24rpx rgba(37,39,45,.045); }
.priority, .score, .linked { color: #27655f; font-weight: 700; }
.summary { margin-top: 10rpx; color: #4c5850; font-size: 25rpx; line-height: 1.55; }
.muted, .hint { color: #71807d; font-size: 23rpx; line-height: 1.5; }
.hint { margin: 10rpx 2rpx 20rpx; }
.field-label { margin: 18rpx 0 10rpx; font-size: 25rpx; font-weight: 650; }
.picker-field, .text-input, .deduction-input, .reason-input { min-height: 88rpx; box-sizing: border-box; padding: 0 18rpx; border: 1px solid #d9e6e3; border-radius: 16rpx; background: #f8faf8; font-size: 26rpx; line-height: 88rpx; }
.picker-field.compact { min-height: 88rpx; line-height: 88rpx; }
.two-columns { align-items: flex-start; }
.field-block { flex: 1; min-width: 0; }
.camera-button, .secondary-button, .primary-button, .ghost-button { min-height: 88rpx; line-height: 88rpx; border-radius: 16rpx; font-size: 27rpx; }
.camera-button { margin-top: 24rpx; background: #27655f; color: #fff; }
.photo-card { padding: 16rpx; overflow: hidden; }
.photo { width: 100%; height: 320rpx; border-radius: 16rpx; background: #e9ede9; }
.photo-content { padding: 14rpx 6rpx 4rpx; }
.ai-suggestion { margin-top: 16rpx; padding: 18rpx; border-radius: 16rpx; background: #e6f3f1; }
.manual-box { margin-top: 16rpx; padding: 18rpx; border: 1px dashed #bdc9bf; border-radius: 16rpx; }
.manual-row { margin-top: 12rpx; }
.deduction-input { width: 150rpx; }
.reason-input { flex: 1; }
.secondary-button { margin-top: 14rpx; background: #e6f3f1; color: #27655f; }
.linked { margin-top: 14rpx; font-size: 24rpx; }
.note-input { width: 100%; min-height: 160rpx; box-sizing: border-box; padding: 18rpx; border-radius: 16rpx; background: #f5f7f4; font-size: 27rpx; }
.score-strip { margin-top: 18rpx; padding: 18rpx; border-radius: 14rpx; background: #1c1d22; color: #fff; font-weight: 650; }
.records-head { margin-top: 40rpx; }
.filter-input{box-sizing:border-box;width:100%;min-height:82rpx;padding:0 18rpx;background:#f8faf8;border:1rpx solid #d9e6e3;border-radius:14rpx}.filter-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12rpx;margin-top:12rpx}.filter-actions{display:flex;gap:12rpx;margin-top:14rpx}.filter-actions button{flex:1;margin:0;background:#f7faf9;color:#4e5966}.filter-actions .filter-primary{background:#27655f;color:#fff}
.issue-list { margin-top: 18rpx; padding-top: 14rpx; border-top: 1px solid #edf0ed; }
.issue-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 14rpx; padding: 10rpx 0; font-size: 24rpx; }
.status-chip { flex-shrink: 0; padding: 5rpx 10rpx; border-radius: 10rpx; background: #fff1df; color: #8d5b16; }
.state-card { text-align: center; }
.state-card.small { padding: 22rpx; color: #71807d; font-size: 24rpx; }
.message { margin-bottom: 18rpx; padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 25rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.message.success { background: #eaf5ed; color: #24663e; }
.ghost-button { min-width: 136rpx; background: #fff; color: #1f5752; border: 1px solid #d9e6e3; }
.bar-hint { display: block; margin: 10rpx 0; color: #84631c; text-align: center; font-size: 22rpx; }
button::after { border: 0; }
</style>
