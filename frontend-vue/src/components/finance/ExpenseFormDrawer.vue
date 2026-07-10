<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Paperclip, X } from 'lucide-vue-next'
import {
  createExpense,
  submitExpense,
  updateExpense,
  uploadExpenseAttachment,
  type ExpenseClaim,
  type ExpenseClaimPayload,
} from '../../api/finance'
import type { StoreInfo } from '../../api/operations'

const props = defineProps<{
  stores: StoreInfo[]
  claim?: ExpenseClaim | null
}>()

const emit = defineEmits<{
  close: []
  saved: [claim: ExpenseClaim]
}>()

const form = reactive<ExpenseClaimPayload>(emptyForm())
const file = ref<File | null>(null)
const saving = ref(false)
const error = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

const title = computed(() => props.claim ? '编辑报销' : '新增报销')
const submitLabel = computed(() => props.claim?.status === '草稿' ? '提交审核' : '保存并提交')
const storeOptions = computed(() => props.stores)

watch(
  () => props.claim,
  () => resetForm(),
  { immediate: true },
)

function emptyForm(): ExpenseClaimPayload {
  const now = new Date()
  return {
    storeId: props.stores[0]?.id || '',
    month: `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`,
    amount: 0,
    category: '',
    reason: '',
    imageUrl: '',
  }
}

function resetForm() {
  const claim = props.claim
  Object.assign(form, claim
    ? {
        storeId: claim.storeId,
        month: claim.month,
        amount: Number(claim.amount || 0),
        category: claim.category || '',
        reason: claim.reason || '',
        imageUrl: claim.imageUrl || '',
      }
    : emptyForm())
  file.value = null
  error.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

function chooseFile() {
  fileInput.value?.click()
}

function onFileChange(event: Event) {
  const selected = (event.target as HTMLInputElement).files?.[0] || null
  if (selected && selected.size > 20 * 1024 * 1024) {
    error.value = '附件不能超过 20MB。'
    file.value = null
    return
  }
  file.value = selected
  error.value = ''
}

async function save(shouldSubmit: boolean) {
  error.value = ''
  if (!form.storeId) {
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
      ? await updateExpense(props.claim.id, { ...form, amount: Number(form.amount) })
      : await createExpense({ ...form, amount: Number(form.amount) })
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
  <div class="drawer-backdrop" @click.self="emit('close')">
    <aside class="expense-drawer" role="dialog" aria-modal="true" :aria-label="title">
      <header class="drawer-head">
        <h3>{{ title }}</h3>
        <button class="icon-button" type="button" aria-label="关闭" :disabled="saving" @click="emit('close')">
          <X :size="18" />
        </button>
      </header>

      <div class="drawer-body">
        <div v-if="error" class="error-box compact-error">{{ error }}</div>
        <label>
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
        <input ref="fileInput" class="file-input" type="file" accept="image/*,.pdf" @change="onFileChange" />
        <div class="attachment-row">
          <button class="ghost-button" type="button" :disabled="saving" @click="chooseFile">
            <Paperclip :size="16" />
            {{ file ? '更换附件' : '添加附件' }}
          </button>
          <span>{{ file?.name || (form.imageUrl ? '已有附件' : '未添加附件') }}</span>
        </div>
      </div>

      <footer class="drawer-actions">
        <button class="ghost-button" type="button" :disabled="saving" @click="save(false)">保存草稿</button>
        <button class="primary-button" type="button" :disabled="saving" @click="save(true)">{{ saving ? '正在保存' : submitLabel }}</button>
      </footer>
    </aside>
  </div>
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

.drawer-head,
.drawer-actions {
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

.attachment-row {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: 13px;
}

.drawer-actions {
  justify-content: flex-end;
  border-top: 1px solid var(--line);
  border-bottom: 0;
}

.compact-error {
  margin: 0;
}

@media (max-width: 560px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
