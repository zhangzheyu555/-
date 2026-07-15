<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { CheckCircle2, ClipboardCheck, FileImage, LoaderCircle, RefreshCw, RotateCcw, Send, XCircle } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import UiButton from '../components/ui/UiButton.vue'
import {
  getInspectionRectificationReviewQueue,
  isInspectionRectificationServiceUnavailable,
  reviewInspectionRectification,
  type InspectionRectificationTask,
} from '../api/inspectionRectification'

const tasks = ref<InspectionRectificationTask[]>([])
const reviewNotes = reactive<Record<string, string>>({})
const taskErrors = reactive<Record<string, string>>({})
const loading = ref(false)
const submittingId = ref('')
const error = ref('')
const actionMessage = ref('')

onMounted(() => {
  void loadQueue()
})

async function loadQueue() {
  loading.value = true
  error.value = ''
  actionMessage.value = ''
  try {
    tasks.value = await getInspectionRectificationReviewQueue()
  } catch (loadError) {
    tasks.value = []
    error.value = unavailableMessage(loadError, '整改复核队列加载失败，请稍后刷新。')
  } finally {
    loading.value = false
  }
}

async function review(task: InspectionRectificationTask, decision: 'APPROVED' | 'REJECTED') {
  const note = String(reviewNotes[task.recordId] || '').trim()
  if (!note) {
    taskErrors[task.recordId] = decision === 'APPROVED' ? '请填写复核通过说明。' : '请填写驳回原因，便于店长继续整改。'
    return
  }
  submittingId.value = task.recordId
  taskErrors[task.recordId] = ''
  actionMessage.value = ''
  try {
    await reviewInspectionRectification(task.recordId, { decision, note })
    tasks.value = tasks.value.filter((item) => item.recordId !== task.recordId)
    delete reviewNotes[task.recordId]
    actionMessage.value = decision === 'APPROVED'
      ? '整改已复核通过，处理记录已写入操作日志。'
      : '整改已驳回，店长将看到驳回原因并可重新提交。'
  } catch (reviewError) {
    taskErrors[task.recordId] = unavailableMessage(reviewError, '复核提交失败，请稍后重试。')
  } finally {
    submittingId.value = ''
  }
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
    return '整改复核服务暂未部署或当前候选版本不匹配，无法进行复核。请刷新到已部署整改服务的预发布候选后重试。'
  }
  return reason instanceof Error && reason.message ? reason.message : fallback
}
</script>

<template>
  <section class="page-panel review-page">
    <PageHeader title="整改复核" subtitle="核对店长提交的整改说明与现场证据；通过或驳回都会保留业务备注和操作日志。">
      <template #actions>
        <UiButton variant="secondary" :loading="loading" @click="loadQueue">
          <template #icon><RefreshCw :size="16" /></template>
          刷新队列
        </UiButton>
      </template>
    </PageHeader>

    <div v-if="error" class="error-box" role="alert">{{ error }}</div>
    <div v-if="actionMessage" class="success-box" role="status">{{ actionMessage }}</div>

    <div v-if="loading" class="review-loading" aria-live="polite">
      <LoaderCircle class="spin" :size="22" /> 正在读取待复核整改…
    </div>
    <div v-else-if="!tasks.length && !error" class="empty-state">当前没有待复核的巡检整改。</div>

    <div v-else class="review-list">
      <article v-for="task in tasks" :key="task.recordId" class="review-card">
        <header class="review-card-head">
          <div>
            <span>待复核整改</span>
            <h2>{{ task.storeName || task.storeId }}</h2>
          </div>
          <span class="review-status">待运营复核</span>
        </header>

        <dl class="review-meta">
          <div><dt>巡检日期</dt><dd>{{ task.inspectionDate || '—' }}</dd></div>
          <div><dt>最近更新时间</dt><dd>{{ formatTime(task.updatedAt) }}</dd></div>
          <div><dt>现场证据</dt><dd>{{ evidenceText(task) }}</dd></div>
        </dl>

        <section class="review-section issue-section">
          <div><ClipboardCheck :size="18" /><b>巡检问题</b></div>
          <p>请回到巡检记录核对问题条款与整改要求，避免仅凭备注作出复核结论。</p>
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
            <FileImage :size="16" />
            <span>整改现场证据 #{{ attachmentId }}</span>
            <small>已关联受认证附件</small>
          </li>
        </ul>
        <div v-else class="evidence-warning"><RotateCcw :size="17" /> 未发现现场证据，不能据此判断整改已完成。</div>

        <label class="review-note-field" :for="`review-note-${task.recordId}`">
          <span>复核备注</span>
          <textarea
            :id="`review-note-${task.recordId}`"
            v-model="reviewNotes[task.recordId]"
            rows="3"
            maxlength="1000"
            :disabled="submittingId === task.recordId"
            placeholder="通过时说明核对结果；驳回时说明仍需整改的具体事项"
          />
        </label>
        <div v-if="taskErrors[task.recordId]" class="error-box compact-error" role="alert">{{ taskErrors[task.recordId] }}</div>

        <footer class="review-actions">
          <UiButton variant="danger" :loading="submittingId === task.recordId" @click="review(task, 'REJECTED')">
            <template #icon><XCircle :size="16" /></template>
            驳回整改
          </UiButton>
          <UiButton variant="primary" :loading="submittingId === task.recordId" @click="review(task, 'APPROVED')">
            <template #icon><CheckCircle2 :size="16" /></template>
            通过复核
          </UiButton>
        </footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.review-page, .review-list { display: grid; gap: var(--space-4, 16px); }.review-list { grid-template-columns: repeat(auto-fit, minmax(min(100%, 470px), 1fr)); align-items: start; }.review-card { display: grid; gap: 14px; padding: clamp(16px, 3vw, 22px); border: 1px solid var(--line); border-radius: 12px; background: #fff; }.review-card-head, .review-section > div, .review-actions { display: flex; align-items: center; gap: 9px; }.review-card-head { justify-content: space-between; align-items: flex-start; }.review-card-head span { color: var(--muted); font-size: 12px; font-weight: 800; }.review-card-head h2 { margin: 4px 0 0; font-size: 20px; }.review-status { padding: 5px 8px; border-radius: 999px; background: var(--ds-warning-soft, #fff7e7); color: var(--warn); white-space: nowrap; }.review-meta { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; margin: 0; overflow: hidden; border: 1px solid var(--line); border-radius: 10px; background: var(--line); }.review-meta div { padding: 10px; background: #fafbfc; }.review-meta dt { color: var(--muted); font-size: 12px; }.review-meta dd { margin: 4px 0 0; color: var(--ink); font-size: 13px; font-weight: 800; overflow-wrap: anywhere; }.review-section { padding: 11px 12px; border-left: 4px solid var(--primary); background: var(--primary-soft); }.issue-section { border-left-color: var(--warn); background: #fffbf2; }.manager-note-section { border-left-color: #64748b; background: #f6f8fa; }.review-section p { margin: 8px 0 0; color: var(--ink); line-height: 1.65; white-space: pre-wrap; }.review-evidence-list { display: grid; gap: 6px; margin: 0; padding: 0; list-style: none; }.review-evidence-list li { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 9px 10px; border: 1px solid var(--line); border-radius: 9px; font-size: 13px; }.review-evidence-list span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.review-evidence-list small { color: var(--muted); font-size: 11px; }.evidence-warning { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border: 1px solid rgba(217, 119, 6, .3); border-radius: 9px; background: #fffbf2; color: #9a5b00; font-size: 13px; }.review-note-field { display: grid; gap: 7px; }.review-note-field span { color: var(--ink); font-weight: 800; }.review-note-field textarea { width: 100%; min-height: 94px; resize: vertical; }.review-actions { justify-content: flex-end; flex-wrap: wrap; }.compact-error { margin: 0; padding: 9px 11px; }.success-box { padding: 11px 13px; border: 1px solid rgba(34, 197, 94, .35); border-radius: 10px; background: rgba(34, 197, 94, .08); color: #177443; }.review-loading { display: flex; align-items: center; justify-content: center; gap: 9px; min-height: 180px; color: var(--muted); }.spin { animation: spin .8s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 640px) { .review-list { grid-template-columns: 1fr; }.review-meta { grid-template-columns: 1fr; }.review-actions :deep(button) { flex: 1 1 150px; } }
</style>
