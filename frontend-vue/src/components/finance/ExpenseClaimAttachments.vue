<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Download, Image, ImageOff, LoaderCircle, RefreshCw, X } from 'lucide-vue-next'
import {
  downloadExpenseAttachment,
  expenseClaimAttachmentDisplayName,
  fetchExpenseAttachment,
  type ExpenseAttachment,
} from '../../api/finance'
import UiButton from '../ui/UiButton.vue'

type PreviewState = 'loading' | 'ready' | 'error'

const props = defineProps<{ attachments: ExpenseAttachment[] }>()

const imageUrls = ref<Record<string, string>>({})
const imageStates = ref<Record<string, PreviewState>>({})
const downloadingId = ref('')
const preview = ref<{ url: string; alt: string } | null>(null)
const error = ref('')
const controllers = new Map<string, AbortController>()
let loadGeneration = 0

watch(
  () => props.attachments.map((item) => `${item.id}:${item.contentType || ''}`),
  () => { void loadImages() },
  { immediate: true },
)

onBeforeUnmount(() => {
  loadGeneration += 1
  controllers.forEach((controller) => controller.abort())
  releaseImages()
})

function isImage(attachment: ExpenseAttachment) {
  const type = String(attachment.contentType || '').toLowerCase()
  if (type.startsWith('image/')) return true
  return /\.(?:jpe?g|png|webp)$/i.test(expenseClaimAttachmentDisplayName(attachment))
}

async function loadImages() {
  const generation = ++loadGeneration
  controllers.forEach((controller) => controller.abort())
  controllers.clear()
  releaseImages()
  imageStates.value = Object.fromEntries(
    props.attachments.filter(isImage).map((attachment) => [String(attachment.id), 'loading' as PreviewState]),
  )
  await Promise.all(props.attachments.filter(isImage).map((attachment) => loadImage(attachment, generation)))
}

async function loadImage(attachment: ExpenseAttachment, generation = loadGeneration) {
  const key = String(attachment.id)
  const controller = new AbortController()
  controllers.set(key, controller)
  imageStates.value = { ...imageStates.value, [key]: 'loading' }
  try {
    const blob = await fetchExpenseAttachment(attachment, controller.signal)
    if (generation !== loadGeneration || controller.signal.aborted) return
    if (!String(blob.type || attachment.contentType || '').startsWith('image/')) throw new Error('不是图片')
    imageUrls.value = { ...imageUrls.value, [key]: URL.createObjectURL(blob) }
    imageStates.value = { ...imageStates.value, [key]: 'ready' }
  } catch {
    if (generation === loadGeneration && !controller.signal.aborted) {
      imageStates.value = { ...imageStates.value, [key]: 'error' }
    }
  } finally {
    if (controllers.get(key) === controller) controllers.delete(key)
  }
}

function releaseImages() {
  Object.values(imageUrls.value).forEach((url) => URL.revokeObjectURL(url))
  imageUrls.value = {}
  preview.value = null
}

function openPreview(attachment: ExpenseAttachment) {
  const url = imageUrls.value[String(attachment.id)]
  if (!url) return
  preview.value = { url, alt: expenseClaimAttachmentDisplayName(attachment) }
}

async function retry(attachment: ExpenseAttachment) {
  error.value = ''
  await loadImage(attachment)
}

async function download(attachment: ExpenseAttachment) {
  downloadingId.value = String(attachment.id)
  error.value = ''
  try {
    await downloadExpenseAttachment(attachment)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '附件下载失败，请稍后重试。'
  } finally {
    downloadingId.value = ''
  }
}
</script>

<template>
  <div class="expense-attachments">
    <article v-for="attachment in attachments" :key="attachment.id" class="expense-attachment">
      <button
        v-if="isImage(attachment)"
        class="expense-preview"
        type="button"
        :disabled="imageStates[String(attachment.id)] !== 'ready'"
        :aria-label="`预览 ${expenseClaimAttachmentDisplayName(attachment)}`"
        @click="openPreview(attachment)"
      >
        <img
          v-if="imageStates[String(attachment.id)] === 'ready' && imageUrls[String(attachment.id)]"
          :src="imageUrls[String(attachment.id)]"
          :alt="`${expenseClaimAttachmentDisplayName(attachment)} 缩略图`"
        />
        <LoaderCircle v-else-if="imageStates[String(attachment.id)] === 'loading'" class="spin" :size="18" />
        <ImageOff v-else-if="imageStates[String(attachment.id)] === 'error'" :size="18" />
        <Image v-else :size="18" />
      </button>
      <span class="expense-attachment-name">{{ expenseClaimAttachmentDisplayName(attachment) }}</span>
      <button
        v-if="isImage(attachment) && imageStates[String(attachment.id)] === 'error'"
        class="expense-attachment-action"
        type="button"
        @click="retry(attachment)"
      >
        <RefreshCw :size="14" />重试
      </button>
      <button class="expense-attachment-action" type="button" :disabled="downloadingId === String(attachment.id)" @click="download(attachment)">
        <LoaderCircle v-if="downloadingId === String(attachment.id)" class="spin" :size="14" />
        <Download v-else :size="14" />下载
      </button>
    </article>
    <span v-if="error" class="attachment-error" role="alert">{{ error }}</span>
  </div>

  <Teleport to="body">
    <div v-if="preview" class="image-preview-backdrop" @click.self="preview = null" @keydown.esc="preview = null">
      <section class="image-preview-dialog" role="dialog" aria-modal="true" aria-label="报销图片预览">
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
.expense-attachments {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.expense-attachment {
  display: grid;
  width: min(260px, 100%);
  min-height: 58px;
  grid-template-columns: 52px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 7px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fff;
}

.expense-preview {
  display: grid;
  width: 52px;
  height: 44px;
  overflow: hidden;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: var(--ds-surface-muted);
  color: var(--muted);
}

.expense-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.expense-preview:not(:disabled):hover,
.expense-preview:not(:disabled):focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

.expense-preview:disabled {
  cursor: default;
  opacity: 1;
}

.expense-attachment-name {
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.expense-attachment-action {
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

.expense-attachment-action:hover:not(:disabled),
.expense-attachment-action:focus-visible {
  background: var(--primary-soft);
}

.attachment-error {
  width: 100%;
  color: var(--bad);
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
  animation: expense-attachment-spin .8s linear infinite;
}

@keyframes expense-attachment-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 720px) {
  .expense-attachment {
    width: 100%;
    grid-template-columns: 52px minmax(0, 1fr);
  }

  .expense-attachment-action {
    grid-column: 2;
    justify-self: start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .spin { animation: none; }
}
</style>
