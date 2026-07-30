<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import SearchableSingleSelect from '../common/SearchableSingleSelect.vue'
import type {
  WarehouseItem,
  WarehousePurchaseOrder,
  WarehousePurchaseOrderCreatePayload,
  WarehousePurchaseOrderReceivePayload,
  WarehouseStockBatch,
  WarehouseSupplier,
} from '../../api/warehouse'

const props = defineProps<{
  items: WarehouseItem[]
  batches: WarehouseStockBatch[]
  suppliers?: WarehouseSupplier[]
  purchaseOrders?: WarehousePurchaseOrder[]
  actioningId: string
  downloadingId: string
  mode?: 'receive' | 'records'
  canManage?: boolean
  successMessage?: string
  warehouseName?: string
}>()

const emit = defineEmits<{
  createOrder: [payload: Omit<WarehousePurchaseOrderCreatePayload, 'warehouseId'>]
  approveOrder: [purchaseOrderId: string]
  receiveOrder: [purchaseOrderId: string, payload: WarehousePurchaseOrderReceivePayload]
  downloadReceipt: [batchId: number, itemName: string, batchNo: string]
}>()

const form = reactive({
  itemId: 0,
  quantity: '',
  unitCost: '',
  note: '',
})

const defaultSupplier = computed(() =>
  (props.suppliers || []).find((supplier) => supplier.active !== false))
const enabledItems = computed(() => props.items.filter((item) => item.active !== false))
const itemOptions = computed(() => enabledItems.value.map((item) => ({
  value: item.id,
  label: item.name,
  description: [
    item.code,
    item.categoryName || item.category,
    `采购单位 ${purchaseUnit(item)}`,
    `库存单位 ${stockUnit(item)}`,
    item.unitConversionText,
    `当前库存 ${qty(item.stockQuantity, item.stockUnit || item.unit)}`,
  ].filter(Boolean).join(' · '),
  searchText: [
    item.name,
    item.code,
    item.categoryName,
    item.category,
    item.purchaseUnit,
    item.stockUnit,
    item.unit,
    item.ingredientUnit,
  ].filter(Boolean).join(' '),
})))
const selectedItem = computed(() => enabledItems.value.find((item) => item.id === Number(form.itemId)))
const selectedPurchaseUnit = computed(() => purchaseUnit(selectedItem.value))
const selectedStockUnit = computed(() => stockUnit(selectedItem.value))
const selectedConversionFactor = computed(() => conversionFactor(selectedItem.value))
const convertedQuantityPreview = computed(() => {
  const quantity = Number(form.quantity)
  const factor = selectedConversionFactor.value
  if (!selectedItem.value || !Number.isFinite(quantity) || quantity <= 0 || !factor) return ''
  if (selectedPurchaseUnit.value === selectedStockUnit.value && factor === 1) return ''
  return `${qty(quantity, selectedPurchaseUnit.value)} → 入库 ${qty(quantity * factor, selectedStockUnit.value)}`
})
const defaultUnitPriceHint = computed(() => {
  if (!selectedItem.value) return '留空时使用物料档案中的默认采购单价'
  return `留空使用默认价 ${money(selectedItem.value.unitPrice)}/${selectedPurchaseUnit.value}`
})
const clientRequestId = ref('')
const submittedActionId = ref('')
type ReceiveDraft = {
  clientRequestId: string
  receivedDate: string
  expiryDate: string
  note: string
  batches: Record<string, string>
}
const receiveDrafts = reactive<Record<string, ReceiveDraft>>({})

watch(
  () => props.actioningId,
  (actioningId, previousActionId) => {
    if (submittedActionId.value && previousActionId === submittedActionId.value && !actioningId && props.successMessage === '采购草稿已创建，等待审批') {
      resetForm()
    }
  },
)

function submit() {
  const itemId = Number(form.itemId)
  const quantity = Number(form.quantity)
  const hasUnitCost = String(form.unitCost).trim() !== ''
  const unitCost = hasUnitCost
    ? Number(form.unitCost)
    : Number(selectedItem.value?.unitPrice)
  if (!itemId || !Number.isFinite(quantity) || quantity <= 0) return
  if (!Number.isFinite(unitCost) || unitCost < 0) return
  if (!clientRequestId.value) {
    clientRequestId.value = `purchase-${crypto.randomUUID().replace(/-/g, '')}`
  }
  submittedActionId.value = `purchase:create:${clientRequestId.value}`
  emit('createOrder', {
    supplierId: defaultSupplier.value?.id,
    note: form.note.trim() || undefined,
    clientRequestId: clientRequestId.value,
    lines: [{
      itemId,
      orderedQuantity: quantity,
      unitCost,
      note: form.note.trim() || undefined,
    }],
  })
}

function resetForm() {
  form.quantity = ''
  form.unitCost = ''
  form.note = ''
  clientRequestId.value = ''
  submittedActionId.value = ''
}

function receiveDraft(order: WarehousePurchaseOrder) {
  if (!receiveDrafts[order.id]) {
    receiveDrafts[order.id] = {
      clientRequestId: `purchase-receive-${crypto.randomUUID().replace(/-/g, '')}`,
      receivedDate: new Date().toISOString().slice(0, 10),
      expiryDate: '',
      note: '',
      batches: Object.fromEntries(order.lines.map((line) => [String(line.itemId), ''])),
    }
  }
  return receiveDrafts[order.id]
}

function submitReceive(order: WarehousePurchaseOrder) {
  const draft = receiveDraft(order)
  if (order.lines.some((line) => !String(draft.batches[String(line.itemId)] || '').trim())) return
  emit('receiveOrder', order.id, {
    clientRequestId: draft.clientRequestId,
    note: draft.note.trim() || undefined,
    lines: order.lines.map((line) => ({
      itemId: line.itemId,
      batchNo: draft.batches[String(line.itemId)].trim(),
      receivedDate: draft.receivedDate,
      expiryDate: draft.expiryDate || undefined,
      quantity: line.orderedQuantity,
      note: draft.note.trim() || undefined,
    })),
  })
}

function money(value: number | undefined) {
  return Number(value || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' })
}

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${unit ? ` ${unit}` : ''}`
}

function purchaseUnit(item?: WarehouseItem) {
  return item?.purchaseUnit?.trim() || item?.unit?.trim() || '件'
}

function stockUnit(item?: WarehouseItem) {
  return item?.stockUnit?.trim() || item?.unit?.trim() || '件'
}

function conversionFactor(item?: WarehouseItem) {
  if (!item) return undefined
  const fromUnit = purchaseUnit(item)
  const toUnit = stockUnit(item)
  if (fromUnit === toUnit) return 1
  const text = String(item.unitConversionText || '').replace(/\s+/g, '')
  const escapedFrom = fromUnit.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const escapedTo = toUnit.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const equation = text.match(new RegExp(`(\\d+(?:\\.\\d+)?)${escapedFrom}[=＝](\\d+(?:\\.\\d+)?)${escapedTo}`))
  if (equation) {
    const left = Number(equation[1])
    const right = Number(equation[2])
    return left > 0 && right > 0 ? right / left : undefined
  }
  const perUnit = text.match(new RegExp(`(\\d+(?:\\.\\d+)?)${escapedTo}(?:[/／]|每)${escapedFrom}`))
  const value = Number(perUnit?.[1])
  return value > 0 ? value : undefined
}

function linePurchaseUnit(line: WarehousePurchaseOrder['lines'][number]) {
  return line.purchaseUnit || line.unit || '件'
}

function lineStockUnit(line: WarehousePurchaseOrder['lines'][number]) {
  return line.stockUnit || line.unit || '件'
}

function showLineConversion(line: WarehousePurchaseOrder['lines'][number]) {
  return line.stockQuantity !== undefined
    && (linePurchaseUnit(line) !== lineStockUnit(line)
      || Number(line.stockQuantity) !== Number(line.orderedQuantity))
}
</script>

<template>
  <div class="receive-grid">
    <form v-if="props.mode !== 'records' && props.canManage" class="content-card receive-form" @submit.prevent="submit">
      <div class="table-heading">
        <div>
          <h3>新建外部采购单</h3>
          <span>采购仓：{{ warehouseName || '荆州总仓' }}；提交后须审批，再按单入库</span>
        </div>
      </div>
      <div class="form-grid">
        <div class="fixed-field">
          <span>供应商</span>
          <div class="fixed-supplier-value" aria-label="供应商">默认供应商</div>
        </div>
        <label class="item-select-field">
          <span>商品</span>
          <SearchableSingleSelect
            :model-value="form.itemId"
            :options="itemOptions"
            :disabled="Boolean(actioningId)"
            :empty-option-label="'请选择商品'"
            :empty-value="0"
            placeholder="请选择商品"
            search-placeholder="搜索商品名称、编码、分类或单位"
            aria-label="采购商品"
            @update:model-value="form.itemId = Number($event)"
          />
        </label>
        <label>
          <span>采购数量</span>
          <input
            v-model="form.quantity"
            type="number"
            min="0.01"
            step="0.01"
            required
            aria-label="采购数量"
            :placeholder="`请输入采购数量（${selectedPurchaseUnit}）`"
          />
          <small v-if="convertedQuantityPreview" class="field-hint conversion-preview">{{ convertedQuantityPreview }}</small>
        </label>
        <label>
          <span>采购单价</span>
          <input
            v-model="form.unitCost"
            type="number"
            min="0"
            step="0.01"
            aria-label="采购单价"
            :placeholder="defaultUnitPriceHint"
          />
          <small class="field-hint">{{ defaultUnitPriceHint }}</small>
        </label>
        <label class="wide">
          备注
          <input v-model="form.note" placeholder="采购单号 / 说明" />
        </label>
      </div>
      <button class="primary-button submit-inline" type="submit" :disabled="!selectedItem || Number(form.quantity) <= 0 || Boolean(actioningId)">
        {{ actioningId ? '正在提交' : '创建采购草稿' }}
      </button>
    </form>

    <div v-if="props.mode !== 'records' && props.canManage" class="content-card purchase-orders">
      <div class="table-heading">
        <div>
          <h3>采购单审批与入库</h3>
          <span>只有荆州总仓可处理，重复点击不会重复入账</span>
        </div>
      </div>
      <div v-if="props.purchaseOrders?.length" class="purchase-order-list">
        <article v-for="order in props.purchaseOrders" :key="order.id" class="purchase-order">
          <header>
            <div>
              <strong>{{ order.id }}</strong>
              <span>{{ order.supplierName || '默认供应商' }} · {{ money(order.totalAmount) }}</span>
            </div>
            <span class="status-pill">{{ order.statusLabel || order.status }}</span>
          </header>
          <div class="order-lines">
            <div v-for="line in order.lines" :key="line.id" class="order-line">
              <div class="order-line-summary">
                <strong>{{ line.itemName }}</strong>
                <span v-if="line.itemCode || line.spec">
                  {{ [line.itemCode, line.spec].filter(Boolean).join(' · ') }}
                </span>
              </div>
              <div class="order-line-amount">
                <span>{{ qty(line.orderedQuantity, linePurchaseUnit(line)) }} × {{ money(line.unitCost) }}</span>
                <small v-if="showLineConversion(line)">
                  入库换算：{{ qty(line.stockQuantity, lineStockUnit(line)) }}
                  <template v-if="line.unitConversionText">（{{ line.unitConversionText }}）</template>
                </small>
              </div>
              <input
                v-if="order.status === 'ORDERED'"
                v-model="receiveDraft(order).batches[String(line.itemId)]"
                :aria-label="`${line.itemName}批次号`"
                placeholder="到货批次号"
              />
            </div>
          </div>
          <div v-if="order.status === 'ORDERED'" class="receive-order-fields">
            <label>
              到货日期
              <input v-model="receiveDraft(order).receivedDate" type="date" />
            </label>
            <label>
              到期日期
              <input v-model="receiveDraft(order).expiryDate" type="date" />
            </label>
            <label>
              入库备注
              <input v-model="receiveDraft(order).note" placeholder="到货核对说明" />
            </label>
          </div>
          <footer>
            <button
              v-if="order.status === 'DRAFT'"
              class="secondary-button"
              type="button"
              :disabled="Boolean(actioningId)"
              @click="emit('approveOrder', order.id)"
            >
              审批采购单
            </button>
            <button
              v-if="order.status === 'ORDERED'"
              class="primary-button"
              type="button"
              :disabled="Boolean(actioningId) || order.lines.some((line) => !receiveDraft(order).batches[String(line.itemId)]?.trim())"
              @click="submitReceive(order)"
            >
              按单确认入库
            </button>
          </footer>
        </article>
      </div>
      <div v-else class="empty-cell">暂无采购单。</div>
    </div>
  </div>
</template>

<style scoped>
.receive-grid {
  display: grid;
  grid-template-columns: minmax(340px, 0.8fr) minmax(0, 1.2fr);
  gap: 16px;
  align-items: start;
}

.receive-form {
  display: grid;
  gap: 14px;
}

.receive-form .submit-inline {
  width: auto;
  margin-top: 0;
  justify-self: start;
}

.records-only {
  grid-column: 1 / -1;
}

.purchase-orders {
  min-width: 0;
}

.purchase-order-list {
  display: grid;
  gap: 10px;
}

.purchase-order {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--color-border, #d7e2e0);
  border-radius: 8px;
}

.purchase-order > header,
.purchase-order > footer,
.order-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.purchase-order > header > div,
.order-lines {
  display: grid;
  gap: 4px;
}

.purchase-order header span,
.order-line {
  color: var(--color-text-secondary, #607573);
  font-size: 13px;
}

.order-line-summary,
.order-line-amount {
  display: grid;
  gap: 3px;
}

.order-line-summary strong {
  color: var(--ink);
}

.order-line-amount {
  justify-items: end;
  text-align: right;
}

.order-line-amount small,
.field-hint {
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
}

.conversion-preview {
  color: var(--primary, #176f68);
}

.order-line input {
  width: min(180px, 42%);
}

.receive-order-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.status-pill {
  flex: none;
  padding: 3px 8px;
  border-radius: 999px;
  background: #e9f4f2;
  color: #276b65;
}

.wide {
  grid-column: 1 / -1;
}

.item-select-field {
  align-content: start;
}

.fixed-field {
  display: grid;
  gap: 7px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.fixed-supplier-value {
  display: flex;
  min-height: 42px;
  align-items: center;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #f7faf9;
  color: var(--ink);
  font-size: 14px;
  font-weight: 600;
}

@media (max-width: 1000px) {
  .receive-grid {
    grid-template-columns: 1fr;
  }

  .receive-order-fields {
    grid-template-columns: 1fr;
  }
}
</style>
