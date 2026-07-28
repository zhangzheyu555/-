<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardCheck,
  ExternalLink,
  Eye,
  FileImage,
  LoaderCircle,
  RefreshCw,
  Send,
  X,
  XCircle,
} from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import UiButton from '../components/ui/UiButton.vue'
import { useForegroundReload } from '../composables/useForegroundReload'
import { fetchInspectionAttachment } from '../api/inspection'
import {
  getInspectionRectificationReviewQueue,
  isInspectionRectificationServiceUnavailable,
  reviewInspectionRectification,
  type InspectionRectificationTask,
} from '../api/inspectionRectification'

type ReviewDecision = 'APPROVED' | 'REJECTED'

interface EvidencePreview {
  attachmentId: number
  recordId: string
  storeName: string
  contentType: string
  url: string
}

const tasks = ref<InspectionRectificationTask[]>([])
const reviewNotes = reactive<Record<string, string>>({})
const taskErrors = reactive<Record<string, string>>({})
const loading = ref(false)
const submittingId = ref('')
const error = ref('')
const loadFailed = ref(false)
const actionMessage = ref('')
const pendingDecision = ref<{ task: InspectionRectificationTask; decision: ReviewDecision } | null>(null)
const decisionDialogError = ref('')
const discardDialogOpen = ref(false)
let pendingDiscardAction: (() => void) | null = null
let pendingDiscardCancel: (() => void) | null = null
const evidencePreview = ref<EvidencePreview | null>(null)
const evidencePreviewDialog = ref<HTMLElement | null>(null)
const loadingAttachmentKey = ref('')
let evidencePreviewController: AbortController | null = null
let evidencePreviewTrigger: HTMLElement | null = null
let previewAppRoot: HTMLElement | null = null
let previewAppWasInert = false

const hasDirtyReviewNotes = computed(() => Object.values(reviewNotes).some((note) => String(note || '').trim()))
const hasPendingChanges = computed(() => Boolean(
  submittingId.value
  || hasDirtyReviewNotes.value,
))
const pendingDecisionTitle = computed(() => pendingDecision.value?.decision === 'APPROVED'
  ? '确认通过本次整改？'
  : '确认驳回本次整改？')
const pendingDecisionMessage = computed(() => {
  const target = pendingDecision.value
  if (!target) return ''
  return target.decision === 'APPROVED'
    ? `通过后，“${target.task.storeName || target.task.storeId}”将从待复核队列移除，并写入操作日志。`
    : `驳回后，店长会收到复核备注并需要重新整改。“${target.task.storeName || target.task.storeId}”仍可再次提交。`
})
const { markFresh } = useForegroundReload(loadQueue, {
  canReload: () => !hasPendingChanges.value && !loading.value,
})

onMounted(() => {
  void loadQueue()
})

async function loadQueue() {
  if (loading.value) return false
  loading.value = true
  loadFailed.value = false
  error.value = ''
  try {
    tasks.value = await getInspectionRectificationReviewQueue()
    Object.keys(taskErrors).forEach((recordId) => delete taskErrors[recordId])
    markFresh()
    return true
  } catch (loadError) {
    loadFailed.value = true
    error.value = unavailableMessage(loadError, '整改复核队列加载失败，请稍后重试。')
    return false
  } finally {
    loading.value = false
  }
}

function requestReview(task: InspectionRectificationTask, decision: ReviewDecision) {
  if (submittingId.value) return
  const note = String(reviewNotes[task.recordId] || '').trim()
  if (!note) {
    taskErrors[task.recordId] = decision === 'APPROVED' ? '请填写复核通过说明。' : '请填写驳回原因，便于店长继续整改。'
    return
  }
  if (decision === 'APPROVED' && !task.evidenceAttachmentIds.length) {
    taskErrors[task.recordId] = '未关联整改现场证据，不能通过复核；请驳回并要求店长补充。'
    return
  }
  taskErrors[task.recordId] = ''
  decisionDialogError.value = ''
  pendingDecision.value = { task, decision }
}

function cancelReviewDecision() {
  if (submittingId.value) return
  pendingDecision.value = null
  decisionDialogError.value = ''
}

async function confirmReview() {
  const target = pendingDecision.value
  if (!target || submittingId.value) return
  const { task, decision } = target
  const note = String(reviewNotes[task.recordId] || '').trim()
  if (!note) {
    decisionDialogError.value = '复核备注已被清空，请返回填写后再提交。'
    return
  }
  submittingId.value = task.recordId
  taskErrors[task.recordId] = ''
  decisionDialogError.value = ''
  actionMessage.value = ''
  try {
    await reviewInspectionRectification(task.recordId, { decision, note })
    tasks.value = tasks.value.filter((item) => item.recordId !== task.recordId)
    delete reviewNotes[task.recordId]
    pendingDecision.value = null
    actionMessage.value = decision === 'APPROVED'
      ? '整改已复核通过，处理记录已写入操作日志。'
      : '整改已驳回，店长将看到驳回原因并可重新提交。'
    markFresh()
  } catch (reviewError) {
    const message = unavailableMessage(reviewError, '复核提交失败，请稍后重试。')
    taskErrors[task.recordId] = message
    decisionDialogError.value = message
  } finally {
    submittingId.value = ''
  }
}

function clearTaskError(recordId: string) {
  taskErrors[recordId] = ''
}

function requestDiscardConfirmation(action: () => void, cancel: () => void = () => {}) {
  if (!hasDirtyReviewNotes.value) {
    action()
    return
  }
  if (discardDialogOpen.value || submittingId.value || pendingDecision.value) {
    cancel()
    return
  }
  pendingDiscardAction = action
  pendingDiscardCancel = cancel
  discardDialogOpen.value = true
}

function keepReviewNotes() {
  const cancel = pendingDiscardCancel
  pendingDiscardAction = null
  pendingDiscardCancel = null
  discardDialogOpen.value = false
  cancel?.()
}

function discardReviewNotes() {
  const action = pendingDiscardAction
  pendingDiscardAction = null
  pendingDiscardCancel = null
  discardDialogOpen.value = false
  Object.keys(reviewNotes).forEach((recordId) => delete reviewNotes[recordId])
  action?.()
}

onBeforeRouteLeave(() => {
  if (!hasDirtyReviewNotes.value) return true
  if (discardDialogOpen.value || submittingId.value || pendingDecision.value || evidencePreview.value) return false
  return new Promise<boolean>((resolve) => {
    requestDiscardConfirmation(
      () => resolve(true),
      () => resolve(false),
    )
  })
})

async function openEvidencePreview(task: InspectionRectificationTask, attachmentId: number) {
  const key = `${task.recordId}:${attachmentId}`
  if (loadingAttachmentKey.value) return
  closeEvidencePreview()
  evidencePreviewTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const controller = new AbortController()
  evidencePreviewController = controller
  loadingAttachmentKey.value = key
  taskErrors[task.recordId] = ''
  try {
    const blob = await fetchInspectionAttachment(attachmentId, controller.signal)
    if (controller.signal.aborted) return
    evidencePreview.value = {
      attachmentId,
      recordId: task.recordId,
      storeName: task.storeName || task.storeId,
      contentType: String(blob.type || '').toLowerCase(),
      url: URL.createObjectURL(blob),
    }
    previewAppRoot = document.getElementById('app')
    previewAppWasInert = Boolean(previewAppRoot?.inert)
    if (previewAppRoot) previewAppRoot.inert = true
    await nextTick()
    evidencePreviewDialog.value?.focus()
  } catch (previewError) {
    if (!controller.signal.aborted) {
      taskErrors[task.recordId] = unavailableMessage(previewError, '现场证据读取失败，请稍后重试。')
    }
  } finally {
    if (evidencePreviewController === controller) evidencePreviewController = null
    if (loadingAttachmentKey.value === key) loadingAttachmentKey.value = ''
  }
}

function closeEvidencePreview() {
  evidencePreviewController?.abort()
  evidencePreviewController = null
  loadingAttachmentKey.value = ''
  if (evidencePreview.value?.url) URL.revokeObjectURL(evidencePreview.value.url)
  evidencePreview.value = null
  if (previewAppRoot && !previewAppWasInert) previewAppRoot.inert = false
  previewAppRoot = null
  evidencePreviewTrigger?.focus()
  evidencePreviewTrigger = null
}

function previewIsImage() {
  return evidencePreview.value?.contentType.startsWith('image/')
}

function previewIsPdf() {
  return evidencePreview.value?.contentType === 'application/pdf'
}

function evidenceText(task: InspectionRectificationTask) {
  if (!task.evidenceAttachmentIds.length) return '未发现已关联现场证据'
  return `已关联 ${task.evidenceAttachmentIds.length} 份现场证据`
}

function formatTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

function unavailableMessage(reason: unknown, fallback: string) {
  if (isInspectionRectificationServiceUnavailable(reason)) {
    return '整改复核服务暂未部署或当前候选版本不匹配，无法进行复核。请切换到已部署整改服务的预发布候选后重试。'
  }
  return reason instanceof Error && reason.message ? reason.message : fallback
}

onBeforeUnmount(() => {
  closeEvidencePreview()
})
</script>

<template>
  <section class="page-panel review-page">
    <PageHeader title="整改复核">
      <template #actions>
        <div class="review-page-actions">
          <span>{{ tasks.length }} 项待复核</span>
          <UiButton
            variant="secondary"
            size="sm"
            :loading="loading"
            :disabled="hasPendingChanges || Boolean(pendingDecision)"
            :title="hasPendingChanges ? '填写复核备注后需先处理或放弃，避免刷新丢失' : '重新读取待复核整改'"
            @click="loadQueue"
          >
            <template #icon><RefreshCw :size="15" /></template>
            刷新队列
          </UiButton>
        </div>
      </template>
    </PageHeader>

    <div v-if="error" class="error-box" role="alert">
      {{ error }}
      <UiButton v-if="loadFailed && !hasPendingChanges" variant="ghost" size="sm" :loading="loading" @click="loadQueue">重试</UiButton>
    </div>
    <div v-if="actionMessage" class="success-box" role="status">{{ actionMessage }}</div>

    <div v-if="loading && !tasks.length" class="review-loading" aria-live="polite">
      <LoaderCircle class="spin" :size="22" /> 正在读取待复核整改…
    </div>
    <div v-else-if="!tasks.length && !error" class="empty-state">当前没有待复核的巡检整改。</div>

    <div v-else class="review-list">
      <article
        v-for="task in tasks"
        :key="task.recordId"
        class="review-card"
        :class="{ busy: Boolean(submittingId) }"
        :aria-busy="submittingId === task.recordId"
      >
        <header class="review-card-head">
          <div>
            <span>待复核整改</span>
            <h2>{{ task.storeName || task.storeId }}</h2>
          </div>
          <span class="review-status">待督导复核</span>
        </header>

        <dl class="review-meta">
          <div><dt>巡检日期</dt><dd>{{ task.inspectionDate || '—' }}</dd></div>
          <div><dt>最近更新时间</dt><dd>{{ formatTime(task.updatedAt) }}</dd></div>
          <div><dt>现场证据</dt><dd>{{ evidenceText(task) }}</dd></div>
        </dl>

        <section class="review-section issue-section">
          <div><ClipboardCheck :size="18" /><b>巡检问题</b></div>
          <p>请核对原巡检问题条款、整改说明与现场证据，避免仅凭备注作出结论。</p>
          <RouterLink
            class="review-record-link"
            :to="{ path: '/operations/inspection/records', query: { recordId: task.recordId } }"
          >
            查看原巡检 <ExternalLink :size="14" />
          </RouterLink>
        </section>
        <section class="review-section">
          <div><CheckCircle2 :size="18" /><b>整改要求</b></div>
          <p>{{ task.requirement || '请核对整改结果是否满足巡检要求。' }}</p>
        </section>
        <section class="review-section manager-note-section">
          <div><Send :size="18" /><b>店长整改说明</b></div>
          <p>{{ task.managerNote || '店长未填写整改说明。' }}</p>
        </section>

        <ul v-if="task.evidenceAttachmentIds.length" class="review-evidence-list" aria-label="已关联现场证据">
          <li v-for="attachmentId in task.evidenceAttachmentIds" :key="attachmentId">
            <button
              type="button"
              class="review-evidence-button"
              :disabled="Boolean(submittingId) || Boolean(loadingAttachmentKey)"
              :aria-label="`预览整改现场证据 ${attachmentId}`"
              @click="openEvidencePreview(task, attachmentId)"
            >
              <LoaderCircle v-if="loadingAttachmentKey === `${task.recordId}:${attachmentId}`" class="spin" :size="16" />
              <FileImage v-else :size="16" />
              <span>整改现场证据 #{{ attachmentId }}</span>
              <small>点击安全预览</small>
              <Eye :size="16" />
            </button>
          </li>
        </ul>
        <div v-else class="evidence-warning"><AlertTriangle :size="17" /> 未发现现场证据，不能据此判断整改已完成。</div>

        <label class="review-note-field" :for="`review-note-${task.recordId}`">
          <span>复核备注</span>
          <textarea
            :id="`review-note-${task.recordId}`"
            v-model="reviewNotes[task.recordId]"
            rows="3"
            maxlength="1000"
            :disabled="Boolean(submittingId)"
            placeholder="通过时说明核对结果；驳回时说明仍需整改的具体事项"
            @input="clearTaskError(task.recordId)"
          />
          <small class="review-note-count">当前 {{ (reviewNotes[task.recordId] || '').length }}/1000 字</small>
        </label>
        <div v-if="taskErrors[task.recordId]" class="error-box compact-error" role="alert">{{ taskErrors[task.recordId] }}</div>

        <footer class="review-actions">
          <UiButton
            variant="danger"
            :loading="submittingId === task.recordId"
            :disabled="Boolean(submittingId)"
            @click="requestReview(task, 'REJECTED')"
          >
            <template #icon><XCircle :size="16" /></template>
            驳回整改
          </UiButton>
          <UiButton
            variant="primary"
            :loading="submittingId === task.recordId"
            :disabled="Boolean(submittingId) || !task.evidenceAttachmentIds.length"
            :title="task.evidenceAttachmentIds.length ? '通过本次整改复核' : '缺少现场证据，不能通过复核'"
            @click="requestReview(task, 'APPROVED')"
          >
            <template #icon><CheckCircle2 :size="16" /></template>
            通过复核
          </UiButton>
        </footer>
      </article>
    </div>

    <ActionConfirmDialog
      :open="Boolean(pendingDecision)"
      :title="pendingDecisionTitle"
      :message="pendingDecisionMessage"
      :confirm-label="pendingDecision?.decision === 'APPROVED' ? '确认通过' : '确认驳回'"
      :confirm-variant="pendingDecision?.decision === 'REJECTED' ? 'danger' : 'primary'"
      :busy="Boolean(submittingId)"
      :error="decisionDialogError"
      @cancel="cancelReviewDecision"
      @confirm="confirmReview"
    />
    <ActionConfirmDialog
      :open="discardDialogOpen"
      title="放弃未提交的复核备注？"
      message="离开页面后，当前填写的所有复核备注都将被清空，且无法恢复。"
      confirm-label="放弃备注"
      cancel-label="继续复核"
      confirm-variant="danger"
      @cancel="keepReviewNotes"
      @confirm="discardReviewNotes"
    />
  </section>

  <Teleport to="body">
    <div v-if="evidencePreview" class="evidence-preview-backdrop" @click.self="closeEvidencePreview">
      <section
        ref="evidencePreviewDialog"
        class="evidence-preview-dialog"
        role="dialog"
        aria-modal="true"
        tabindex="-1"
        :aria-label="`整改现场证据 ${evidencePreview.attachmentId}`"
        @keydown.esc.stop.prevent="closeEvidencePreview"
      >
        <header>
          <div>
            <b>{{ evidencePreview.storeName }} · 现场证据</b>
            <span>附件 #{{ evidencePreview.attachmentId }}</span>
          </div>
          <UiButton variant="ghost" icon-only aria-label="关闭证据预览" @click="closeEvidencePreview">
            <template #icon><X :size="20" /></template>
          </UiButton>
        </header>
        <img
          v-if="previewIsImage()"
          :src="evidencePreview.url"
          :alt="`${evidencePreview.storeName} 整改现场证据`"
        >
        <iframe
          v-else-if="previewIsPdf()"
          :src="evidencePreview.url"
          title="整改现场 PDF 证据"
        />
        <div v-else class="evidence-preview-fallback">
          <FileImage :size="28" />
          <p>该文件类型暂不支持在线预览，可在当前登录会话中下载核对。</p>
          <a :href="evidencePreview.url" :download="`整改证据-${evidencePreview.attachmentId}`">下载证据</a>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.review-page, .review-list { display: grid; gap: var(--space-4, 16px); }.review-list { grid-template-columns: repeat(auto-fit, minmax(min(100%, 470px), 1fr)); align-items: start; }.review-card { display: grid; gap: 14px; padding: clamp(16px, 3vw, 22px); border: 1px solid var(--line); border-radius: 12px; background: #fff; }.review-card-head, .review-section > div, .review-actions { display: flex; align-items: center; gap: 9px; }.review-card-head { justify-content: space-between; align-items: flex-start; }.review-card-head span { color: var(--muted); font-size: 12px; font-weight: 800; }.review-card-head h2 { margin: 4px 0 0; font-size: 20px; }.review-status { padding: 5px 8px; border-radius: 999px; background: var(--ds-warning-soft, #fff7e7); color: var(--warn); white-space: nowrap; }.review-meta { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; margin: 0; overflow: hidden; border: 1px solid var(--line); border-radius: 10px; background: var(--line); }.review-meta div { padding: 10px; background: #fafbfc; }.review-meta dt { color: var(--muted); font-size: 12px; }.review-meta dd { margin: 4px 0 0; color: var(--ink); font-size: 13px; font-weight: 800; overflow-wrap: anywhere; }.review-section { padding: 11px 12px; border-left: 4px solid var(--primary); background: var(--primary-soft); }.issue-section { border-left-color: var(--warn); background: #fffbf2; }.manager-note-section { border-left-color: #64748b; background: #f6f8fa; }.review-section p { margin: 8px 0 0; color: var(--ink); line-height: 1.65; white-space: pre-wrap; }.review-evidence-list { display: grid; gap: 6px; margin: 0; padding: 0; list-style: none; }.review-evidence-list li { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 9px 10px; border: 1px solid var(--line); border-radius: 9px; font-size: 13px; }.review-evidence-list span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.review-evidence-list small { color: var(--muted); font-size: 11px; }.evidence-warning { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border: 1px solid rgba(217, 119, 6, .3); border-radius: 9px; background: #fffbf2; color: #9a5b00; font-size: 13px; }.review-note-field { display: grid; gap: 7px; }.review-note-field span { color: var(--ink); font-weight: 800; }.review-note-field textarea { width: 100%; min-height: 94px; resize: vertical; }.review-actions { justify-content: flex-end; flex-wrap: wrap; }.compact-error { margin: 0; padding: 9px 11px; }.success-box { padding: 11px 13px; border: 1px solid rgba(34, 197, 94, .35); border-radius: 10px; background: rgba(34, 197, 94, .08); color: #177443; }.review-loading { display: flex; align-items: center; justify-content: center; gap: 9px; min-height: 180px; color: var(--muted); }.spin { animation: spin .8s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
.review-page-actions { display: flex; align-items: center; gap: 10px; color: var(--muted); font-size: 13px; }
.review-card.busy { opacity: .82; }
.review-record-link { display: inline-flex; align-items: center; gap: 5px; width: max-content; margin-top: 9px; color: var(--primary-dark); font-size: 13px; font-weight: 800; text-decoration: none; }
.review-record-link:hover { text-decoration: underline; }
.review-evidence-list li { display: block; padding: 0; overflow: hidden; }
.review-evidence-button { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: center; gap: 8px; width: 100%; min-height: 44px; padding: 9px 10px; border: 0; background: #fff; color: var(--ink); text-align: left; cursor: pointer; }
.review-evidence-button:hover:not(:disabled) { background: var(--primary-soft); }
.review-evidence-button:focus-visible { outline: 2px solid var(--primary); outline-offset: -2px; }
.review-evidence-button:disabled { cursor: not-allowed; opacity: .64; }
.review-note-count { justify-self: end; color: var(--muted); font-size: 12px; }
.evidence-preview-backdrop { position: fixed; z-index: var(--ds-z-modal, 1500); inset: 0; display: grid; place-items: center; padding: 16px; background: rgba(19, 39, 38, .55); }
.evidence-preview-dialog { display: grid; grid-template-rows: auto minmax(0, 1fr); width: min(920px, 100%); max-height: calc(100dvh - 32px); overflow: hidden; border: 1px solid var(--line); border-radius: 12px; background: #fff; box-shadow: 0 24px 70px rgba(20, 45, 43, .24); }
.evidence-preview-dialog > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; border-bottom: 1px solid var(--line); }
.evidence-preview-dialog > header div { display: grid; gap: 2px; min-width: 0; }
.evidence-preview-dialog > header b, .evidence-preview-dialog > header span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.evidence-preview-dialog > header span { color: var(--muted); font-size: 12px; }
.evidence-preview-dialog > img { display: block; max-width: 100%; max-height: calc(100dvh - 110px); margin: auto; object-fit: contain; }
.evidence-preview-dialog > iframe { width: min(900px, 100vw); height: min(78vh, 850px); border: 0; }
.evidence-preview-fallback { display: grid; place-items: center; align-content: center; gap: 12px; min-height: 260px; padding: 24px; color: var(--muted); text-align: center; }
.evidence-preview-fallback p { max-width: 480px; margin: 0; }
.evidence-preview-fallback a { color: var(--primary-dark); font-weight: 800; }
@media (max-width: 640px) {
  .review-list { grid-template-columns: 1fr; }
  .review-meta { grid-template-columns: 1fr; }
  .review-page-actions { width: 100%; justify-content: space-between; }
  .review-actions { display: grid; grid-template-columns: 1fr 1fr; }
  .review-actions :deep(button) { width: 100%; min-height: 44px; }
  .review-evidence-button { grid-template-columns: auto minmax(0, 1fr) auto; }
  .review-evidence-button small { display: none; }
  .evidence-preview-backdrop { padding: 8px; }
  .evidence-preview-dialog { max-height: calc(100dvh - 16px); }
}
</style>
