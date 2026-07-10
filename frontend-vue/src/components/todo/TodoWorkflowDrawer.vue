<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Download, ExternalLink, FileText, History, LoaderCircle, Paperclip, X } from 'lucide-vue-next'
import {
  downloadBusinessTodoAttachment,
  getBusinessTodo,
  transitionBusinessTodo,
  type BusinessTodo,
  type BusinessTodoAttachment,
  type BusinessTodoTransitionPayload,
} from '../../api/todos'
import { useAuthStore } from '../../stores/auth'
import StatusBadge from '../common/StatusBadge.vue'

const props = defineProps<{
  todoId: string
}>()

const emit = defineEmits<{
  close: []
  updated: [todo: BusinessTodo]
  openSource: [route: string]
}>()

type TransitionStatus = BusinessTodoTransitionPayload['status']

interface WorkflowAction {
  status: TransitionStatus
  label: string
  tone: 'primary' | 'danger'
}

const auth = useAuthStore()
const todo = ref<BusinessTodo | null>(null)
const loading = ref(true)
const submitting = ref(false)
const error = ref('')
const note = ref('')
const files = ref<File[]>([])
const fileInput = ref<HTMLInputElement | null>(null)

const isGlobalRole = computed(() => ['ADMIN', 'BOSS', 'OWNER'].includes(auth.role))
const isAssignee = computed(() => Boolean(todo.value?.assigneeRole && todo.value.assigneeRole === auth.roleLabel))
const isReviewer = computed(() => Boolean(todo.value?.reviewRole && todo.value.reviewRole === auth.roleLabel))

const availableActions = computed<WorkflowAction[]>(() => {
  const current = todo.value
  if (!current?.canTransition || ['COMPLETED', 'REJECTED'].includes(current.status)) return []
  const owner = isGlobalRole.value || isAssignee.value
  const reviewer = isGlobalRole.value || isReviewer.value
  if (current.status === 'PENDING') {
    return [
      ...(owner ? [{ status: 'IN_PROGRESS' as const, label: '开始处理', tone: 'primary' as const }] : []),
      ...(owner || reviewer ? [{ status: 'REJECTED' as const, label: '驳回', tone: 'danger' as const }] : []),
    ]
  }
  if (current.status === 'IN_PROGRESS') {
    return [
      ...(owner ? [{ status: 'PENDING_REVIEW' as const, label: '提交复核', tone: 'primary' as const }] : []),
      ...(owner || reviewer ? [{ status: 'REJECTED' as const, label: '驳回', tone: 'danger' as const }] : []),
    ]
  }
  return reviewer
    ? [
        { status: 'COMPLETED', label: '复核完成', tone: 'primary' },
        { status: 'REJECTED', label: '驳回', tone: 'danger' },
      ]
    : []
})

const statusTone = computed(() => {
  if (todo.value?.status === 'COMPLETED') return 'ok'
  if (todo.value?.status === 'REJECTED') return 'bad'
  if (todo.value?.status === 'PENDING_REVIEW') return 'warn'
  return 'info'
})

onMounted(() => {
  document.addEventListener('keydown', handleEscape)
  void loadTodo()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleEscape)
})

function handleEscape(event: KeyboardEvent) {
  if (event.key === 'Escape' && !submitting.value) emit('close')
}

async function loadTodo() {
  loading.value = true
  error.value = ''
  try {
    todo.value = await getBusinessTodo(props.todoId)
  } catch (loadError) {
    console.error('[Todo] workflow detail load failed', loadError)
    error.value = loadError instanceof Error ? loadError.message : '待办详情加载失败'
  } finally {
    loading.value = false
  }
}

function chooseFiles() {
  fileInput.value?.click()
}

function handleFiles(event: Event) {
  error.value = ''
  const input = event.target as HTMLInputElement
  const selected = Array.from(input.files || [])
  const next = [...files.value, ...selected]
  if (next.length > 10) {
    error.value = '每次最多上传 10 个附件'
    input.value = ''
    return
  }
  const invalid = selected.find((file) => file.size > 5 * 1024 * 1024)
  if (invalid) {
    error.value = `${invalid.name} 超过 5MB`
    input.value = ''
    return
  }
  files.value = next
  input.value = ''
}

function removeFile(index: number) {
  files.value = files.value.filter((_, current) => current !== index)
}

async function submit(status: TransitionStatus) {
  if (!note.value.trim()) {
    error.value = '请填写处理备注'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const attachments = await Promise.all(files.value.map(async (file) => ({
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      dataBase64: await readFile(file),
    })))
    const updated = await transitionBusinessTodo(props.todoId, {
      status,
      note: note.value.trim(),
      attachments,
    })
    todo.value = updated
    note.value = ''
    files.value = []
    emit('updated', updated)
  } catch (submitError) {
    console.error('[Todo] workflow transition failed', submitError)
    error.value = submitError instanceof Error ? submitError.message : '待办处理失败，请重试'
  } finally {
    submitting.value = false
  }
}

function readFile(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error(`${file.name} 读取失败`))
    reader.readAsDataURL(file)
  })
}

async function downloadAttachment(attachment: BusinessTodoAttachment) {
  error.value = ''
  try {
    await downloadBusinessTodoAttachment(props.todoId, attachment)
  } catch (downloadError) {
    console.error('[Todo] attachment download failed', downloadError)
    error.value = downloadError instanceof Error ? downloadError.message : '附件下载失败'
  }
}

function formatTime(value?: string) {
  if (!value) return ''
  return value.replace('T', ' ').slice(0, 16)
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <Teleport to="body">
    <div class="todo-drawer-mask" @click.self="emit('close')">
      <aside class="todo-drawer" role="dialog" aria-modal="true" aria-labelledby="todo-workflow-title">
        <header class="todo-drawer-head">
          <div>
            <span>待办处理</span>
            <h2 id="todo-workflow-title">{{ todo?.title || '待办详情' }}</h2>
          </div>
          <button class="drawer-icon-button" type="button" title="关闭" :disabled="submitting" @click="emit('close')">
            <X :size="20" />
          </button>
        </header>

        <div v-if="loading" class="drawer-loading">
          <LoaderCircle class="spin" :size="24" />
          正在加载...
        </div>

        <div v-else-if="todo" class="todo-drawer-body">
          <div class="todo-status-row">
            <StatusBadge :label="todo.statusLabel" :tone="statusTone" />
            <span>{{ todo.brandName || '' }}{{ todo.brandName && todo.storeName ? ' · ' : '' }}{{ todo.storeName || todo.storeId || '全部门店' }}</span>
            <span v-if="todo.month">{{ todo.month }}</span>
          </div>

          <p class="todo-summary">{{ todo.summary }}</p>

          <dl class="todo-role-grid">
            <div>
              <dt>处理岗位</dt>
              <dd>{{ todo.assigneeRole || '待分配' }}</dd>
            </div>
            <div>
              <dt>复核岗位</dt>
              <dd>{{ todo.reviewRole || '无需复核' }}</dd>
            </div>
          </dl>

          <button class="ghost-button source-button" type="button" @click="emit('openSource', todo.targetRoute)">
            <ExternalLink :size="16" />
            去业务页面
          </button>

          <section v-if="availableActions.length" class="workflow-form">
            <label for="todo-note">处理备注</label>
            <textarea
              id="todo-note"
              v-model="note"
              rows="3"
              maxlength="1000"
              placeholder="填写处理情况、复核结论或驳回原因"
              :disabled="submitting"
            />

            <div class="attachment-toolbar">
              <button class="ghost-button" type="button" :disabled="submitting" @click="chooseFiles">
                <Paperclip :size="16" />
                添加附件
              </button>
              <span>图片或 PDF，单个不超过 5MB</span>
              <input
                ref="fileInput"
                class="file-input"
                type="file"
                accept="image/jpeg,image/png,image/webp,application/pdf"
                multiple
                @change="handleFiles"
              >
            </div>

            <ul v-if="files.length" class="selected-files">
              <li v-for="(file, index) in files" :key="`${file.name}-${file.size}-${index}`">
                <FileText :size="15" />
                <span>{{ file.name }}</span>
                <small>{{ formatSize(file.size) }}</small>
                <button type="button" title="移除附件" :disabled="submitting" @click="removeFile(index)">
                  <X :size="14" />
                </button>
              </li>
            </ul>

            <div v-if="error" class="error-box compact-error">{{ error }}</div>

            <div class="workflow-actions">
              <button
                v-for="action in availableActions"
                :key="action.status"
                :class="action.tone === 'danger' ? 'danger-button' : 'primary-button'"
                type="button"
                :disabled="submitting"
                @click="submit(action.status)"
              >
                <LoaderCircle v-if="submitting" class="spin" :size="16" />
                {{ action.label }}
              </button>
            </div>
          </section>

          <div v-else-if="error" class="error-box compact-error">{{ error }}</div>

          <section class="workflow-history">
            <div class="workflow-section-title">
              <History :size="17" />
              <h3>操作记录</h3>
            </div>
            <ol v-if="todo.actions.length">
              <li v-for="action in todo.actions" :key="action.id">
                <div class="history-head">
                  <b>{{ action.statusLabel }}</b>
                  <time>{{ formatTime(action.createdAt) }}</time>
                </div>
                <p>{{ action.note }}</p>
                <span>{{ action.actorName || '系统' }}</span>
                <div v-if="action.attachments.length" class="history-files">
                  <button
                    v-for="attachment in action.attachments"
                    :key="attachment.id"
                    type="button"
                    @click="downloadAttachment(attachment)"
                  >
                    <Download :size="14" />
                    {{ attachment.fileName }}
                  </button>
                </div>
              </li>
            </ol>
            <div v-else class="empty-state compact">暂无操作记录</div>
          </section>
        </div>

        <div v-else class="drawer-load-error">
          <div class="error-box">{{ error || '待办详情加载失败' }}</div>
          <button class="ghost-button" type="button" @click="loadTodo">重新加载</button>
        </div>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped>
.todo-drawer-mask {
  position: fixed;
  z-index: 1200;
  inset: 0;
  display: flex;
  justify-content: flex-end;
  background: rgba(20, 24, 32, 0.42);
}

.todo-drawer {
  display: flex;
  flex-direction: column;
  width: min(560px, 100%);
  height: 100%;
  background: #fff;
  box-shadow: -12px 0 34px rgba(20, 24, 32, 0.16);
}

.todo-drawer-head {
  display: flex;
  flex-shrink: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 22px 16px;
  border-bottom: 1px solid var(--line);
}

.todo-drawer-head span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.todo-drawer-head h2 {
  margin: 4px 0 0;
  font-size: 20px;
  line-height: 1.4;
}

.drawer-icon-button {
  display: grid;
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--muted);
  cursor: pointer;
  place-items: center;
}

.todo-drawer-body {
  min-height: 0;
  padding: 18px 22px 28px;
  overflow-y: auto;
}

.drawer-loading,
.drawer-load-error {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 24px;
  color: var(--muted);
}

.drawer-load-error {
  flex-direction: column;
}

.todo-status-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  color: var(--muted);
  font-size: 13px;
}

.todo-summary {
  margin: 14px 0;
  color: var(--ink);
  line-height: 1.7;
  white-space: pre-wrap;
}

.todo-role-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  margin: 0 0 14px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--line);
}

.todo-role-grid div {
  padding: 10px 12px;
  background: #fafbfc;
}

.todo-role-grid dt {
  color: var(--muted);
  font-size: 12px;
}

.todo-role-grid dd {
  margin: 3px 0 0;
  color: var(--ink);
  font-size: 14px;
  font-weight: 800;
}

.source-button {
  margin-bottom: 18px;
}

.workflow-form,
.workflow-history {
  padding-top: 18px;
  border-top: 1px solid var(--line);
}

.workflow-form label {
  display: block;
  margin-bottom: 8px;
  color: var(--ink);
  font-size: 14px;
  font-weight: 800;
}

.workflow-form textarea {
  width: 100%;
  min-height: 88px;
  resize: vertical;
}

.attachment-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-top: 10px;
}

.attachment-toolbar span {
  color: var(--muted);
  font-size: 12px;
}

.file-input {
  display: none;
}

.selected-files {
  display: grid;
  gap: 6px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.selected-files li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 13px;
}

.selected-files span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-files small {
  color: var(--muted);
}

.selected-files button {
  display: grid;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  place-items: center;
}

.compact-error {
  margin-top: 12px;
  padding: 9px 11px;
}

.workflow-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 14px;
}

.danger-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 38px;
  padding: 0 15px;
  border: 1px solid rgba(214, 69, 69, 0.32);
  border-radius: 7px;
  background: #fff;
  color: var(--bad);
  font-weight: 800;
  cursor: pointer;
}

.workflow-history {
  margin-top: 20px;
}

.workflow-section-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.workflow-section-title h3 {
  margin: 0;
  font-size: 16px;
}

.workflow-history ol {
  display: grid;
  gap: 0;
  margin: 14px 0 0;
  padding: 0 0 0 18px;
  border-left: 2px solid var(--line);
  list-style: none;
}

.workflow-history li {
  position: relative;
  padding: 0 0 18px 14px;
}

.workflow-history li::before {
  position: absolute;
  top: 5px;
  left: -23px;
  width: 8px;
  height: 8px;
  border: 3px solid #fff;
  border-radius: 50%;
  background: var(--primary);
  box-shadow: 0 0 0 1px var(--line);
  content: '';
}

.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.history-head b {
  font-size: 14px;
}

.history-head time,
.workflow-history li > span {
  color: var(--muted);
  font-size: 12px;
}

.workflow-history li p {
  margin: 5px 0;
  color: var(--ink);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.history-files {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.history-files button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--ink);
  font-size: 12px;
  cursor: pointer;
}

.spin {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 600px) {
  .todo-drawer {
    width: 100%;
  }

  .todo-drawer-head,
  .todo-drawer-body {
    padding-right: 16px;
    padding-left: 16px;
  }

  .todo-role-grid {
    grid-template-columns: 1fr;
  }

  .workflow-actions button {
    flex: 1 1 130px;
  }
}
</style>
