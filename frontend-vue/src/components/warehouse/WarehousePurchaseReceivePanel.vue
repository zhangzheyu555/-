<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Search } from 'lucide-vue-next'
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
  supplierId: 0,
  itemId: 0,
  quantity: '',
  unitCost: '',
  note: '',
})

const enabledItems = computed(() => props.items.filter((item) => item.active !== false))
const itemSearch = ref('')
const filteredItems = computed(() => {
  const keyword = itemSearch.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return enabledItems.value
  return enabledItems.value.filter((item) => [
    item.name,
    item.code,
    item.unit,
    item.stockUnit,
  ].some((value) => String(value || '').toLocaleLowerCase('zh-CN').includes(keyword)))
})
const selectedItem = computed(() => enabledItems.value.find((item) => item.id === Number(form.itemId)))
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
  const unitCost = Number(form.unitCost || 0)
  if (!itemId || quantity <= 0 || unitCost < 0) return
  if (!clientRequestId.value) {
    clientRequestId.value = `purchase-${crypto.randomUUID().replace(/-/g, '')}`
  }
  submittedActionId.value = `purchase:create:${clientRequestId.value}`
  emit('createOrder', {
    supplierId: Number(form.supplierId) || undefined,
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
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ''}`
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
        <label>
          供应商
          <select v-model.number="form.supplierId">
            <option :value="0">未指定供应商</option>
            <option v-for="supplier in props.suppliers || []" :key="supplier.id" :value="supplier.id">
              {{ supplier.name }}
            </option>
          </select>
        </label>
        <label class="item-select-field">
          <span>商品</span>
          <span class="item-search-control">
            <Search :size="15" aria-hidden="true" />
            <input v-model="itemSearch" type="search" aria-label="搜索商品" placeholder="搜索名称、编码或单位" autocomplete="off" />
          </span>
          <select v-model.number="form.itemId" aria-label="商品" required>
            <option :value="0" disabled>请选择商品</option>
            <option v-for="item in filteredItems" :key="item.id" :value="item.id">
              {{ item.name }} · 当前 {{ qty(item.stockQuantity, item.unit) }}
            </option>
          </select>
          <small v-if="itemSearch" class="item-search-result">
            {{ filteredItems.length ? `找到 ${filteredItems.length} 个商品` : '没有匹配的商品' }}
          </small>
        </label>
        <label>
          采购数量
          <input v-model="form.quantity" type="number" min="0.01" step="0.01" required placeholder="请输入采购数量" />
        </label>
        <label>
          采购单价
          <input v-model="form.unitCost" type="number" min="0" step="0.01" placeholder="请输入采购单价" />
        </label>
        <label class="wide">
          备注
          <input v-model="form.note" placeholder="供应商 / 采购单号 / 说明" />
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
              <span>{{ order.supplierName || '未指定供应商' }} · {{ money(order.totalAmount) }}</span>
            </div>
            <span class="status-pill">{{ order.statusLabel || order.status }}</span>
          </header>
          <div class="order-lines">
            <div v-for="line in order.lines" :key="line.id" class="order-line">
              <span>{{ line.itemName }}</span>
              <span>{{ qty(line.orderedQuantity, line.unit) }} × {{ money(line.unitCost) }}</span>
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

.item-search-control {
  position: relative;
  display: block;
}

.item-search-control svg {
  position: absolute;
  top: 50%;
  left: 9px;
  z-index: 1;
  color: var(--color-text-secondary, #607573);
  pointer-events: none;
  transform: translateY(-50%);
}

.form-grid .item-search-control input {
  padding-left: 31px;
}

.item-search-result {
  color: var(--color-text-secondary, #607573);
  font-size: 12px;
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
