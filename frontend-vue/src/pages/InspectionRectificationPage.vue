<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Camera, CheckCircle2, CircleAlert, FileImage, LoaderCircle, RefreshCw, Send, Trash2 } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import UiButton from '../components/ui/UiButton.vue'
import {
  getMyInspectionRectifications,
  isInspectionRectificationServiceUnavailable,
  submitInspectionRectification,
  uploadInspectionRectificationEvidence,
  type InspectionRectificationEvidenceUpload,
  type InspectionRectificationTask,
} from '../api/inspectionRectification'

const tasks = ref<InspectionRectificationTask[]>([])
const selectedRecordId = ref('')
const note = ref('')
const files = ref<File[]>([])
const uploadedEvidence = ref<InspectionRectificationEvidenceUpload[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const loading = ref(false)
const uploading = ref(false)
const submitting = ref(false)
const error = ref('')
const actionMessage = ref('')

const selectedTask = computed(() => tasks.value.find((item) => item.recordId === selectedRecordId.value) || null)
const pendingTasks = computed(() => tasks.value.filter((item) => isActionableStatus(item.status)))

onMounted(() => {
  void loadTasks()
})

async function loadTasks() {
  loading.value = true
  error.value = ''
  actionMessage.value = ''
  try {
    tasks.value = await getMyInspectionRectifications()
    if (!selectedRecordId.value || !tasks.value.some((item) => item.recordId === selectedRecordId.value)) {
      selectTask(pendingTasks.value[0] || tasks.value[0] || null)
    }
  } catch (loadError) {
    tasks.value = []
    error.value = unavailableMessage(loadError, '整改待办加载失败，请稍后刷新。')
  } finally {
    loading.value = false
  }
}

function selectTask(task: InspectionRectificationTask | null) {
  selectedRecordId.value = task?.recordId || ''
  note.value = ''
  files.value = []
  uploadedEvidence.value = []
  error.value = ''
  actionMessage.value = ''
}

function chooseFiles() {
  if (submitting.value || uploading.value) return
  fileInput.value?.click()
}

function handleFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = Array.from(input.files || [])
  input.value = ''
  if (!selected.length) return
  if (uploadedEvidence.value.length) {
    error.value = '已有已上传但未提交的现场证据，请先提交整改或刷新后重新办理。'
    return
  }
  const invalid = selected.find((file) => !isAllowedEvidence(file))
  if (invalid) {
    error.value = '现场证据仅支持 JPG、PNG、WEBP 图片或 PDF 文件。'
    return
  }
  const oversized = selected.find((file) => file.size > 5 * 1024 * 1024)
  if (oversized) {
    error.value = `${oversized.name} 超过 5MB，请压缩后再上传。`
    return
  }
  const next = [...files.value, ...selected]
  if (next.length > 10) {
    error.value = '一次最多选择 10 份现场证据。'
    return
  }
  files.value = next
  error.value = ''
}

function removeFile(index: number) {
  if (uploading.value || submitting.value || uploadedEvidence.value.length) return
  files.value = files.value.filter((_, current) => current !== index)
}

async function submit() {
  const task = selectedTask.value
  if (!task || !isActionableStatus(task.status)) return
  if (!note.value.trim()) {
    error.value = '请填写整改说明后再提交。'
    return
  }
  if (!files.value.length && !uploadedEvidence.value.length) {
    error.value = '请至少上传一份现场证据后再提交整改。'
    return
  }

  submitting.value = true
  error.value = ''
  actionMessage.value = ''
  try {
    const evidence = await ensureEvidenceUploaded(task)
    const attachmentIds = evidence
      .map((item) => Number(item.attachmentId))
      .filter((item) => Number.isInteger(item) && item > 0)
    if (!attachmentIds.length) throw new Error('现场证据上传未返回有效附件编号，请重新选择文件后再试。')

    const updated = await submitInspectionRectification(task.recordId, {
      note: note.value.trim(),
      attachmentIds,
    })
    replaceTask(updated)
    note.value = ''
    files.value = []
    uploadedEvidence.value = []
    actionMessage.value = '整改已提交，等待运营复核。'
  } catch (submitError) {
    const uploadedHint = uploadedEvidence.value.length
      ? '现场证据已上传，但整改尚未提交；请保留当前页面后重新提交。'
      : ''
    error.value = [unavailableMessage(submitError, '整改提交失败，请稍后重试。'), uploadedHint]
      .filter(Boolean)
      .join(' ')
  } finally {
    submitting.value = false
  }
}

async function ensureEvidenceUploaded(task: InspectionRectificationTask) {
  if (uploadedEvidence.value.length) return uploadedEvidence.value
  uploading.value = true
  try {
    const uploaded: InspectionRectificationEvidenceUpload[] = []
    for (const file of files.value) {
      // 复用已有受认证、门店范围校验的附件入口；页面不会构造磁盘路径或匿名链接。
      uploaded.push(await uploadInspectionRectificationEvidence(task.recordId, file))
    }
    uploadedEvidence.value = uploaded
    return uploaded
  } finally {
    uploading.value = false
  }
}

function replaceTask(updated: InspectionRectificationTask) {
  const index = tasks.value.findIndex((item) => item.recordId === updated.recordId)
  if (index >= 0) tasks.value.splice(index, 1, updated)
  else tasks.value.unshift(updated)
}

function taskStatusLabel(task: InspectionRectificationTask) {
  if (task.statusLabel) return task.statusLabel
  const labels: Record<string, string> = {
    PENDING_SUBMISSION: '待整改',
    PENDING_REVIEW: '待运营复核',
    APPROVED: '复核通过',
    REJECTED: '已驳回，需重新整改',
  }
  return labels[String(task.status || '').toUpperCase()] || '处理中'
}

function statusTone(status: string) {
  const value = String(status || '').toUpperCase()
  if (value === 'APPROVED') return 'ok'
  if (value === 'REJECTED') return 'bad'
  if (value === 'PENDING_REVIEW') return 'warn'
  return 'info'
}

function isActionableStatus(status: string) {
  return ['PENDING_SUBMISSION', 'REJECTED'].includes(String(status || '').toUpperCase())
}

function isAllowedEvidence(file: File) {
  return ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'].includes(file.type.toLowerCase())
}

function evidenceLabel(attachmentIds: number[]) {
  if (!attachmentIds.length) return '尚未关联现场证据'
  return `已关联 ${attachmentIds.length} 份现场证据`
}

function formatTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

function formatSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function unavailableMessage(reason: unknown, fallback: string) {
  if (isInspectionRectificationServiceUnavailable(reason)) {
    return '整改服务暂未部署或当前候选版本不匹配，无法办理整改。请刷新到已部署整改服务的预发布候选后重试。'
  }
  return reason instanceof Error && reason.message ? reason.message : fallback
}
</script>

<template>
  <section class="page-panel rectification-page">
    <PageHeader title="巡检整改" subtitle="上传本店现场证据并提交整改说明，运营复核前不会改写历史巡检得分。">
      <template #actions>
        <UiButton variant="secondary" :loading="loading" @click="loadTasks">
          <template #icon><RefreshCw :size="16" /></template>
          刷新待办
        </UiButton>
      </template>
    </PageHeader>

    <div v-if="error" class="error-box" role="alert">{{ error }}</div>
    <div v-if="actionMessage" class="success-box" role="status">{{ actionMessage }}</div>

    <div v-if="loading" class="rectification-loading" aria-live="polite">
      <LoaderCircle class="spin" :size="22" /> 正在读取本店整改待办…
    </div>

    <template v-else>
      <div v-if="!tasks.length && !error" class="empty-state">
        当前没有需要整改的巡检事项。
      </div>

      <div v-else class="rectification-layout">
        <aside class="rectification-list" aria-label="本店整改待办">
          <div class="list-heading">
            <b>整改待办</b>
            <span>{{ pendingTasks.length }} 项待办</span>
          </div>
          <button
            v-for="task in tasks"
            :key="task.recordId"
            class="rectification-task"
            :class="{ active: selectedRecordId === task.recordId }"
            type="button"
            @click="selectTask(task)"
          >
            <span class="status-chip" :class="statusTone(task.status)">{{ taskStatusLabel(task) }}</span>
            <b>{{ task.storeName || task.storeId }}</b>
            <small>{{ task.requirement || '巡检问题待处理' }}</small>
            <time>巡检日期：{{ task.inspectionDate || '—' }}</time>
          </button>
        </aside>

        <section v-if="selectedTask" class="rectification-detail" aria-live="polite">
          <div class="detail-heading">
            <div>
              <span>本店整改事项</span>
              <h2>{{ selectedTask.storeName || selectedTask.storeId }}</h2>
            </div>
            <span class="status-chip" :class="statusTone(selectedTask.status)">{{ taskStatusLabel(selectedTask) }}</span>
          </div>

          <dl class="task-meta">
            <div><dt>巡检日期</dt><dd>{{ selectedTask.inspectionDate || '—' }}</dd></div>
            <div><dt>整改期限</dt><dd>未设置整改期限</dd></div>
            <div><dt>当前证据</dt><dd>{{ evidenceLabel(selectedTask.evidenceAttachmentIds) }}</dd></div>
          </dl>

          <section class="problem-card">
            <div><CircleAlert :size="18" /><b>发现问题</b></div>
            <p>本次巡检存在需整改事项，请根据整改要求完成现场处理并提交证据。</p>
          </section>
          <section class="problem-card requirement-card">
            <div><CheckCircle2 :size="18" /><b>整改要求</b></div>
            <p>{{ selectedTask.requirement || '请完成现场整改，上传清晰证据并说明处理结果。' }}</p>
          </section>

          <template v-if="isActionableStatus(selectedTask.status)">
            <section class="rectification-form">
              <label for="rectification-note">整改说明</label>
              <textarea
                id="rectification-note"
                v-model="note"
                maxlength="1000"
                rows="4"
                :disabled="submitting"
                placeholder="说明已采取的整改措施、完成时间和现场核对结果"
              />

              <div class="evidence-toolbar">
                <div>
                  <b>现场证据</b>
                  <span>支持 JPG、PNG、WEBP 或 PDF；每份不超过 5MB。</span>
                </div>
                <UiButton variant="secondary" :disabled="submitting || uploading" @click="chooseFiles">
                  <template #icon><Camera :size="16" /></template>
                  {{ uploading ? '正在上传…' : '选择证据' }}
                </UiButton>
                <input
                  ref="fileInput"
                  class="visually-hidden"
                  type="file"
                  accept="image/jpeg,image/png,image/webp,application/pdf"
                  capture="environment"
                  multiple
                  @change="handleFiles"
                >
              </div>

              <ul v-if="files.length" class="evidence-file-list" aria-label="待上传现场证据">
                <li v-for="(file, index) in files" :key="`${file.name}-${file.size}-${index}`">
                  <FileImage :size="16" />
                  <span>{{ file.name }}</span>
                  <small>{{ formatSize(file.size) }}</small>
                  <button
                    v-if="!uploadedEvidence.length"
                    type="button"
                    :disabled="submitting || uploading"
                    :aria-label="`移除 ${file.name}`"
                    @click="removeFile(index)"
                  ><Trash2 :size="16" /></button>
                </li>
              </ul>
              <p v-if="uploadedEvidence.length" class="upload-pending-note">
                已上传 {{ uploadedEvidence.length }} 份现场证据，正在等待提交整改。
              </p>

              <div class="form-actions">
                <UiButton variant="primary" :loading="submitting" :disabled="uploading" @click="submit">
                  <template #icon><Send :size="16" /></template>
                  提交整改复核
                </UiButton>
              </div>
            </section>
          </template>

          <section v-else class="rectification-result" :class="statusTone(selectedTask.status)">
            <b>{{ taskStatusLabel(selectedTask) }}</b>
            <p v-if="selectedTask.reviewNote">运营备注：{{ selectedTask.reviewNote }}</p>
            <p v-else-if="selectedTask.managerNote">已提交整改说明，等待运营处理。</p>
            <small>更新时间：{{ formatTime(selectedTask.updatedAt) }}</small>
          </section>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.rectification-page { display: grid; gap: var(--space-4, 16px); }
.rectification-layout { display: grid; grid-template-columns: minmax(230px, 0.78fr) minmax(0, 1.72fr); gap: var(--space-4, 16px); align-items: start; }
.rectification-list, .rectification-detail { border: 1px solid var(--line); border-radius: 12px; background: #fff; }
.rectification-list { display: grid; gap: 8px; padding: 10px; }
.list-heading, .detail-heading, .evidence-toolbar { display: flex; align-items: center; gap: 10px; }
.list-heading { justify-content: space-between; padding: 4px 4px 8px; color: var(--muted); font-size: 12px; }
.list-heading b { color: var(--ink); font-size: 15px; }
.rectification-task { display: grid; gap: 6px; width: 100%; padding: 12px; border: 1px solid transparent; border-radius: 10px; background: #fafbfc; color: var(--ink); text-align: left; cursor: pointer; }
.rectification-task:hover, .rectification-task.active { border-color: rgba(39, 107, 101, 0.38); background: var(--primary-soft); }
.rectification-task b, .rectification-task small, .rectification-task time { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rectification-task small, .rectification-task time { color: var(--muted); font-size: 12px; }
.status-chip { width: max-content; max-width: 100%; padding: 4px 8px; border-radius: 999px; background: #eef2f5; color: var(--muted); font-size: 12px; font-weight: 800; }
.status-chip.ok { background: rgba(34, 197, 94, .12); color: var(--good); }.status-chip.bad { background: rgba(220, 38, 38, .1); color: var(--bad); }.status-chip.warn { background: var(--ds-warning-soft, #fff7e7); color: var(--warn); }.status-chip.info { background: var(--primary-soft); color: var(--primary-dark); }
.rectification-detail { display: grid; gap: 18px; padding: clamp(16px, 3vw, 24px); }
.detail-heading { justify-content: space-between; align-items: flex-start; }.detail-heading span { color: var(--muted); font-size: 12px; font-weight: 800; }.detail-heading h2 { margin: 4px 0 0; font-size: 21px; }
.task-meta { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; margin: 0; overflow: hidden; border: 1px solid var(--line); border-radius: 10px; background: var(--line); }.task-meta div { padding: 10px 12px; background: #fafbfc; }.task-meta dt { color: var(--muted); font-size: 12px; }.task-meta dd { margin: 4px 0 0; color: var(--ink); font-size: 14px; font-weight: 800; overflow-wrap: anywhere; }
.problem-card { padding: 13px 14px; border-left: 4px solid var(--warn); background: #fffbf2; }.problem-card > div { display: flex; align-items: center; gap: 8px; color: var(--ink); }.problem-card p { margin: 8px 0 0; color: var(--ink); line-height: 1.65; white-space: pre-wrap; }.requirement-card { border-left-color: var(--primary); background: var(--primary-soft); }
.rectification-form { display: grid; gap: 10px; padding-top: 18px; border-top: 1px solid var(--line); }.rectification-form > label { color: var(--ink); font-weight: 800; }.rectification-form textarea { width: 100%; min-height: 110px; resize: vertical; }.evidence-toolbar { justify-content: space-between; flex-wrap: wrap; padding: 12px; border: 1px solid var(--line); border-radius: 10px; background: #fafbfc; }.evidence-toolbar > div { display: grid; gap: 3px; }.evidence-toolbar span, .upload-pending-note { color: var(--muted); font-size: 12px; }.evidence-file-list { display: grid; gap: 6px; margin: 0; padding: 0; list-style: none; }.evidence-file-list li { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: center; gap: 8px; padding: 9px 10px; border: 1px solid var(--line); border-radius: 9px; font-size: 13px; }.evidence-file-list span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.evidence-file-list small { color: var(--muted); }.evidence-file-list button { display: inline-flex; align-items: center; justify-content: center; width: 36px; height: 36px; padding: 0; border: 0; background: transparent; color: var(--bad); cursor: pointer; }.upload-pending-note { margin: 0; }.form-actions { display: flex; justify-content: flex-end; gap: 10px; }.rectification-result { padding: 14px; border-radius: 10px; background: #f5f7f8; }.rectification-result.ok { background: rgba(34, 197, 94, .1); }.rectification-result.bad { background: rgba(220, 38, 38, .08); }.rectification-result.warn { background: var(--ds-warning-soft, #fff7e7); }.rectification-result p { margin: 8px 0; line-height: 1.6; white-space: pre-wrap; }.rectification-result small { color: var(--muted); }.rectification-loading { display: flex; align-items: center; justify-content: center; gap: 9px; min-height: 180px; color: var(--muted); }.success-box { padding: 11px 13px; border: 1px solid rgba(34, 197, 94, .35); border-radius: 10px; background: rgba(34, 197, 94, .08); color: #177443; }.visually-hidden { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }.spin { animation: spin .8s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 780px) { .rectification-layout { grid-template-columns: 1fr; }.rectification-list { grid-auto-flow: column; grid-auto-columns: minmax(220px, 78vw); overflow-x: auto; padding-bottom: 12px; }.list-heading { grid-column: 1 / -1; grid-row: 1; }.rectification-task { grid-row: 2; }.task-meta { grid-template-columns: 1fr; }.form-actions :deep(button) { width: 100%; } }
</style>
