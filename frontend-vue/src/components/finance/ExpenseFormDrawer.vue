<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { AlertTriangle, FileText, Image, Paperclip, RefreshCw, Trash2, X } from 'lucide-vue-next'
import {
  createExpense,
  submitExpense,
  updateExpense,
  uploadExpenseAttachment,
  type ExpenseClaim,
  type ExpenseClaimPayload,
} from '../../api/finance'
import type { StoreInfo } from '../../api/operations'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'

const props = defineProps<{
  stores: StoreInfo[]
  claim?: ExpenseClaim | null
  lockedStoreId?: string
  lockedStoreName?: string
}>()

const emit = defineEmits<{
  close: []
  saved: [claim: ExpenseClaim]
}>()

const form = reactive<ExpenseClaimPayload>(emptyForm())
const initialSnapshot = ref('')
const file = ref<File | null>(null)
const saving = ref(false)
const error = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const fileType = ref('')
const previewUrl = ref('')
const previewFailed = ref(false)
const previewOpen = ref(false)
const confirmDialog = ref(false)
let selectionGeneration = 0

const title = computed(() => props.claim ? '编辑报销' : '新增报销')
const submitLabel = computed(() => props.claim?.status === '草稿' ? '提交审核' : '保存并提交')
const storeOptions = computed(() => props.stores)

const dirty = computed(() => {
  const current = JSON.stringify({ ...form, file: file.value?.name ?? '' })
  return current !== initialSnapshot.value
})

function captureSnapshot() {
  initialSnapshot.value = JSON.stringify({ ...form, file: file.value?.name ?? '' })
}

watch(
  () => props.claim,
  () => { resetForm(); captureSnapshot() },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('keydown', handleGlobalEsc)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleGlobalEsc)
  releasePreview()
})

function emptyForm(): ExpenseClaimPayload {
  const now = new Date()
  return {
    storeId: props.lockedStoreId || props.stores[0]?.id || '',
    month: `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`,
    amount: 0,
    category: '',
    reason: '',
    imageUrl: '',
  }
}

function resetForm() {
  releasePreview()
  const claim = props.claim
  Object.assign(form, claim
    ? {
        storeId: props.lockedStoreId || claim.storeId,
        month: claim.month,
        amount: Number(claim.amount || 0),
        category: claim.category || '',
        reason: claim.reason || '',
        imageUrl: claim.imageUrl || '',
      }
    : emptyForm())
  file.value = null
  fileType.value = ''
  previewFailed.value = false
  error.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

function chooseFile() {
  fileInput.value?.click()
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0] || null
  input.value = ''
  if (!selected) return

  releasePreview()
  const generation = ++selectionGeneration
  file.value = selected
  fileType.value = selected.type
  previewFailed.value = false
  error.value = ''
  if (selected.type.startsWith('image/') || /\.(?:jpe?g|png|webp)$/i.test(selected.name)) {
    previewUrl.value = URL.createObjectURL(selected)
  }

  const actualType = await detectFileType(selected)
  if (generation !== selectionGeneration) return
  const validationError = validateAttachment(selected, actualType)
  if (validationError) {
    releasePreview()
    file.value = null
    fileType.value = ''
    error.value = validationError
    return
  }

  fileType.value = actualType
  if (actualType.startsWith('image/') && !previewUrl.value) previewUrl.value = URL.createObjectURL(selected)
}

function validateAttachment(selected: File, actualType: string) {
  if (!selected.size) return `${selected.name} 是空文件，无法上传。`
  if (selected.size > 10 * 1024 * 1024) return `${selected.name} 超过 10MB。`
  const acceptedTypes = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf']
  if (!acceptedTypes.includes(actualType)) return `${selected.name} 不是有效的 JPG、PNG、WebP 或 PDF 文件。`
  const extension = selected.name.slice(selected.name.lastIndexOf('.')).toLowerCase()
  const expectedTypes: Record<string, string> = {
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.png': 'image/png',
    '.webp': 'image/webp',
    '.pdf': 'application/pdf',
  }
  if (expectedTypes[extension] !== actualType) return `${selected.name} 的扩展名与真实文件类型不一致。`
  return ''
}

async function detectFileType(selected: File) {
  const bytes = new Uint8Array(await selected.slice(0, 16).arrayBuffer())
  if (bytes.length >= 4 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg'
  if (bytes.length >= 8 && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((value, index) => bytes[index] === value)) return 'image/png'
  if (bytes.length >= 12 && ascii(bytes, 0, 4) === 'RIFF' && ascii(bytes, 8, 12) === 'WEBP') return 'image/webp'
  if (bytes.length >= 5 && ascii(bytes, 0, 5) === '%PDF-') return 'application/pdf'
  return ''
}

function ascii(bytes: Uint8Array, from: number, to: number) {
  return String.fromCharCode(...bytes.slice(from, to))
}

function releasePreview() {
  selectionGeneration += 1
  revokePreviewUrl()
  previewOpen.value = false
}

function revokePreviewUrl() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
}

function removeFile() {
  releasePreview()
  file.value = null
  fileType.value = ''
  previewFailed.value = false
  error.value = ''
}

function markPreviewFailed() {
  revokePreviewUrl()
  previewOpen.value = false
  previewFailed.value = true
}

function retryPreview() {
  if (!file.value || !fileType.value.startsWith('image/')) return
  revokePreviewUrl()
  previewOpen.value = false
  previewUrl.value = URL.createObjectURL(file.value)
  previewFailed.value = false
}

function fileSize(size: number) {
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(size / 1024))} KB`
}

function requestClose() {
  if (saving.value) return
  if (dirty.value) {
    confirmDialog.value = true
    return
  }
  releasePreview()
  emit('close')
}

function handleGlobalEsc(event: KeyboardEvent) {
  if (event.key === 'Escape' && !previewOpen.value) {
    requestClose()
  }
}

function onBackdrop() {
  requestClose()
}

function onDismiss() {
  confirmDialog.value = false
  releasePreview()
  emit('close')
}

function onContinueEdit() {
  confirmDialog.value = false
}

async function save(shouldSubmit: boolean) {
  error.value = ''
  const storeId = props.lockedStoreId || form.storeId
  if (!storeId) {
    error.value = '请选择门店。'
    return
  }
  if (!form.month) {
    error.value = '请选择报销月份。'
    return
  }
  if (!Number.isFinite(Number(form.amount)) || Number(form.amount) <= 0) {
    error.value = '请输入大于 0 的报销金额。'
    return
  }
  if (!String(form.reason || '').trim()) {
    error.value = '请填写报销说明。'
    return
  }

  saving.value = true
  try {
    let saved = props.claim
      ? await updateExpense(props.claim.id, { ...form, storeId, amount: Number(form.amount) })
      : await createExpense({ ...form, storeId, amount: Number(form.amount) })
    if (file.value) {
      const attachment = await uploadExpenseAttachment(file.value, saved.storeId, saved.id)
      saved = await updateExpense(saved.id, {
        storeId: saved.storeId,
        month: saved.month,
        amount: Number(saved.amount),
        category: saved.category,
        reason: saved.reason,
        imageUrl: attachment.downloadUrl,
      })
    }
    if (shouldSubmit) saved = await submitExpense(saved.id)
    releasePreview()
    captureSnapshot()
    emit('saved', saved)
  } catch (reason) {
    error.value = displayError(reason)
  } finally {
    saving.value = false
  }
}

function displayError(reason: unknown) {
  const message = reason instanceof Error ? reason.message : String(reason || '')
  return message || '报销保存失败，请稍后重试。'
}
</script>

<template>
    <div class="drawer-backdrop" @click.self="onBackdrop">
    <aside class="expense-drawer" role="dialog" aria-modal="true" :aria-label="title">
      <header class="drawer-head">
        <h3>{{ title }}</h3>
        <UiButton
          variant="ghost"
          icon-only
          type="button"
          :aria-label="`关闭${title}`"
          title="关闭"
          :disabled="saving"
          @click="requestClose"
        >
          <template #icon><X :size="18" /></template>
        </UiButton>
      </header>

      <div class="drawer-body">
        <div v-if="error" class="error-box compact-error">{{ error }}</div>
        <div v-if="props.lockedStoreId" class="locked-store-field">
          <span>报销门店</span>
          <strong>{{ props.lockedStoreName || storeOptions.find((store) => store.id === props.lockedStoreId)?.name || props.lockedStoreId }}</strong>
        </div>
        <label v-else>
          门店
          <select v-model="form.storeId" :disabled="Boolean(props.claim) || saving">
            <option value="">请选择门店</option>
            <option v-for="store in storeOptions" :key="store.id" :value="store.id">
              {{ store.brandName ? `${store.brandName} · ` : '' }}{{ store.name }}
            </option>
          </select>
        </label>
        <div class="form-grid">
          <label>
            报销月份
            <input v-model="form.month" type="month" :disabled="saving" />
          </label>
          <label>
            报销金额
            <input v-model.number="form.amount" type="number" min="0" step="0.01" placeholder="请输入金额" :disabled="saving" />
          </label>
        </div>
        <label>
          报销类别
          <input v-model.trim="form.category" type="text" placeholder="例如：物料采购、设备维修" :disabled="saving" />
        </label>
        <label>
          报销说明
          <textarea v-model.trim="form.reason" rows="4" placeholder="填写用途、业务原因和票据说明" :disabled="saving" />
        </label>
        <input
          ref="fileInput"
          class="file-input"
          type="file"
          accept=".jpg,.jpeg,.png,.webp,.pdf,image/jpeg,image/png,image/webp,application/pdf"
          @change="onFileChange"
        />
        <section class="attachment-section" aria-label="报销附件">
          <div class="attachment-heading">
            <div>
              <b>报销凭证</b>
              <span>JPG、PNG、WebP 或 PDF，单个不超过 10MB</span>
            </div>
            <button v-if="!file" class="ghost-button" type="button" :disabled="saving" @click="chooseFile">
              <Paperclip :size="16" />选择附件
            </button>
          </div>

          <article v-if="file" class="selected-attachment">
            <button
              v-if="previewUrl || fileType.startsWith('image/')"
              class="attachment-preview preview-button"
              type="button"
              :disabled="!previewUrl"
              :aria-label="`预览 ${file.name}`"
              @click="previewOpen = true"
            >
              <img v-if="previewUrl" :src="previewUrl" :alt="`${file.name} 缩略图`" @error="markPreviewFailed" />
              <Image v-else :size="23" />
            </button>
            <span v-else class="attachment-preview"><FileText :size="23" /></span>
            <span class="attachment-details">
              <b :title="file.name">{{ file.name }}</b>
              <small>{{ fileSize(file.size) }}</small>
              <small v-if="previewFailed" class="preview-error">预览生成失败，可重试</small>
            </span>
            <span class="attachment-actions">
              <button v-if="previewFailed" type="button" :disabled="saving" @click="retryPreview">
                <RefreshCw :size="14" />重试预览
              </button>
              <button type="button" :disabled="saving" @click="chooseFile">
                <RefreshCw :size="14" />重新选择
              </button>
              <button type="button" :disabled="saving" @click="removeFile">
                <Trash2 :size="14" />删除
              </button>
            </span>
          </article>

          <div v-else-if="form.imageUrl" class="existing-attachment">
            <Paperclip :size="17" />
            <span><b>已保存附件</b><small>重新选择后将替换现有附件</small></span>
            <button class="ghost-button" type="button" :disabled="saving" @click="chooseFile">重新选择</button>
          </div>
          <p v-else class="attachment-empty">尚未选择附件</p>
        </section>
      </div>

      <ModalFooter sticky>
        <UiButton variant="secondary" type="button" :disabled="saving" @click="requestClose">取消</UiButton>
        <UiButton variant="secondary" type="button" :loading="saving" @click="save(false)">保存草稿</UiButton>
        <UiButton variant="primary" type="button" :loading="saving" @click="save(true)">{{ submitLabel }}</UiButton>
      </ModalFooter>
    </aside>
  </div>

  <Teleport to="body">
    <div v-if="previewOpen && previewUrl && file" class="drawer-preview-backdrop" @click.self="previewOpen = false" @keydown.esc="previewOpen = false">
      <section class="drawer-preview-dialog" role="dialog" aria-modal="true" aria-label="报销凭证预览">
        <header>
          <b>{{ file.name }}</b>
          <UiButton variant="ghost" icon-only type="button" aria-label="关闭预览" title="关闭" @click="previewOpen = false">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>
        <img :src="previewUrl" :alt="file.name" @error="markPreviewFailed" />
      </section>
    </div>
  </Teleport>

  <Teleport to="body">
    <div v-if="confirmDialog" class="confirm-backdrop" @click.self="onContinueEdit" @keydown.esc="onContinueEdit">
      <section class="confirm-dialog" role="alertdialog" aria-modal="true" aria-label="放弃未保存内容？">
        <header>
          <AlertTriangle :size="20" />
          <b>放弃未保存内容？</b>
        </header>
        <p>当前填写的报销信息和已选择附件将不会保存。</p>
        <ModalFooter>
          <UiButton variant="secondary" type="button" @click="onContinueEdit">继续填写</UiButton>
          <UiButton variant="danger" type="button" @click="onDismiss">放弃修改</UiButton>
        </ModalFooter>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  justify-content: flex-end;
  background: rgba(20, 24, 32, 0.34);
}

.expense-drawer {
  display: flex;
  width: min(520px, 100vw);
  min-height: 100%;
  flex-direction: column;
  background: #fff;
  box-shadow: -12px 0 32px rgba(22, 26, 34, 0.16);
}

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--line);
}

.drawer-head h3 {
  margin: 0;
  font-size: 18px;
}

.drawer-body {
  display: grid;
  flex: 1;
  align-content: start;
  gap: 14px;
  overflow-y: auto;
  padding: 18px;
}

.drawer-body label {
  display: grid;
  gap: 7px;
  color: var(--ink);
  font-size: 13px;
  font-weight: 800;
}

.locked-store-field {
  display: flex;
  min-height: 40px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  font-size: 13px;
}

.locked-store-field strong {
  color: var(--ds-ink);
}

.drawer-body input,
.drawer-body select,
.drawer-body textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  color: var(--ink);
  font: inherit;
  padding: 10px 11px;
}

.drawer-body textarea {
  resize: vertical;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.file-input {
  display: none;
}

.attachment-section {
  display: grid;
  gap: 10px;
  padding-top: 2px;
}

.attachment-heading,
.selected-attachment,
.existing-attachment {
  display: flex;
  align-items: center;
  gap: 10px;
}

.attachment-heading {
  justify-content: space-between;
}

.attachment-heading > div,
.attachment-details,
.existing-attachment > span {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.attachment-heading span,
.attachment-details small,
.existing-attachment small,
.attachment-empty {
  color: var(--muted);
  font-size: 12px;
}

.selected-attachment,
.existing-attachment {
  min-height: 66px;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fff;
}

.attachment-preview {
  display: grid;
  width: 58px;
  height: 48px;
  flex: none;
  overflow: hidden;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: var(--ds-surface-muted);
  color: var(--muted);
}

.attachment-preview img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
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

.attachment-details {
  flex: 1;
}

.attachment-details b,
.attachment-details small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-details .preview-error {
  color: var(--bad);
}

.attachment-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.attachment-actions button {
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
}

.attachment-actions button:hover:not(:disabled),
.attachment-actions button:focus-visible {
  background: var(--primary-soft);
}

.existing-attachment > span {
  flex: 1;
}

.attachment-empty {
  margin: 0;
  padding: 12px;
  border: 1px dashed var(--line);
  border-radius: 7px;
  text-align: center;
}

.drawer-preview-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1510;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(9, 14, 14, .72);
}

.drawer-preview-dialog {
  display: grid;
  max-width: min(960px, calc(100vw - 48px));
  max-height: calc(100vh - 48px);
  overflow: hidden;
  border-radius: 8px;
  background: #fff;
}

.drawer-preview-dialog header {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
}

.drawer-preview-dialog header b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-preview-dialog > img {
  display: block;
  max-width: 100%;
  max-height: calc(100vh - 102px);
  margin: auto;
  object-fit: contain;
}

/* ---- confirm dialog ---- */
.confirm-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1520;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(9, 14, 14, 0.55);
}

.confirm-dialog {
  display: grid;
  gap: 16px;
  width: min(400px, calc(100vw - 48px));
  padding: 24px 24px 20px;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 16px 48px rgba(22, 26, 34, 0.2);
}

.confirm-dialog header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0;
  border: 0;
  color: var(--ds-warning);
}

.confirm-dialog header b {
  font-size: 16px;
  color: var(--ink);
}

.confirm-dialog p {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.6;
}

.compact-error {
  margin: 0;
}

@media (max-width: 560px) {
  .form-grid {
    grid-template-columns: 1fr;
  }

  .selected-attachment {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .attachment-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
