<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Download, FileText, Image, ImageOff, LoaderCircle, RefreshCw, X } from 'lucide-vue-next'
import {
  downloadExpenseSupplementAttachment,
  expenseAttachmentDisplayName,
  fetchExpenseSupplementAttachment,
  type ExpenseSupplementAttachment,
} from '../../api/finance'
import UiButton from '../ui/UiButton.vue'

type PreviewState = 'loading' | 'ready' | 'error'

const props = defineProps<{
  expenseId: string
  attachments: ExpenseSupplementAttachment[]
}>()

const imageUrls = ref<Record<string, string>>({})
const imageStates = ref<Record<string, PreviewState>>({})
const downloadingId = ref('')
const preview = ref<{ url: string; alt: string } | null>(null)
const error = ref('')
const controllers = new Map<string, AbortController>()
let loadGeneration = 0

watch(
  () => [props.expenseId, ...props.attachments.map((item) => `${item.id}:${item.contentType || ''}`)],
  () => { void loadThumbnails() },
  { immediate: true },
)

function handlePreviewKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') preview.value = null
}

watch(preview, (value) => {
  if (value) document.addEventListener('keydown', handlePreviewKeydown)
  else document.removeEventListener('keydown', handlePreviewKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handlePreviewKeydown)
  loadGeneration += 1
  cancelImageRequests()
  releaseImages()
})

async function loadThumbnails() {
  const generation = ++loadGeneration
  cancelImageRequests()
  releaseImages()
  error.value = ''
  imageStates.value = Object.fromEntries(
    props.attachments.filter(isImage).map((attachment) => [String(attachment.id), 'loading' as PreviewState]),
  )
  await Promise.all(props.attachments.filter(isImage).map((attachment) => loadImage(attachment, generation)))
}

async function loadImage(attachment: ExpenseSupplementAttachment, generation = loadGeneration) {
  const key = String(attachment.id)
  controllers.get(key)?.abort()
  const controller = new AbortController()
  controllers.set(key, controller)
  setImageState(key, 'loading')

  try {
    const blob = await fetchExpenseSupplementAttachment(props.expenseId, attachment, 'preview', controller.signal)
    if (generation !== loadGeneration || controller.signal.aborted) return
    if (!String(blob.type || attachment.contentType || '').startsWith('image/')) {
      throw new Error('附件返回的类型不是图片')
    }
    const previousUrl = imageUrls.value[key]
    if (previousUrl) URL.revokeObjectURL(previousUrl)
    imageUrls.value = { ...imageUrls.value, [key]: URL.createObjectURL(blob) }
    setImageState(key, 'ready')
  } catch {
    if (generation === loadGeneration && !controller.signal.aborted) setImageState(key, 'error')
  } finally {
    if (controllers.get(key) === controller) controllers.delete(key)
  }
}

function setImageState(key: string, state: PreviewState) {
  imageStates.value = { ...imageStates.value, [key]: state }
}

function cancelImageRequests() {
  controllers.forEach((controller) => controller.abort())
  controllers.clear()
}

function releaseImages() {
  Object.values(imageUrls.value).forEach((url) => URL.revokeObjectURL(url))
  imageUrls.value = {}
  preview.value = null
}

function isImage(attachment: ExpenseSupplementAttachment) {
  const type = String(attachment.contentType || '').toLowerCase()
  if (type.startsWith('image/')) return true
  return /\.(?:jpe?g|png|webp)$/i.test(expenseAttachmentDisplayName(attachment))
}

function isPdf(attachment: ExpenseSupplementAttachment) {
  return String(attachment.contentType || '').toLowerCase() === 'application/pdf'
    || /\.pdf$/i.test(expenseAttachmentDisplayName(attachment))
}

function openPreview(attachment: ExpenseSupplementAttachment) {
  const url = imageUrls.value[String(attachment.id)]
  if (!url) return
  preview.value = { url, alt: expenseAttachmentDisplayName(attachment) }
}

function markPreviewFailed(attachment: ExpenseSupplementAttachment) {
  const key = String(attachment.id)
  const url = imageUrls.value[key]
  if (url) URL.revokeObjectURL(url)
  const nextUrls = { ...imageUrls.value }
  delete nextUrls[key]
  imageUrls.value = nextUrls
  setImageState(key, 'error')
  preview.value = null
}

async function retryPreview(attachment: ExpenseSupplementAttachment) {
  error.value = ''
  await loadImage(attachment)
}

async function download(attachment: ExpenseSupplementAttachment) {
  downloadingId.value = String(attachment.id)
  error.value = ''
  try {
    await downloadExpenseSupplementAttachment(props.expenseId, attachment)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '附件下载失败，请稍后重试。'
  } finally {
    downloadingId.value = ''
  }
}

function fileSize(size: number) {
  if (!size) return '大小未知'
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(size / 1024))} KB`
}
</script>

<template>
  <div class="supplement-attachments">
    <article v-for="attachment in attachments" :key="attachment.id" class="persisted-attachment">
      <button
        v-if="isImage(attachment)"
        class="persisted-preview-button"
        type="button"
        :disabled="imageStates[String(attachment.id)] !== 'ready'"
        :aria-label="`预览 ${expenseAttachmentDisplayName(attachment)}`"
        @click="openPreview(attachment)"
      >
        <span class="persisted-preview">
          <img
            v-if="imageStates[String(attachment.id)] === 'ready' && imageUrls[String(attachment.id)]"
            :src="imageUrls[String(attachment.id)]"
            :alt="`${expenseAttachmentDisplayName(attachment)} 缩略图`"
            @error="markPreviewFailed(attachment)"
          />
          <LoaderCircle v-else-if="imageStates[String(attachment.id)] === 'loading'" class="spin" :size="19" />
          <ImageOff v-else-if="imageStates[String(attachment.id)] === 'error'" :size="19" />
          <Image v-else :size="19" />
        </span>
      </button>
      <span v-else class="persisted-preview static" aria-hidden="true">
        <FileText :size="21" />
      </span>

      <span class="persisted-details">
        <b :title="expenseAttachmentDisplayName(attachment)">{{ expenseAttachmentDisplayName(attachment) }}</b>
        <small>{{ fileSize(attachment.fileSize ?? attachment.sizeBytes ?? 0) }}</small>
        <small v-if="imageStates[String(attachment.id)] === 'loading'">正在加载预览…</small>
        <small v-else-if="imageStates[String(attachment.id)] === 'error'" class="preview-error">预览加载失败</small>
      </span>

      <span class="persisted-actions">
        <button
          v-if="isImage(attachment) && imageStates[String(attachment.id)] === 'error'"
          type="button"
          @click="retryPreview(attachment)"
        >
          <RefreshCw :size="14" />重试
        </button>
        <button type="button" :disabled="downloadingId === String(attachment.id)" @click="download(attachment)">
          <LoaderCircle v-if="downloadingId === String(attachment.id)" class="spin" :size="14" />
          <Download v-else :size="14" />
          {{ isPdf(attachment) ? '下载 PDF' : '下载' }}
        </button>
      </span>
    </article>
    <span v-if="error" class="attachment-error" role="alert">{{ error }}</span>
  </div>

  <Teleport to="body">
    <div v-if="preview" class="image-preview-backdrop" @click.self="preview = null" @keydown.esc="preview = null">
      <section class="image-preview-dialog" role="dialog" aria-modal="true" aria-label="图片预览">
        <header>
          <b>{{ preview.alt }}</b>
          <UiButton variant="ghost" icon-only aria-label="关闭图片预览" title="关闭" @click="preview = null">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>
        <img :src="preview.url" :alt="preview.alt" />
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.supplement-attachments {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.persisted-attachment {
  display: grid;
  width: min(320px, 100%);
  min-height: 62px;
  grid-template-columns: 52px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  padding: 7px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fff;
  color: var(--ink);
}

.persisted-preview-button,
.persisted-preview {
  display: grid;
  width: 52px;
  height: 46px;
  overflow: hidden;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: var(--ds-surface-muted);
  color: var(--muted);
}

.persisted-preview-button:not(:disabled):hover,
.persisted-preview-button:not(:disabled):focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.persisted-preview-button:disabled {
  cursor: default;
  opacity: 1;
}

.persisted-preview img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.persisted-details {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.persisted-details b,
.persisted-details small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.persisted-details b {
  font-size: 12px;
}

.persisted-details small {
  color: var(--muted);
  font-size: 11px;
}

.persisted-details .preview-error,
.attachment-error {
  color: var(--bad);
}

.persisted-actions {
  display: flex;
  align-items: center;
  gap: 3px;
}

.persisted-actions button {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  gap: 4px;
  padding: 0 7px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--primary-dark);
  font-size: 12px;
  white-space: nowrap;
}

.persisted-actions button:hover:not(:disabled),
.persisted-actions button:focus-visible {
  background: var(--primary-soft);
}

.attachment-error {
  width: 100%;
  font-size: 12px;
}

.image-preview-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1510;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(9, 14, 14, .72);
}

.image-preview-dialog {
  display: grid;
  max-width: min(960px, calc(100vw - 48px));
  max-height: calc(100vh - 48px);
  overflow: hidden;
  border-radius: 8px;
  background: #fff;
}

.image-preview-dialog header {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
}

.image-preview-dialog header b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.image-preview-dialog > img {
  display: block;
  max-width: 100%;
  max-height: calc(100vh - 102px);
  margin: auto;
  object-fit: contain;
}

.spin {
  animation: attachment-spin .8s linear infinite;
}

@keyframes attachment-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 720px) {
  .persisted-attachment {
    width: 100%;
    grid-template-columns: 52px minmax(0, 1fr);
  }

  .persisted-actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }
}

@media (prefers-reduced-motion: reduce) {
  .spin { animation: none; }
}
</style>
