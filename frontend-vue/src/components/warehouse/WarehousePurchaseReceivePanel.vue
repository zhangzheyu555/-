<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import type { WarehouseItem, WarehouseStockBatch } from '../../api/warehouse'

const props = defineProps<{
  items: WarehouseItem[]
  batches: WarehouseStockBatch[]
  actioningId: string
  downloadingId: string
  mode?: 'receive' | 'records'
  canManage?: boolean
  successMessage?: string
}>()

const emit = defineEmits<{
  receive: [payload: { itemId: number; batchNo: string; receivedDate: string; expiryDate?: string; quantity: number; unitCost: number; note?: string; clientRequestId: string }]
  downloadReceipt: [batchId: number, itemName: string, batchNo: string]
}>()

const form = reactive({
  itemId: 0,
  batchNo: '',
  receivedDate: new Date().toISOString().slice(0, 10),
  expiryDate: '',
  quantity: '',
  unitCost: '',
  note: '',
})

const enabledItems = computed(() => props.items.filter((item) => item.active !== false))
const selectedItem = computed(() => enabledItems.value.find((item) => item.id === Number(form.itemId)))
const clientRequestId = ref('')
const submittedActionId = ref('')

watch(
  () => props.actioningId,
  (actioningId, previousActionId) => {
    if (submittedActionId.value && previousActionId === submittedActionId.value && !actioningId && props.successMessage === '采购到货已入库') {
      resetForm()
    }
  },
)

function submit() {
  const itemId = Number(form.itemId)
  const quantity = Number(form.quantity)
  const unitCost = Number(form.unitCost || 0)
  if (!itemId || !form.batchNo.trim() || quantity <= 0) return
  if (!clientRequestId.value) {
    clientRequestId.value = `stock-${crypto.randomUUID().replace(/-/g, '')}`
  }
  submittedActionId.value = `stock:${clientRequestId.value}`
  emit('receive', {
    itemId,
    batchNo: form.batchNo.trim(),
    receivedDate: form.receivedDate,
    expiryDate: form.expiryDate || undefined,
    quantity,
    unitCost,
    note: form.note.trim() || undefined,
    clientRequestId: clientRequestId.value,
  })
}

function resetForm() {
  form.batchNo = ''
  form.quantity = ''
  form.unitCost = ''
  form.note = ''
  clientRequestId.value = ''
  submittedActionId.value = ''
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
          <h3>采购到货入库</h3>
        </div>
      </div>
      <div class="form-grid">
        <label>
          商品
          <select v-model.number="form.itemId" required>
            <option :value="0" disabled>请选择商品</option>
            <option v-for="item in enabledItems" :key="item.id" :value="item.id">
              {{ item.name }} · 当前 {{ qty(item.stockQuantity, item.unit) }}
            </option>
          </select>
        </label>
        <label>
          批次号
          <input v-model="form.batchNo" required placeholder="例如：B20260709001" />
        </label>
        <label>
          到货日期
          <input v-model="form.receivedDate" type="date" required />
        </label>
        <label>
          到期日期
          <input v-model="form.expiryDate" type="date" />
        </label>
        <label>
          入库数量
          <input v-model="form.quantity" type="number" min="0.01" step="0.01" required placeholder="请输入到货数量" />
        </label>
        <label>
          入库单价
          <input v-model="form.unitCost" type="number" min="0" step="0.01" placeholder="请输入采购单价" />
        </label>
        <label class="wide">
          备注
          <input v-model="form.note" placeholder="供应商 / 采购单号 / 说明" />
        </label>
      </div>
      <button class="primary-button submit-inline" type="submit" :disabled="!selectedItem || !form.batchNo || Number(form.quantity) <= 0 || Boolean(actioningId)">
        {{ actioningId ? '正在入库' : '确认入库' }}
      </button>
    </form>

    <div class="content-card" :class="{ 'records-only': props.mode === 'records' || !props.canManage }">
      <div class="table-heading">
        <div>
          <h3>入库记录</h3>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>商品</th>
              <th>批次</th>
              <th>数量</th>
              <th>单价</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="batch in batches.slice(0, 12)" :key="batch.id">
              <td>{{ batch.receivedDate || batch.createdAt || '-' }}</td>
              <td>{{ batch.itemName }}</td>
              <td>{{ batch.batchNo }}</td>
              <td>{{ qty(batch.quantity, batch.unit) }}</td>
              <td>{{ money(batch.unitCost) }}</td>
              <td>
                <WarehousePrintButtons
                  label="下载入库单"
                  :disabled="downloadingId.includes(`/receipts/${batch.id}`)"
                  @download="emit('downloadReceipt', batch.id, batch.itemName, batch.batchNo)"
                />
              </td>
            </tr>
            <tr v-if="!batches.length">
              <td colspan="6" class="empty-cell">暂无入库记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.receive-grid {
  display: grid;
  grid-template-columns: minmax(360px, 0.9fr) minmax(0, 1.1fr);
  gap: 16px;
  align-items: start;
}

.records-only {
  grid-column: 1 / -1;
}

.wide {
  grid-column: 1 / -1;
}

@media (max-width: 1000px) {
  .receive-grid {
    grid-template-columns: 1fr;
  }
}
</style>
