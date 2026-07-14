<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { FileText, Image, Paperclip, RefreshCw, Trash2, Upload, X } from 'lucide-vue-next'
import { submitExpenseSupplement, type ExpenseClaim } from '../../api/finance'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'
import UnsavedChangesDialog from '../ui/UnsavedChangesDialog.vue'

const MAX_FILES = 6
const MAX_FILE_SIZE = 10 * 1024 * 1024
const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'] as const
const ACCEPTED_EXTENSIONS: Record<string, string> = {
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.pdf': 'application/pdf',
}

interface SelectedFile {
  key: string
  file: File
  actualType: string
  previewUrl: string
  previewError: boolean
}

const props = defineProps<{ expense: ExpenseClaim }>()
const emit = defineEmits<{
  close: []
  submitted: [claim: ExpenseClaim]
}>()

const note = ref('')
const selectedFiles = ref<SelectedFile[]>([])
const selectionInput = ref<HTMLInputElement | null>(null)
const replacementInput = ref<HTMLInputElement | null>(null)
const noteInput = ref<HTMLTextAreaElement | null>(null)
const replacingIndex = ref<number | null>(null)
const selecting = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const error = ref('')
const preview = ref<{ url: string; name: string } | null>(null)
const discardConfirmOpen = ref(false)

const remainingCount = computed(() => MAX_FILES - selectedFiles.value.length)
const canSubmit = computed(() => {
  return !selecting.value
    && !uploading.value
    && Boolean(note.value.trim() || selectedFiles.value.length)
})
const dirty = computed(() => Boolean(note.value.trim() || selectedFiles.value.length))

onMounted(() => {
  void nextTick(() => noteInput.value?.focus())
})

onBeforeUnmount(releaseAllPreviews)

function close() {
  if (uploading.value) return
  if (dirty.value) {
    discardConfirmOpen.value = true
    return
  }
  releaseAllPreviews()
  emit('close')
}

function discardAndClose() {
  discardConfirmOpen.value = false
  releaseAllPreviews()
  emit('close')
}

function chooseFiles() {
  if (remainingCount.value <= 0 || uploading.value) return
  selectionInput.value?.click()
}

function replaceFile(index: number) {
  if (uploading.value) return
  replacingIndex.value = index
  replacementInput.value?.click()
}

async function onFilesSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  await addFiles(files)
}

async function onReplacementSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const index = replacingIndex.value
  replacingIndex.value = null
  if (!file || index === null) return

  selecting.value = true
  error.value = ''
  try {
    const validationError = await validateFile(file, index)
    if (validationError) {
      error.value = validationError
      return
    }
    const next = await toSelectedFile(file)
    releasePreview(selectedFiles.value[index])
    selectedFiles.value.splice(index, 1, next)
  } finally {
    selecting.value = false
  }
}

async function onDrop(event: DragEvent) {
  if (uploading.value) return
  await addFiles(Array.from(event.dataTransfer?.files || []))
}

async function addFiles(files: File[]) {
  if (!files.length) return
  selecting.value = true
  error.value = ''
  const errors: string[] = []
  const available = remainingCount.value
  if (files.length > available) {
    errors.push(`最多上传 ${MAX_FILES} 个文件，本次仅检查前 ${available} 个。`)
  }

  try {
    for (const file of files.slice(0, available)) {
      const validationError = await validateFile(file)
      if (validationError) {
        errors.push(validationError)
        continue
      }
      selectedFiles.value.push(await toSelectedFile(file))
    }
  } finally {
    selecting.value = false
  }

  if (errors.length) error.value = errors.join(' ')
}

async function validateFile(file: File, replacing?: number) {
  if (!file.size) return `${file.name} 是空文件，无法上传。`
  if (file.size > MAX_FILE_SIZE) return `${file.name} 超过 10MB。`

  const duplicate = selectedFiles.value.some((item, index) => {
    if (index === replacing) return false
    return item.file.name === file.name
      && item.file.size === file.size
      && item.file.lastModified === file.lastModified
  })
  if (duplicate) return `${file.name} 已在待上传列表中。`

  const actualType = await detectFileType(file)
  if (!ACCEPTED_TYPES.includes(actualType as typeof ACCEPTED_TYPES[number])) {
    return `${file.name} 不是有效的 JPG、PNG、WebP 或 PDF 文件。`
  }

  const extension = file.name.slice(file.name.lastIndexOf('.')).toLowerCase()
  const expectedType = ACCEPTED_EXTENSIONS[extension]
  if (!expectedType || expectedType !== actualType) {
    return `${file.name} 的扩展名与真实文件类型不一致。`
  }

  const reportedType = normalizeMime(file.type)
  if (reportedType && reportedType !== actualType) {
    return `${file.name} 的文件类型信息不一致，请重新选择原始文件。`
  }
  return ''
}

async function toSelectedFile(file: File): Promise<SelectedFile> {
  const actualType = await detectFileType(file)
  return {
    key: `${file.name}-${file.size}-${file.lastModified}-${crypto.randomUUID()}`,
    file,
    actualType,
    previewUrl: actualType.startsWith('image/') ? URL.createObjectURL(file) : '',
    previewError: false,
  }
}

async function detectFileType(file: File) {
  const bytes = new Uint8Array(await file.slice(0, 16).arrayBuffer())
  if (bytes.length >= 4 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg'
  if (bytes.length >= 8 && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((value, index) => bytes[index] === value)) return 'image/png'
  if (bytes.length >= 12 && ascii(bytes, 0, 4) === 'RIFF' && ascii(bytes, 8, 12) === 'WEBP') return 'image/webp'
  if (bytes.length >= 5 && ascii(bytes, 0, 5) === '%PDF-') return 'application/pdf'
  return ''
}

function ascii(bytes: Uint8Array, from: number, to: number) {
  return String.fromCharCode(...bytes.slice(from, to))
}

function normalizeMime(value: string) {
  const mime = String(value || '').toLowerCase()
  return mime === 'image/jpg' ? 'image/jpeg' : mime
}

function releasePreview(item?: SelectedFile) {
  if (!item?.previewUrl) return
  URL.revokeObjectURL(item.previewUrl)
  item.previewUrl = ''
}

function releaseAllPreviews() {
  preview.value = null
  for (const item of selectedFiles.value) releasePreview(item)
  selectedFiles.value = []
}

function openPreview(item: SelectedFile) {
  if (!item.previewUrl) return
  preview.value = { url: item.previewUrl, name: item.file.name }
}

function markPreviewFailed(item: SelectedFile) {
  releasePreview(item)
  item.previewError = true
  if (preview.value?.name === item.file.name) preview.value = null
}

function retryPreview(item: SelectedFile) {
  releasePreview(item)
  item.previewUrl = URL.createObjectURL(item.file)
  item.previewError = false
}

function removeFile(index: number) {
  const [removed] = selectedFiles.value.splice(index, 1)
  releasePreview(removed)
  error.value = ''
}

function fileSize(size: number) {
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(size / 1024))} KB`
}

async function submit() {
  if (!note.value.trim() && !selectedFiles.value.length) {
    error.value = '请填写补充说明或至少选择一个附件。'
    noteInput.value?.focus()
    return
  }

  uploading.value = true
  uploadProgress.value = 0
  error.value = ''
  try {
    const claim = await submitExpenseSupplement(
      props.expense.id,
      note.value,
      selectedFiles.value.map((item) => item.file),
      (percent) => { uploadProgress.value = percent },
    )
    uploadProgress.value = 100
    releaseAllPreviews()
    emit('submitted', claim)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '补充资料上传失败，请保留当前内容后重试。'
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="supplement-backdrop" @mousedown.self="close" @keydown.esc="close">
      <section class="supplement-dialog" role="dialog" aria-modal="true" aria-labelledby="supplement-title">
        <header class="supplement-head">
          <div>
            <h3 id="supplement-title">补充报销资料</h3>
            <p>{{ expense.storeName || expense.storeCode || expense.storeId }} · {{ expense.month }}</p>
          </div>
          <UiButton variant="ghost" icon-only type="button" aria-label="关闭补资料窗口" title="关闭" :disabled="uploading" @click="close">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>

        <form class="supplement-body" @submit.prevent="submit">
          <div v-if="error" class="error-box" role="alert">{{ error }}</div>

          <label class="note-field">
            <span>补充说明</span>
            <textarea
              ref="noteInput"
              v-model="note"
              rows="4"
              maxlength="2000"
              placeholder="说明补充的票据、付款记录或业务情况"
              :disabled="uploading"
            />
            <small>{{ note.length }}/2000</small>
          </label>

          <div class="attachment-heading">
            <div>
              <b>图片或文件</b>
              <span>JPG、PNG、WebP、PDF；最多 6 个，单个不超过 10MB</span>
            </div>
            <span>{{ selectedFiles.length }}/{{ MAX_FILES }}</span>
          </div>

          <input
            ref="selectionInput"
            class="visually-hidden-input"
            type="file"
            multiple
            accept=".jpg,.jpeg,.png,.webp,.pdf,image/jpeg,image/png,image/webp,application/pdf"
            @change="onFilesSelected"
          />
          <input
            ref="replacementInput"
            class="visually-hidden-input"
            type="file"
            accept=".jpg,.jpeg,.png,.webp,.pdf,image/jpeg,image/png,image/webp,application/pdf"
            @change="onReplacementSelected"
          />

          <button
            class="drop-zone"
            type="button"
            :disabled="uploading || selecting || remainingCount <= 0"
            @click="chooseFiles"
            @dragover.prevent
            @drop.prevent="onDrop"
          >
            <Upload :size="22" />
            <span>{{ selecting ? '正在检查文件…' : '选择文件或拖到这里' }}</span>
            <small v-if="remainingCount > 0">还可选择 {{ remainingCount }} 个</small>
            <small v-else>已达到 6 个文件上限</small>
          </button>

          <ul v-if="selectedFiles.length" class="selected-files" aria-label="待上传文件">
            <li v-for="(item, index) in selectedFiles" :key="item.key">
              <button
                v-if="item.actualType.startsWith('image/')"
                class="file-preview preview-button"
                type="button"
                :disabled="!item.previewUrl"
                :aria-label="`预览 ${item.file.name}`"
                @click="openPreview(item)"
              >
                <img
                  v-if="item.previewUrl"
                  :src="item.previewUrl"
                  :alt="`${item.file.name} 缩略图`"
                  @error="markPreviewFailed(item)"
                />
                <Image v-else :size="24" />
              </button>
              <div v-else class="file-preview">
                <FileText :size="24" />
              </div>
              <div class="file-details">
                <b :title="item.file.name">{{ item.file.name }}</b>
                <span>{{ fileSize(item.file.size) }}</span>
                <span v-if="item.previewError" class="file-preview-error">预览生成失败，可点击重试</span>
              </div>
              <div class="file-actions">
                <button v-if="item.previewError" type="button" :disabled="uploading" @click="retryPreview(item)">
                  <RefreshCw :size="14" />重试预览
                </button>
                <button type="button" :disabled="uploading" @click="replaceFile(index)">
                  <RefreshCw :size="14" />重新选择
                </button>
                <button type="button" :disabled="uploading" @click="removeFile(index)">
                  <Trash2 :size="14" />删除
                </button>
              </div>
            </li>
          </ul>

          <div v-if="uploading" class="upload-status" aria-live="polite">
            <div>
              <span>正在上传 {{ selectedFiles.length ? `${selectedFiles.length} 个文件` : '补充说明' }}</span>
              <b>{{ uploadProgress }}%</b>
            </div>
            <progress :value="uploadProgress" max="100">{{ uploadProgress }}%</progress>
          </div>

          <ModalFooter class="supplement-footer" sticky>
            <template #info><span><Paperclip :size="15" />提交后资料将保存到报销记录</span></template>
            <UiButton variant="secondary" type="button" :disabled="uploading" @click="close">取消</UiButton>
            <UiButton variant="primary" type="submit" :disabled="!canSubmit" :loading="uploading">提交补充资料</UiButton>
          </ModalFooter>
        </form>
      </section>
    </div>
  </Teleport>

  <Teleport to="body">
    <div v-if="preview" class="local-preview-backdrop" @click.self="preview = null" @keydown.esc="preview = null">
      <section class="local-preview-dialog" role="dialog" aria-modal="true" aria-label="待上传图片预览">
        <header>
          <b>{{ preview.name }}</b>
          <UiButton variant="ghost" icon-only type="button" aria-label="关闭图片预览" title="关闭" @click="preview = null">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>
        <img :src="preview.url" :alt="preview.name" />
      </section>
    </div>
  </Teleport>

  <UnsavedChangesDialog
    :open="discardConfirmOpen"
    title="放弃未提交的补充资料？"
    message="关闭后，当前填写的说明和已选择文件将不会保留。"
    @keep-editing="discardConfirmOpen = false"
    @discard="discardAndClose"
  />
</template>

<style scoped>
.supplement-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1500;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(20, 29, 29, .42);
}

.supplement-dialog {
  width: min(720px, calc(100vw - 32px));
  max-height: min(840px, calc(100vh - 48px));
  overflow: hidden;
  border: 0;
  border-radius: 10px;
  background: #fff;
}

.supplement-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--line);
}

.supplement-head h3,
.supplement-head p {
  margin: 0;
}

.supplement-head h3 {
  font-size: 18px;
}

.supplement-head p {
  margin-top: 4px;
  color: var(--muted);
  font-size: 13px;
}

.supplement-body {
  display: grid;
  gap: 16px;
  max-height: calc(100vh - 150px);
  overflow-y: auto;
  padding: 20px;
}

.note-field {
  position: relative;
  display: grid;
  gap: 7px;
  font-weight: 700;
}

.note-field textarea {
  width: 100%;
  resize: vertical;
}

.note-field small {
  position: absolute;
  right: 9px;
  bottom: 8px;
  padding-left: 8px;
  background: #fff;
  color: var(--muted);
  font-size: 11px;
  font-weight: 500;
}

.attachment-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.attachment-heading > div {
  display: grid;
  gap: 3px;
}

.attachment-heading span {
  color: var(--muted);
  font-size: 12px;
}

.attachment-heading > span {
  flex: none;
  color: var(--ink);
  font-weight: 700;
}

.visually-hidden-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.drop-zone {
  display: grid;
  min-height: 112px;
  place-items: center;
  align-content: center;
  gap: 5px;
  padding: 18px;
  border: 1px dashed var(--ds-line-strong);
  border-radius: 8px;
  background: var(--ds-surface-muted);
  color: var(--primary-dark);
}

.drop-zone:hover:not(:disabled),
.drop-zone:focus-visible {
  border-color: var(--primary);
  background: var(--primary-soft);
}

.drop-zone span {
  color: var(--ink);
  font-weight: 700;
}

.drop-zone small {
  color: var(--muted);
}

.selected-files {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.selected-files li {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 9px;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.file-preview {
  display: grid;
  width: 52px;
  height: 42px;
  overflow: hidden;
  place-items: center;
  border-radius: 6px;
  background: var(--ds-surface-muted);
  color: var(--muted);
}

.preview-button {
  padding: 0;
  border: 0;
}

.preview-button:disabled {
  cursor: default;
  opacity: 1;
}

.preview-button:not(:disabled):hover,
.preview-button:not(:disabled):focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.file-preview img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.file-details {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.file-details b {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-details span {
  color: var(--muted);
  font-size: 12px;
}

.file-details .file-preview-error {
  color: var(--bad);
}

.file-actions {
  display: flex;
  gap: 4px;
}

.file-actions button {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  gap: 4px;
  padding: 0 8px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--primary-dark);
  font-size: 12px;
}

.file-actions button:hover:not(:disabled) {
  background: var(--primary-soft);
}

.upload-status {
  display: grid;
  gap: 7px;
}

.upload-status > div {
  display: flex;
  justify-content: space-between;
  color: var(--muted);
  font-size: 12px;
}

.upload-status progress {
  width: 100%;
  height: 7px;
  accent-color: var(--primary-dark);
}

.local-preview-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1510;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(9, 14, 14, .72);
}

.local-preview-dialog {
  display: grid;
  max-width: min(960px, calc(100vw - 48px));
  max-height: calc(100vh - 48px);
  overflow: hidden;
  border-radius: 8px;
  background: #fff;
}

.local-preview-dialog header {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
}

.local-preview-dialog header b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-preview-dialog > img {
  display: block;
  max-width: 100%;
  max-height: calc(100vh - 102px);
  margin: auto;
  object-fit: contain;
}

.supplement-footer {
  margin: 2px -20px -20px;
}

.supplement-footer span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

@media (max-width: 640px) {
  .supplement-backdrop {
    padding: 0;
  }

  .supplement-dialog {
    width: 100vw;
    max-height: 100vh;
    border-radius: 0;
  }

  .supplement-body {
    max-height: calc(100vh - 78px);
  }

  .selected-files li {
    grid-template-columns: 44px minmax(0, 1fr);
  }

  .file-preview {
    width: 44px;
  }

  .file-actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }

}

@media (prefers-reduced-motion: reduce) {
  .supplement-dialog,
  .drop-zone {
    scroll-behavior: auto;
    transition: none;
  }
}
</style>
