<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ImagePlus, Link2, LoaderCircle, Upload, X } from 'lucide-vue-next'
import {
  getInspectionEvidenceAttachments,
  linkInspectionEvidence,
  uploadAndLinkInspectionEvidence,
  type InspectionEvidenceCandidate,
  type InspectionEvidenceLinkResponse,
  type InspectionItemResult,
  type InspectionRecord,
} from '../../api/inspection'

type EvidenceMode = 'existing' | 'upload'

const props = withDefaults(defineProps<{
  record: InspectionRecord
  items: InspectionItemResult[]
  initialAttachmentId?: number
  /** Exact server-provided photosJson array position for a metadata-only image. */
  sourcePhotoIndex?: number
  initialMode?: EvidenceMode
}>(), {
  initialAttachmentId: undefined,
  sourcePhotoIndex: undefined,
  initialMode: 'existing',
})

const emit = defineEmits<{
  close: []
  saved: [result: InspectionEvidenceLinkResponse]
}>()

const mode = ref<EvidenceMode>(props.initialMode)
const loadingCandidates = ref(false)
const submitting = ref(false)
const candidates = ref<InspectionEvidenceCandidate[]>([])
const selectedAttachmentIds = ref<number[]>([])
const selectedClauseIds = ref<number[]>([])
const selectedSnapshotIds = ref<number[]>([])
const selectedFile = ref<File | null>(null)
const errorMessage = ref('')
const candidateLoadError = ref('')

/**
 * Historical clauses that can be associated.  Uses snapshotId as the stable
 * checkbox key.  Falls back to standardItemId only when the snapshot row still
 * carries a valid standard reference.
 */
const historicalClauses = computed(() => {
  const seen = new Set<number>()
  const clauses: Array<InspectionItemResult & { _key: number; _isSnapshotId: boolean }> = []
  for (const item of props.items) {
    const snapshotId = isPositiveId(item.snapshotId) ? item.snapshotId as number : 0
    const standardItemId = isPositiveId(item.standardItemId) ? Number(item.standardItemId) : 0
    // Prefer snapshotId as the unique key
    if (snapshotId > 0 && !seen.has(snapshotId)) {
      seen.add(snapshotId)
      clauses.push({ ...item, _key: snapshotId, _isSnapshotId: true })
    } else if (standardItemId > 0 && !seen.has(-standardItemId)) {
      // Use negative standardItemId as fallback key to distinguish from snapshotId
      seen.add(-standardItemId)
      clauses.push({ ...item, _key: standardItemId, _isSnapshotId: false })
    }
  }
  return clauses
})

const hasAnyHistoricalClauses = computed(() => historicalClauses.value.length > 0)

const unlinkedCandidates = computed<Array<InspectionEvidenceCandidate & { attachmentId: number }>>(() => candidates.value.filter(
  (candidate): candidate is InspectionEvidenceCandidate & { attachmentId: number } => (
    candidate.status === 'UNLINKED' && isPositiveId(candidate.attachmentId)
  ),
))
const missingCandidates = computed(() => candidates.value.filter((candidate) => (
  candidate.status === 'MISSING' || candidate.status === 'ORIGINAL_NOT_STORED'
)))
const hasAnyClauseSelected = computed(() => selectedClauseIds.value.length > 0 || selectedSnapshotIds.value.length > 0)
const canAssociate = computed(() => (
  selectedAttachmentIds.value.length > 0 && hasAnyClauseSelected.value && !submitting.value
))
const canUpload = computed(() => Boolean(selectedFile.value && hasAnyClauseSelected.value && !submitting.value))

/** Human-readable reason why the primary button is disabled (or empty if enabled). */
const buttonDisabledReason = computed(() => {
  if (submitting.value) return ''
  if (mode.value === 'existing' && selectedAttachmentIds.value.length === 0) return '请先选择已入库图片'
  if (mode.value === 'upload' && !selectedFile.value) return '请先选择微信原图'
  if (!hasAnyClauseSelected.value) {
    if (!hasAnyHistoricalClauses.value) return '该巡检缺少历史条款快照，不能安全关联'
    return '请至少选择一条历史条款'
  }
  return ''
})

watch(
  () => [props.record.id, props.initialAttachmentId, props.sourcePhotoIndex, props.initialMode] as const,
  () => {
    mode.value = props.initialMode
    selectedAttachmentIds.value = []
    selectedClauseIds.value = []
    selectedSnapshotIds.value = []
    selectedFile.value = null
    errorMessage.value = ''
    void loadCandidates()
  },
  { immediate: true },
)

function isPositiveId(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

async function loadCandidates() {
  loadingCandidates.value = true
  errorMessage.value = ''
  candidateLoadError.value = ''
  try {
    const response = await getInspectionEvidenceAttachments(String(props.record.id))
    candidates.value = Array.isArray(response.candidates) ? response.candidates : []
    const requestedId = props.initialAttachmentId
    const sourceCandidate = candidateForSelectedPhoto(requestedId)
    // A historic metadata entry can retain a positive attachmentId after its
    // original bytes were removed. The server-generated photoIndex is the only
    // safe way to identify that exact entry; it must open the upload flow even
    // when other, unrelated UNLINKED attachments are available.
    if (sourceCandidate && isOriginalNotStored(sourceCandidate)) {
      selectedAttachmentIds.value = []
      mode.value = 'upload'
    } else if (isPositiveId(requestedId) && sourceCandidate?.status === 'UNLINKED' && sourceCandidate.attachmentId === requestedId) {
      selectedAttachmentIds.value = [requestedId]
      mode.value = 'existing'
    } else if (!unlinkedCandidates.value.length && missingCandidates.value.length) {
      mode.value = 'upload'
    }
  } catch (error) {
    candidates.value = []
    candidateLoadError.value = candidateLoadErrorMessage(error)
  } finally {
    loadingCandidates.value = false
  }
}

function candidateLoadErrorMessage(error: unknown) {
  const apiError = error as { status?: number }
  if (apiError.status === 401) return '登录已失效，请重新登录后重试。'
  if (apiError.status === 403) return '你无权查看该巡检记录的历史证据。'
  if (apiError.status === 404) return '历史证据服务尚未完成部署，请联系管理员。'
  return '读取失败，请稍后重试。'
}

function candidateForSelectedPhoto(attachmentId?: number) {
  if (Number.isInteger(props.sourcePhotoIndex) && Number(props.sourcePhotoIndex) >= 0) {
    const sourceCandidate = candidates.value.find((candidate) => candidate.photoIndex === props.sourcePhotoIndex)
    if (sourceCandidate) return sourceCandidate
  }
  return isPositiveId(attachmentId)
    ? candidates.value.find((candidate) => candidate.attachmentId === attachmentId)
    : undefined
}

function isOriginalNotStored(candidate: InspectionEvidenceCandidate) {
  return candidate.status === 'MISSING' || candidate.status === 'ORIGINAL_NOT_STORED'
}

function toggleAttachment(attachmentId: number, checked: boolean) {
  selectedAttachmentIds.value = checked
    ? Array.from(new Set([...selectedAttachmentIds.value, attachmentId]))
    : selectedAttachmentIds.value.filter((id) => id !== attachmentId)
}

function toggleClause(clauseKey: number, isSnapshotId: boolean, checked: boolean) {
  if (isSnapshotId) {
    selectedSnapshotIds.value = checked
      ? Array.from(new Set([...selectedSnapshotIds.value, clauseKey]))
      : selectedSnapshotIds.value.filter((id) => id !== clauseKey)
  } else {
    selectedClauseIds.value = checked
      ? Array.from(new Set([...selectedClauseIds.value, clauseKey]))
      : selectedClauseIds.value.filter((id) => id !== clauseKey)
  }
}

function selectMode(next: EvidenceMode) {
  mode.value = next
  errorMessage.value = ''
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] || null
  input.value = ''
  if (!file) return
  if (!file.type.toLowerCase().startsWith('image/')) {
    selectedFile.value = null
    errorMessage.value = '仅支持补传图片原图，请重新从微信选择图片文件。'
    return
  }
  selectedFile.value = file
  errorMessage.value = ''
}

async function associateExisting() {
  if (!canAssociate.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const result = await linkInspectionEvidence(String(props.record.id), {
      attachmentIds: [...selectedAttachmentIds.value],
      clauseIds: [...selectedClauseIds.value],
      historicalSnapshotIds: [...selectedSnapshotIds.value],
    })
    emit('saved', result)
  } catch (error) {
    errorMessage.value = evidenceError(error, '证据关联没有保存成功，请稍后重试。')
  } finally {
    submitting.value = false
  }
}

async function uploadAndAssociate() {
  const file = selectedFile.value
  if (!file || !canUpload.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const result = await uploadAndLinkInspectionEvidence(
      String(props.record.id),
      file,
      [...selectedClauseIds.value],
      [...selectedSnapshotIds.value],
      props.sourcePhotoIndex,
    )
    emit('saved', result)
  } catch (error) {
    errorMessage.value = evidenceError(error, '原图补传没有保存成功，请稍后重试。')
  } finally {
    submitting.value = false
  }
}

function requestClose() {
  if (!submitting.value) emit('close')
}

function evidenceError(error: unknown, fallback: string) {
  const apiError = error as { status?: number; code?: string }
  if (apiError.status === 403) return '无查看权限，不能为该门店补传或关联巡检证据。'
  if (apiError.status === 401) return '登录已失效，请重新登录后再操作。'
  if (apiError.status === 404 || apiError.code === 'ATTACHMENT_NOT_FOUND') return '图片不存在，请从微信重新选择原图补传。'
  if (apiError.code === 'ATTACHMENT_ORIGINAL_NOT_STORED') return '原图未入库，需从微信重新选择原图补传。'
  if (apiError.code === 'INSPECTION_EVIDENCE_IMAGE_REQUIRED') return '只能补传图片原图，请重新选择图片文件。'
  if (apiError.code === 'INSPECTION_EVIDENCE_UNLINKED') return '请先选择至少一张已入库图片和一个历史条款。'
  return fallback
}

function candidateDescription(candidate: InspectionEvidenceCandidate) {
  if (candidate.status === 'MISSING' || candidate.status === 'ORIGINAL_NOT_STORED') return '原图未入库，需补传'
  if (candidate.status === 'LINKED') return '已关联历史条款，不能在此重复关联'
  if (candidate.status === 'INVALID_TYPE') return '附件不是图片，不能作为现场证据'
  return '已入库，待人工选择历史条款关联'
}

function clauseLabel(item: InspectionItemResult) {
  const code = String(item.code || '').trim()
  const title = String(item.title || item.description || '历史条款').trim()
  return [code, title].filter(Boolean).join(' · ')
}
</script>

<template>
  <div class="historical-evidence-backdrop" role="presentation" tabindex="-1" @click.self="requestClose" @keydown.esc="requestClose">
    <section class="historical-evidence-dialog" role="dialog" aria-modal="true" aria-labelledby="historical-evidence-title">
      <header>
        <div>
          <span class="dialog-kicker">历史巡检证据</span>
          <h3 id="historical-evidence-title">补传并关联证据</h3>
          <p>仅补充证据关联，不会重算历史得分、扣分、结果、标准版本或整改状态。</p>
        </div>
        <button class="dialog-close" type="button" data-testid="historical-evidence-close" :disabled="submitting" aria-label="关闭补传并关联证据" @click="requestClose"><X :size="18" /></button>
      </header>

      <div v-if="candidateLoadError" class="dialog-error" role="alert">
        <span>{{ candidateLoadError }}</span>
        <button class="candidate-retry" type="button" :disabled="loadingCandidates" @click="loadCandidates">重试</button>
      </div>
      <div v-if="errorMessage" class="dialog-error" role="alert">{{ errorMessage }}</div>

      <div class="evidence-mode-tabs" role="tablist" aria-label="历史证据处理方式">
        <button type="button" :class="{ active: mode === 'existing' }" :aria-selected="mode === 'existing'" @click="selectMode('existing')">
          <Link2 :size="16" />关联已有证据
        </button>
        <button type="button" :class="{ active: mode === 'upload' }" :aria-selected="mode === 'upload'" @click="selectMode('upload')">
          <ImagePlus :size="16" />补传并关联证据
        </button>
      </div>

      <div class="historical-evidence-body">
        <section v-if="mode === 'existing'" class="evidence-step" aria-labelledby="existing-evidence-title">
          <div class="step-heading"><span>1</span><div><h4 id="existing-evidence-title">选择已入库图片</h4><p>只显示后端核验为本条历史巡检、且尚未关联条款的图片。</p></div></div>
          <div v-if="loadingCandidates" class="evidence-loading"><LoaderCircle class="spin" :size="17" />正在核对可关联证据…</div>
          <div v-else-if="candidateLoadError" class="evidence-empty">
            <b>候选证据暂未读取</b>
            <span>请使用上方“重试”再次读取。</span>
          </div>
          <div v-else-if="unlinkedCandidates.length" class="evidence-candidate-list">
            <label v-for="candidate in unlinkedCandidates" :key="candidate.attachmentId" class="evidence-candidate">
              <input
                type="checkbox"
                :checked="selectedAttachmentIds.includes(candidate.attachmentId)"
                @change="toggleAttachment(candidate.attachmentId, ($event.target as HTMLInputElement).checked)"
              />
              <span><b>{{ candidate.fileName || '现场图片' }}</b><small>{{ candidateDescription(candidate) }}</small></span>
            </label>
          </div>
          <div v-else class="evidence-empty"><span>暂无可关联的已入库图片，请从微信重新选择原图补传。</span></div>
          <p v-if="missingCandidates.length" class="missing-original-hint">有 {{ missingCandidates.length }} 张历史图片仅保留文件名或元数据：原图未入库，需补传。</p>
        </section>

        <section v-else class="evidence-step" aria-labelledby="upload-evidence-title">
          <div class="step-heading"><span>1</span><div><h4 id="upload-evidence-title">从微信重新选择原图补传</h4><p>原图将通过受认证的后端上传并直接绑定当前历史巡检，不会保存在浏览器本地。</p></div></div>
          <label class="upload-original-button">
            <input type="file" accept="image/*" capture="environment" :disabled="submitting" @change="onFileChange" />
            <Upload :size="16" />{{ selectedFile ? '重新选择原图' : '选择图片原图' }}
          </label>
          <p v-if="selectedFile" class="selected-original"><b>{{ selectedFile.name }}</b><span>已选择，仍需人工勾选下方历史条款后才能提交。</span></p>
          <p v-else class="missing-original-hint">原图未入库，需补传。请勿按文件名补配，必须重新选择实际图片。</p>
        </section>

        <section class="evidence-step clause-step" aria-labelledby="historical-clause-title">
          <div class="step-heading"><span>2</span><div><h4 id="historical-clause-title">人工选择历史条款</h4><p>可选择一个或多个条款；系统不会根据文件名、AI 结果或当前标准自动推断。</p></div></div>
          <div v-if="hasAnyHistoricalClauses" class="historical-clause-list">
            <label v-for="item in historicalClauses" :key="item._key" class="historical-clause-option">
              <input
                type="checkbox"
                :checked="item._isSnapshotId ? selectedSnapshotIds.includes(item._key) : selectedClauseIds.includes(item._key)"
                @change="toggleClause(item._key, item._isSnapshotId, ($event.target as HTMLInputElement).checked)"
              />
              <span><b>{{ clauseLabel(item) }}</b><small>{{ item.categoryName || item.dimension || '历史条款' }} · {{ item.deductionReason || '未记录扣分原因' }}</small></span>
            </label>
          </div>
          <div v-else class="evidence-empty"><b>该巡检缺少历史条款快照，不能安全关联</b><span>{{ props.items.length ? '历史快照行未携带可用的条款编号，请联系管理员修复。' : '该巡检在创建时未保存条款快照，不能将图片关联到当前标准条款。' }}</span></div>
        </section>
      </div>

      <footer>
        <p v-if="buttonDisabledReason" class="footer-disabled-reason">{{ buttonDisabledReason }}</p>
        <p v-else>提交后仅更新照片与条款的证据 ID 关联，并写入操作日志。</p>
        <div>
          <button class="secondary-button" type="button" data-testid="historical-evidence-cancel" :disabled="submitting" @click.stop="requestClose">取消</button>
          <button v-if="mode === 'existing'" class="primary-button" type="button" :disabled="!canAssociate" @click="associateExisting">
            <LoaderCircle v-if="submitting" class="spin" :size="16" />{{ submitting ? '正在关联…' : '确认关联' }}
          </button>
          <button v-else class="primary-button" type="button" :disabled="!canUpload" @click="uploadAndAssociate">
            <LoaderCircle v-if="submitting" class="spin" :size="16" />{{ submitting ? '正在补传…' : '补传并关联' }}
          </button>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.historical-evidence-backdrop { position: fixed; inset: 0; z-index: var(--ds-z-modal, 1400); display: grid; place-items: center; padding: 20px; background: rgba(15, 23, 42, .48); }
.historical-evidence-dialog { display: grid; width: min(760px, 100%); max-height: min(760px, calc(100vh - 40px)); overflow: hidden; border: 1px solid var(--line); border-radius: 14px; background: var(--ds-surface, #fff); box-shadow: 0 24px 64px rgba(15, 23, 42, .28); }
.historical-evidence-dialog > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 20px 22px 14px; border-bottom: 1px solid var(--line); }
.dialog-kicker { color: var(--primary-dark); font-size: 12px; font-weight: 800; }
.historical-evidence-dialog h3, .historical-evidence-dialog h4, .historical-evidence-dialog p { margin: 0; }
.historical-evidence-dialog h3 { margin-top: 3px; color: var(--ink); font-size: 20px; }
.historical-evidence-dialog header p { margin-top: 7px; color: var(--muted); font-size: 13px; line-height: 1.5; }
.dialog-close { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border: 0; border-radius: 8px; background: transparent; color: var(--muted); }
.dialog-close:hover:not(:disabled), .dialog-close:focus-visible:not(:disabled) { background: var(--ds-surface-muted); color: var(--ink); }
.dialog-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 14px 22px 0; padding: 10px 12px; border: 1px solid rgba(217, 79, 61, .32); border-radius: 8px; background: var(--ds-danger-soft); color: var(--bad); font-size: 13px; line-height: 1.5; }
.candidate-retry { display: inline-flex; min-height: 32px; flex: 0 0 auto; align-items: center; justify-content: center; padding: 0 10px; border: 1px solid currentColor; border-radius: 7px; background: transparent; color: inherit; font: inherit; font-weight: 800; white-space: nowrap; writing-mode: horizontal-tb; }
.candidate-retry:disabled { cursor: not-allowed; opacity: .55; }
.evidence-mode-tabs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 14px 22px 0; padding: 4px; border-radius: 9px; background: var(--ds-surface-muted); }
.evidence-mode-tabs button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 36px; border: 0; border-radius: 7px; background: transparent; color: var(--muted); font-weight: 800; }
.evidence-mode-tabs button.active { background: #fff; color: var(--primary-dark); box-shadow: 0 1px 3px rgba(15, 23, 42, .12); }
.historical-evidence-body { display: grid; gap: 16px; overflow: auto; padding: 16px 22px 20px; }
.evidence-step { display: grid; gap: 10px; padding: 14px; border: 1px solid var(--line); border-radius: 10px; background: #fff; }
.step-heading { display: flex; align-items: flex-start; gap: 10px; }
.step-heading > span { display: grid; width: 23px; height: 23px; flex: 0 0 auto; place-items: center; border-radius: 50%; background: var(--primary-soft); color: var(--primary-dark); font-size: 12px; font-weight: 900; }
.step-heading h4 { color: var(--ink); font-size: 14px; }
.step-heading p, .evidence-empty span, .selected-original span { margin-top: 3px; color: var(--muted); font-size: 12px; line-height: 1.5; }
.evidence-candidate-list, .historical-clause-list { display: grid; gap: 7px; max-height: 206px; overflow: auto; padding-right: 2px; }
.evidence-candidate, .historical-clause-option { display: flex; align-items: flex-start; gap: 9px; padding: 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--ds-surface-muted); cursor: pointer; }
.evidence-candidate:has(input:checked), .historical-clause-option:has(input:checked) { border-color: rgba(37, 111, 105, .45); background: var(--primary-soft); }
.evidence-candidate input, .historical-clause-option input { margin-top: 3px; }
.evidence-candidate span, .historical-clause-option span, .selected-original { display: grid; min-width: 0; gap: 2px; }
.evidence-candidate b, .historical-clause-option b, .selected-original b { overflow: hidden; color: var(--ink); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.evidence-candidate small, .historical-clause-option small { color: var(--muted); font-size: 12px; line-height: 1.45; }
.evidence-empty { display: grid; gap: 3px; padding: 12px; border-radius: 8px; background: var(--ds-surface-muted); }
.evidence-empty b { color: var(--ink); font-size: 13px; }
.missing-original-hint { padding: 9px 10px; border-radius: 7px; background: var(--ds-warning-soft); color: #77440d; font-size: 12px; font-weight: 700; line-height: 1.5; }
.evidence-loading { display: inline-flex; align-items: center; gap: 7px; min-height: 36px; color: var(--muted); font-size: 13px; }
.upload-original-button { display: inline-flex; width: fit-content; align-items: center; gap: 7px; min-height: 44px; padding: 0 12px; border: 1px solid var(--primary); border-radius: 8px; background: var(--primary-soft); color: var(--primary-dark); font-size: 13px; font-weight: 800; cursor: pointer; }
.upload-original-button input { display: none; }
.selected-original { padding: 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--ds-surface-muted); }
.historical-evidence-dialog > footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 14px 22px; border-top: 1px solid var(--line); background: var(--ds-surface, #fff); }
.footer-disabled-reason { margin: 0; color: var(--bad); font-size: 12px; font-weight: 700; line-height: 1.45; }
.historical-evidence-dialog > footer > p { color: var(--muted); font-size: 12px; line-height: 1.45; }
.historical-evidence-dialog > footer > div { display: grid; grid-template-columns: repeat(2, 148px); gap: 12px; flex: 0 0 auto; align-items: center; justify-content: flex-end; }
.historical-evidence-dialog .primary-button, .historical-evidence-dialog .secondary-button { display: inline-flex; width: 100%; height: 48px; align-items: center; justify-content: center; gap: 6px; white-space: nowrap; writing-mode: horizontal-tb; }
.historical-evidence-dialog .primary-button:disabled { cursor: not-allowed; opacity: .55; }
@media (max-width: 620px) { .historical-evidence-backdrop { align-items: end; padding: 0; } .historical-evidence-dialog { width: 100%; max-height: min(88vh, 760px); border-bottom-right-radius: 0; border-bottom-left-radius: 0; } .historical-evidence-dialog > header, .historical-evidence-body, .historical-evidence-dialog > footer { padding-right: 16px; padding-left: 16px; } .dialog-close { width: 44px; height: 44px; } .evidence-mode-tabs { margin-right: 16px; margin-left: 16px; } .evidence-mode-tabs button, .candidate-retry { min-height: 44px; } .historical-evidence-dialog > footer { align-items: stretch; flex-direction: column; } .historical-evidence-dialog > footer > div { grid-template-columns: repeat(2, 1fr); width: 100%; } .dialog-error { align-items: flex-start; flex-wrap: wrap; } }
</style>
