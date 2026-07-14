<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Minus, Plus, Send, Trash2 } from 'lucide-vue-next'
import type { WarehouseItem } from '../../api/warehouse'

const props = defineProps<{
  items: WarehouseItem[]
  submitting: boolean
  successMessage: string
  initialItemId?: number
}>()

const emit = defineEmits<{
  submit: [payload: { lines: Array<{ itemId: number; requestedQuantity: number; note?: string }>; note?: string; clientRequestId: string }]
}>()

interface DraftLine {
  itemId: number
  requestedQuantity: number
  note: string
}

const selectedItemId = ref(0)
const quantity = ref('')
const lineNote = ref('')
const formNote = ref('')
const lines = reactive<DraftLine[]>([])
const clientRequestId = ref('')
const wasSubmitting = ref(false)
const initializedItemId = ref(0)

const selectedItem = computed(() => props.items.find((item) => item.id === selectedItemId.value))

watch(
  () => props.submitting,
  (submitting) => {
    if (wasSubmitting.value && !submitting && props.successMessage === '叫货单已提交') {
      resetForm()
    }
    wasSubmitting.value = submitting
  },
)

watch(
  [() => props.initialItemId, () => props.items],
  ([initialItemId]) => {
    const itemId = Number(initialItemId || 0)
    if (!itemId || initializedItemId.value === itemId) return
    const item = props.items.find((candidate) => candidate.id === itemId)
    if (!item) return
    initializedItemId.value = itemId
    addItem(item)
  },
  { immediate: true },
)

function addLine() {
  const itemId = Number(selectedItemId.value)
  const requestedQuantity = Number(quantity.value)
  if (!itemId || requestedQuantity <= 0) return
  const existing = lines.find((line) => line.itemId === itemId)
  if (existing) {
    existing.requestedQuantity += requestedQuantity
    if (lineNote.value.trim()) existing.note = lineNote.value.trim()
  } else {
    lines.push({ itemId, requestedQuantity, note: lineNote.value.trim() })
  }
  selectedItemId.value = 0
  quantity.value = ''
  lineNote.value = ''
}

function addItem(item: WarehouseItem) {
  selectedItemId.value = item.id
  quantity.value = '1'
  lineNote.value = ''
  addLine()
}

function changeQuantity(line: DraftLine, delta: number) {
  line.requestedQuantity = Math.max(0.01, Number((line.requestedQuantity + delta).toFixed(2)))
}

function removeLine(index: number) {
  lines.splice(index, 1)
}

function submit() {
  if (!lines.length) return
  if (!clientRequestId.value) {
    clientRequestId.value = requestId()
  }
  emit('submit', {
    lines: lines.map((line) => ({
      itemId: line.itemId,
      requestedQuantity: line.requestedQuantity,
      note: line.note.trim() || undefined,
    })),
    note: formNote.value.trim() || undefined,
    clientRequestId: clientRequestId.value,
  })
}

function resetForm() {
  lines.splice(0, lines.length)
  formNote.value = ''
  clientRequestId.value = ''
}

function requestId() {
  return `req-${crypto.randomUUID().replace(/-/g, '')}`
}

function itemFor(line: DraftLine) {
  return props.items.find((item) => item.id === line.itemId)
}

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${unit ? ` ${unit}` : ''}`
}

defineExpose({ addItem })
</script>

<template>
  <form id="store-requisition-form" class="content-card store-requisition-form" @submit.prevent="submit">
    <div class="table-heading">
      <div>
        <h3>门店叫货</h3>
      </div>
    </div>

    <div class="requisition-add-row">
      <label>
        物料
        <select v-model.number="selectedItemId">
          <option :value="0">请选择物料</option>
          <option v-for="item in items.filter((row) => row.active !== false)" :key="item.id" :value="item.id">
            {{ item.name }} · 供货仓可配 {{ qty(item.warehouseAvailableQuantity, item.stockUnit || item.unit) }}
          </option>
        </select>
      </label>
      <label>
        叫货数量
        <input v-model="quantity" type="number" min="0.01" step="0.01" placeholder="例如 2" />
      </label>
      <label>
        单项备注
        <input v-model="lineNote" maxlength="500" placeholder="可选" />
      </label>
      <button class="mini-button primary add-line-button" type="button" :disabled="!selectedItem || Number(quantity) <= 0" @click="addLine">
        <Plus :size="15" />
        添加
      </button>
    </div>

    <div class="requisition-lines">
      <div v-for="(line, index) in lines" :key="line.itemId" class="requisition-line">
        <div>
          <b>{{ itemFor(line)?.name || '已删除物料' }}</b>
          <small>{{ itemFor(line)?.code }} · 供货仓可配 {{ qty(itemFor(line)?.warehouseAvailableQuantity, itemFor(line)?.stockUnit || itemFor(line)?.unit) }}</small>
        </div>
        <div class="quantity-editor">
          <button class="icon-button" type="button" title="减少数量" @click="changeQuantity(line, -1)"><Minus :size="15" /></button>
          <input v-model.number="line.requestedQuantity" type="number" min="0.01" step="0.01" />
          <button class="icon-button" type="button" title="增加数量" @click="changeQuantity(line, 1)"><Plus :size="15" /></button>
          <span>{{ itemFor(line)?.stockUnit || itemFor(line)?.unit }}</span>
        </div>
        <input v-model="line.note" maxlength="500" placeholder="单项备注" />
        <button class="icon-button danger" type="button" title="删除物料" @click="removeLine(index)"><Trash2 :size="16" /></button>
      </div>
      <div v-if="!lines.length" class="empty-state compact">请先添加需要叫货的物料。</div>
    </div>

    <label class="form-note">
      叫货说明
      <input v-model="formNote" maxlength="1000" placeholder="例如 周末备货" />
    </label>
    <button class="primary-button submit-requisition" type="submit" :disabled="submitting || !lines.length">
      <Send :size="17" />
      {{ submitting ? '正在提交' : '提交叫货' }}
    </button>
  </form>
</template>

<style scoped>
.store-requisition-form {
  display: grid;
  gap: 14px;
}

.requisition-add-row {
  display: grid;
  grid-template-columns: minmax(220px, 1.4fr) minmax(110px, 0.6fr) minmax(170px, 1fr) auto;
  gap: 12px;
  align-items: end;
}

.requisition-add-row label,
.form-note {
  display: grid;
  gap: 6px;
  color: #475569;
  font-size: 13px;
}

select,
input {
  width: 100%;
  min-height: 34px;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 6px 8px;
  background: #fff;
  color: var(--ink);
}

.add-line-button,
.submit-requisition {
  display: inline-flex;
  min-height: 35px;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.requisition-lines {
  overflow: hidden;
  border: 1px solid #e4ecef;
  border-radius: 5px;
}

.requisition-line {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 170px minmax(180px, 1fr) 34px;
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid #edf2f3;
}

.requisition-line:last-child {
  border-bottom: 0;
}

.requisition-line > div:first-child {
  display: grid;
  gap: 3px;
}

.requisition-line small {
  color: var(--muted);
}

.quantity-editor {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 28px auto;
  align-items: center;
  gap: 4px;
}

.quantity-editor span {
  color: var(--muted);
  font-size: 12px;
}

.icon-button {
  display: inline-grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 0;
  border-radius: 4px;
  background: #f4f8f9;
  color: #54777f;
}

.icon-button:hover {
  background: #e0f4f6;
}

.icon-button.danger:hover {
  background: #fff0ef;
  color: var(--bad);
}

.form-note {
  max-width: 620px;
}

.submit-requisition {
  justify-self: end;
  min-width: 150px;
}
</style>
