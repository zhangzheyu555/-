<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RotateCcw, X } from 'lucide-vue-next'
import { reportAppError } from '../../errors/appErrorDialog'
import type {
  WarehouseItem,
  WarehouseRequisition,
  WarehouseReturnCreatePayload,
  WarehouseReturnOrder,
} from '../../api/warehouse'
import { availableStoreReturnQuantity } from '../../utils/storeReturnAvailability'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'
import UnsavedChangesDialog from '../ui/UnsavedChangesDialog.vue'

type ReturnLineDraft = {
  itemId: number
  itemName: string
  unit: string
  availableQuantity: number
  quantity: string
}

const props = defineProps<{
  open: boolean
  requisition: WarehouseRequisition | null
  returns: WarehouseReturnOrder[]
  items: WarehouseItem[]
  warehouseName: string
  submitting?: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [payload: WarehouseReturnCreatePayload]
}>()

const dialogRef = ref<HTMLElement | null>(null)
const returnDate = ref('')
const reason = ref('')
const note = ref('')
const lines = ref<ReturnLineDraft[]>([])
const discardPromptOpen = ref(false)
const initialSnapshot = ref('')
const instanceId = `store-return-${Math.random().toString(36).slice(2, 9)}`
const titleId = `${instanceId}-title`
const descriptionId = `${instanceId}-description`
let previouslyFocused: HTMLElement | null = null
let appRoot: HTMLElement | null = null
let appWasInert = false

const selectedLines = computed(() => lines.value.filter((line) => Number(line.quantity) > 0))
const dirty = computed(() => snapshot() !== initialSnapshot.value)
const hasReturnableLine = computed(() => lines.value.some((line) => line.availableQuantity > 0))

function localDate() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function buildLines() {
  const requisition = props.requisition
  if (!requisition) return []
  return requisition.lines.map((line) => {
    const item = props.items.find((candidate) => candidate.id === line.itemId)
    return {
      itemId: line.itemId,
      itemName: line.itemName,
      unit: line.unit || item?.stockUnit || item?.unit || '',
      availableQuantity: availableStoreReturnQuantity(requisition, line, props.returns, props.items),
      quantity: '',
    }
  })
}

function resetDraft() {
  returnDate.value = localDate()
  reason.value = ''
  note.value = ''
  lines.value = buildLines()
  discardPromptOpen.value = false
  initialSnapshot.value = snapshot()
}

function snapshot() {
  return JSON.stringify({
    requisitionId: props.requisition?.id || '',
    returnDate: returnDate.value,
    reason: reason.value,
    note: note.value,
    lines: lines.value.map((line) => [line.itemId, line.quantity]),
  })
}

function requestClose() {
  if (props.submitting) return
  if (dirty.value) {
    discardPromptOpen.value = true
    return
  }
  emit('close')
}

function discardAndClose() {
  discardPromptOpen.value = false
  emit('close')
}

function submit() {
  const source = props.requisition
  if (!source) return
  if (!returnDate.value) {
    reportAppError('请选择退货日期。', { title: '退货单信息不完整' })
    return
  }
  if (!reason.value.trim()) {
    reportAppError('请填写退货原因。', { title: '退货单信息不完整' })
    return
  }
  if (!selectedLines.value.length) {
    reportAppError('请至少填写一种物料的退货数量。', { title: '退货单信息不完整' })
    return
  }
  const invalidLine = selectedLines.value.find((line) => (
    !Number.isFinite(Number(line.quantity))
    || Number(line.quantity) <= 0
    || Number(line.quantity) > line.availableQuantity
  ))
  if (invalidLine) {
    reportAppError(
      `${invalidLine.itemName}的退货数量不能超过当前可退数量 ${quantityText(invalidLine.availableQuantity, invalidLine.unit)}。`,
      { title: '退货数量不正确' },
    )
    return
  }
  emit('submit', {
    returnStoreId: source.storeId,
    sourceRequisitionId: source.id,
    returnDate: returnDate.value,
    reason: reason.value.trim(),
    note: note.value.trim() || undefined,
    lines: selectedLines.value.map((line) => ({
      itemId: line.itemId,
      quantity: Number(line.quantity),
      reason: reason.value.trim(),
      note: note.value.trim() || undefined,
    })),
    attachments: [],
  })
}

function quantityText(value: number, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${unit || ''}`
}

function handleKeydown(event: KeyboardEvent) {
  if (!props.open || discardPromptOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  if (event.key !== 'Tab') return
  const focusable = Array.from(dialogRef.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [href], [tabindex]:not([tabindex="-1"])',
  ) || [])
  if (!focusable.length) {
    event.preventDefault()
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || !dialogRef.value?.contains(active))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (active === last || !dialogRef.value?.contains(active))) {
    event.preventDefault()
    first.focus()
  }
}

function releaseFocus() {
  document.removeEventListener('keydown', handleKeydown, true)
  if (appRoot && !appWasInert) appRoot.inert = false
  previouslyFocused?.focus()
  previouslyFocused = null
  appRoot = null
}

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      releaseFocus()
      return
    }
    resetDraft()
    previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    appRoot = document.getElementById('app')
    appWasInert = Boolean(appRoot?.inert)
    if (appRoot) appRoot.inert = true
    document.addEventListener('keydown', handleKeydown, true)
    await nextTick()
    dialogRef.value?.querySelector<HTMLElement>('input:not(:disabled)')?.focus()
  },
  { immediate: true },
)

watch(
  () => props.requisition?.id,
  () => {
    if (props.open) resetDraft()
  },
)

onBeforeUnmount(releaseFocus)
</script>

<template>
  <Teleport to="body">
    <div v-if="open && requisition" class="store-return-backdrop" @click.self="requestClose">
      <section
        ref="dialogRef"
        class="store-return-dialog"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="descriptionId"
        :inert="discardPromptOpen || undefined"
      >
        <header class="store-return-head">
          <div>
            <h2 :id="titleId">发起配送退货</h2>
            <p :id="descriptionId">基于已收货叫货单 {{ requisition.id }} 退回 {{ warehouseName }}，提交后等待仓库审核。</p>
          </div>
          <UiButton
            variant="ghost"
            icon-only
            aria-label="关闭配送退货窗口"
            title="关闭"
            :disabled="submitting"
            @click="requestClose"
          >
            <template #icon><X :size="19" /></template>
          </UiButton>
        </header>

        <form class="store-return-form" novalidate @submit.prevent="submit">
          <div class="store-return-body">
            <div class="store-return-route" role="note">
              <span><small>退货部门</small><strong>{{ requisition.storeName }}</strong></span>
              <span aria-hidden="true">→</span>
              <span><small>收货部门</small><strong>{{ warehouseName }}</strong></span>
            </div>

            <div class="store-return-fields">
              <label>
                <span>退货日期</span>
                <input v-model="returnDate" type="date" :disabled="submitting" />
              </label>
              <label>
                <span>退货原因</span>
                <input v-model="reason" maxlength="200" placeholder="例如：包装破损、临期退回" :disabled="submitting" />
              </label>
              <label class="wide">
                <span>备注（选填）</span>
                <textarea v-model="note" maxlength="500" rows="2" placeholder="补充退货说明" :disabled="submitting" />
              </label>
            </div>

            <section class="store-return-lines" aria-labelledby="store-return-lines-title">
              <div class="store-return-lines__head">
                <div>
                  <h3 id="store-return-lines-title">退货物料</h3>
                  <p>可退数量按原单已发数量、历史退货和当前门店库存共同限制。</p>
                </div>
                <button type="button" :disabled="submitting" @click="lines.forEach((line) => { line.quantity = '' })">
                  <RotateCcw :size="15" />清空数量
                </button>
              </div>
              <div class="store-return-line-list">
                <label v-for="line in lines" :key="line.itemId" class="store-return-line">
                  <span>
                    <strong>{{ line.itemName }}</strong>
                    <small>当前可退 {{ quantityText(line.availableQuantity, line.unit) }}</small>
                  </span>
                  <span class="store-return-quantity">
                    <input
                      v-model="line.quantity"
                      type="number"
                      inputmode="decimal"
                      min="0"
                      :max="line.availableQuantity"
                      step="0.01"
                      :disabled="submitting || line.availableQuantity <= 0"
                      :aria-label="`${line.itemName}退货数量`"
                      placeholder="0"
                    />
                    <em>{{ line.unit || '件' }}</em>
                  </span>
                </label>
              </div>
              <p v-if="!hasReturnableLine" class="store-return-empty">该叫货单当前没有可退物料，如有疑问请联系仓库核对库存。</p>
            </section>
          </div>

          <ModalFooter>
            <template #info>提交后将生成配送退货单，仓库审核后办理退货入库。</template>
            <UiButton variant="secondary" type="button" :disabled="submitting" @click="requestClose">取消</UiButton>
            <UiButton variant="primary" type="submit" :loading="submitting">提交配送退货</UiButton>
          </ModalFooter>
        </form>
      </section>
    </div>
  </Teleport>

  <UnsavedChangesDialog
    :open="discardPromptOpen"
    title="放弃本次退货填写？"
    message="关闭后，已填写的退货数量、原因和备注不会保留。"
    discard-label="放弃并关闭"
    keep-label="继续填写"
    @keep-editing="discardPromptOpen = false"
    @discard="discardAndClose"
  />
</template>

<style scoped>
.store-return-backdrop {
  position: fixed;
  z-index: var(--ds-z-modal, 1500);
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(19, 39, 38, .42);
}

.store-return-dialog {
  display: flex;
  width: min(100%, 760px);
  max-height: min(860px, calc(100dvh - 40px));
  overflow: hidden;
  flex-direction: column;
  border: 1px solid var(--ds-line);
  border-radius: 10px;
  background: var(--ds-surface);
  box-shadow: 0 24px 64px rgba(25, 54, 52, .22);
}

.store-return-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: start;
  padding: 20px;
  border-bottom: 1px solid var(--ds-line);
}

.store-return-head h2,
.store-return-lines h3 {
  margin: 0;
  color: var(--ds-text);
}

.store-return-head h2 {
  font-size: 20px;
}

.store-return-head p,
.store-return-lines p {
  margin: 5px 0 0;
  color: var(--ds-muted);
  font-size: 13px;
  line-height: 1.55;
}

.store-return-form {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

.store-return-body {
  display: grid;
  min-height: 0;
  gap: 18px;
  padding: 20px;
  overflow-y: auto;
}

.store-return-route {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  padding: 14px 16px;
  border: 1px solid #cce3df;
  border-radius: 8px;
  background: #f2faf8;
  color: var(--ds-primary-hover);
}

.store-return-route > span:not([aria-hidden]) {
  display: grid;
  gap: 4px;
}

.store-return-route small {
  color: var(--ds-muted);
}

.store-return-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.store-return-fields label {
  display: grid;
  gap: 7px;
  color: var(--ds-secondary);
  font-size: 13px;
  font-weight: 600;
}

.store-return-fields label.wide {
  grid-column: 1 / -1;
}

.store-return-fields input,
.store-return-fields textarea,
.store-return-quantity input {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid var(--ds-control-border, #cbdad8);
  border-radius: 6px;
  background: #fff;
  color: var(--ds-text);
  font: inherit;
}

.store-return-fields input,
.store-return-quantity input {
  height: 42px;
  padding: 0 11px;
}

.store-return-fields textarea {
  min-height: 72px;
  padding: 10px 11px;
  resize: vertical;
}

.store-return-lines {
  display: grid;
  gap: 12px;
}

.store-return-lines__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.store-return-lines__head button {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: #fff;
  color: var(--ds-secondary);
  cursor: pointer;
}

.store-return-line-list {
  display: grid;
  gap: 8px;
}

.store-return-line {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  gap: 16px;
  align-items: center;
  padding: 12px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
}

.store-return-line > span:first-child {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.store-return-line strong {
  color: var(--ds-text);
}

.store-return-line small {
  color: var(--ds-muted);
  font-weight: 400;
}

.store-return-quantity {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.store-return-quantity em {
  min-width: 24px;
  color: var(--ds-secondary);
  font-style: normal;
  text-align: right;
}

.store-return-empty {
  padding: 14px;
  border-radius: 8px;
  background: var(--ds-warning-soft, #fff7e8);
  color: var(--ds-warning, #a56714) !important;
}

@media (max-width: 640px) {
  .store-return-backdrop {
    align-items: end;
    padding: 0;
  }

  .store-return-dialog {
    width: 100%;
    max-height: 94dvh;
    border-radius: 14px 14px 0 0;
  }

  .store-return-head,
  .store-return-body {
    padding: 16px;
  }

  .store-return-fields,
  .store-return-line {
    grid-template-columns: 1fr;
  }

  .store-return-route {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .store-return-route > span[aria-hidden] {
    transform: rotate(90deg);
    justify-self: start;
  }

  .store-return-lines__head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
