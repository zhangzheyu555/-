<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { FileQuestion, X } from 'lucide-vue-next'
import { requestExpenseInfo, type ExpenseClaim } from '../../api/finance'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'
import UnsavedChangesDialog from '../ui/UnsavedChangesDialog.vue'

const MAX_AUDIT_NOTE_LENGTH = 255

const props = defineProps<{ expense: ExpenseClaim }>()
const emit = defineEmits<{
  close: []
  submitted: [claim: ExpenseClaim]
}>()

const initialNote = '请补充票据、付款记录或报销说明'
const note = ref(initialNote)
const noteInput = ref<HTMLTextAreaElement | null>(null)
const submitting = ref(false)
const error = ref('')
const discardConfirmOpen = ref(false)
const dirty = computed(() => note.value !== initialNote)

onMounted(() => {
  void nextTick(() => {
    noteInput.value?.focus()
    noteInput.value?.select()
  })
})

function close() {
  if (submitting.value) return
  if (dirty.value) {
    discardConfirmOpen.value = true
    return
  }
  emit('close')
}

function discardAndClose() {
  discardConfirmOpen.value = false
  emit('close')
}

async function submit() {
  const value = note.value.trim()
  if (!value) {
    error.value = '请填写需要门店补充的资料。'
    noteInput.value?.focus()
    return
  }
  if (value.length > MAX_AUDIT_NOTE_LENGTH) {
    error.value = `补充资料说明不能超过${MAX_AUDIT_NOTE_LENGTH}个字符。`
    noteInput.value?.focus()
    return
  }
  submitting.value = true
  error.value = ''
  try {
    emit('submitted', await requestExpenseInfo(props.expense.id, value))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '提交失败，请保留当前内容后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="request-backdrop" @mousedown.self="close" @keydown.esc="close">
      <section class="request-dialog" role="dialog" aria-modal="true" aria-labelledby="request-title">
        <header>
          <div>
            <h3 id="request-title">要求补充资料</h3>
            <p>{{ expense.storeName || expense.storeCode || expense.storeId }} · {{ expense.month }}</p>
          </div>
          <UiButton variant="ghost" icon-only type="button" aria-label="关闭" title="关闭" :disabled="submitting" @click="close">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>
        <form @submit.prevent="submit">
          <div v-if="error" class="error-box" role="alert">{{ error }}</div>
          <label>
            <span>需要补充的资料</span>
            <textarea ref="noteInput" v-model="note" rows="5" :maxlength="MAX_AUDIT_NOTE_LENGTH" :disabled="submitting" />
            <small>这段说明会保存在审核记录中，并将报销状态改为“待补资料”。</small>
            <small>补充资料说明最多 {{ MAX_AUDIT_NOTE_LENGTH }} 个字符（当前 {{ note.length }}/{{ MAX_AUDIT_NOTE_LENGTH }}）。</small>
          </label>
          <ModalFooter class="request-footer">
            <template #info><span><FileQuestion :size="15" />通知内容不包含文件本身</span></template>
            <UiButton variant="secondary" type="button" :disabled="submitting" @click="close">取消</UiButton>
            <UiButton variant="primary" type="submit" :loading="submitting">确认要求补充</UiButton>
          </ModalFooter>
        </form>
      </section>
    </div>
  </Teleport>

  <UnsavedChangesDialog
    :open="discardConfirmOpen"
    title="放弃未提交的补资料要求？"
    message="关闭后，当前修改的通知内容将不会保留。"
    @keep-editing="discardConfirmOpen = false"
    @discard="discardAndClose"
  />
</template>

<style scoped>
.request-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1500;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(20, 29, 29, .42);
}

.request-dialog {
  width: min(540px, calc(100vw - 32px));
  overflow: hidden;
  border-radius: 9px;
  background: #fff;
}

.request-dialog > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 16px 18px;
}

.request-dialog > header {
  border-bottom: 1px solid var(--line);
}

.request-dialog h3,
.request-dialog p {
  margin: 0;
}

.request-dialog h3 {
  font-size: 18px;
}

.request-dialog p,
.request-dialog small {
  color: var(--muted);
  font-size: 12px;
}

.request-dialog p {
  margin-top: 4px;
}

.request-dialog form {
  display: grid;
  gap: 14px;
  padding: 18px;
}

.request-dialog label {
  display: grid;
  gap: 7px;
  font-weight: 700;
}

.request-dialog textarea {
  width: 100%;
  resize: vertical;
}

.request-footer {
  margin: 0 -18px -18px;
}

.request-footer span {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
