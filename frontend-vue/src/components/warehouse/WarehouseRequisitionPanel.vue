<script setup lang="ts">
import { computed, ref } from 'vue'
import { AlertTriangle } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import type { WarehouseItem, WarehouseRequisition, WarehouseRequisitionLine } from '../../api/warehouse'
import type { StoreInfo } from '../../api/operations'

const props = withDefaults(defineProps<{
  requisitions: WarehouseRequisition[]
  items: WarehouseItem[]
  stores?: StoreInfo[]
  actioningId: string
  downloadingId: string
  canManage?: boolean
}>(), {
  stores: () => [],
})

const emit = defineEmits<{
  approve: [id: string]
  fulfillAvailable: [id: string]
  markBackorder: [id: string]
  waitReplenishment: [id: string]
  reject: [id: string]
  ship: [id: string]
  downloadDelivery: [id: string]
}>()

const expandedId = ref('')
const selectedStoreId = ref('')
const selectedSubmissionDate = ref('')

const storeOptions = computed(() => {
  const stores = new Map<string, string>()
  for (const store of props.stores) {
    const storeId = String(store.id || '').trim()
    if (!storeId) continue
    stores.set(storeId, String(store.name || store.code || storeId).trim())
  }
  for (const row of props.requisitions) {
    const storeId = String(row.storeId || '').trim()
    if (!storeId || stores.has(storeId)) continue
    stores.set(storeId, String(row.storeName || storeId).trim())
  }
  return Array.from(stores, ([id, name]) => ({ id, name }))
    .sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'))
})

const filteredRequisitions = computed(() => props.requisitions.filter((row) => {
  if (selectedStoreId.value && String(row.storeId) !== selectedStoreId.value) return false
  if (selectedSubmissionDate.value && submissionDate(row.submittedAt) !== selectedSubmissionDate.value) return false
  return true
}))

const hasActiveFilters = computed(() => Boolean(selectedStoreId.value || selectedSubmissionDate.value))

function submissionDate(value?: string) {
  const match = String(value || '').match(/^(\d{4}-\d{2}-\d{2})/)
  return match?.[1] || ''
}

function clearFilters() {
  selectedStoreId.value = ''
  selectedSubmissionDate.value = ''
}

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ''}`
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    SUBMITTED: '待仓库审核',
    APPROVED: '待仓库发货',
    BACKORDERED: '缺货待处理',
    WAITING_REPLENISHMENT: '待补货',
    PARTIALLY_SHIPPED: '部分发货 / 待补货',
    SHIPPED: '待门店收货',
    RECEIVED: '门店已收货',
    REJECTED: '已驳回',
  }
  return map[status] || '待处理'
}

function statusTone(status: string) {
  if (['SUBMITTED', 'APPROVED', 'BACKORDERED', 'WAITING_REPLENISHMENT'].includes(status)) return 'warn'
  if (['SHIPPED', 'PARTIALLY_SHIPPED'].includes(status)) return 'info'
  if (status === 'RECEIVED') return 'ok'
  if (status === 'REJECTED') return 'bad'
  return 'muted'
}

function lineText(row: WarehouseRequisition) {
  return row.lines
    .map((line) => `${line.itemName} 申请 ${qty(line.requestedQuantity, line.unit)} / 已发 ${qty(line.shippedQuantity, line.unit)}`)
    .join('，')
}

function itemFor(line: WarehouseRequisitionLine) {
  return props.items.find((item) => item.id === line.itemId)
}

function availableQuantity(line: WarehouseRequisitionLine) {
  return Math.max(0, Number(itemFor(line)?.warehouseAvailableQuantity || 0))
}

function remainingQuantity(line: WarehouseRequisitionLine) {
  return Math.max(0, Number(line.requestedQuantity || 0) - Number(line.shippedQuantity || 0))
}

function expectedShipment(line: WarehouseRequisitionLine) {
  return Math.min(remainingQuantity(line), availableQuantity(line))
}

function currentShortageQuantity(line: WarehouseRequisitionLine) {
  return Math.max(0, remainingQuantity(line) - expectedShipment(line))
}

function canShipAvailable(row: WarehouseRequisition) {
  return row.lines.some((line) => expectedShipment(line) > 0)
}

function hasCurrentShortage(row: WarehouseRequisition) {
  return row.lines.some((line) => currentShortageQuantity(line) > 0)
}

function canReview(row: WarehouseRequisition) {
  return ['SUBMITTED', 'BACKORDERED', 'WAITING_REPLENISHMENT'].includes(row.status)
}

function hasShipped(row: WarehouseRequisition) {
  return row.lines.some((line) => Number(line.shippedQuantity || 0) > 0)
}

function shortageSummary(row: WarehouseRequisition) {
  const lines = row.lines.filter((line) => currentShortageQuantity(line) > 0)
  if (!lines.length) return ''
  const details = lines
    .map((line) => `${line.itemName}缺货 ${qty(currentShortageQuantity(line), line.unit)}`)
    .join('，')
  return `${details}；可先按当前库存发货，未发数量转为待补货。`
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>门店叫货待处理</h3>
        <span>库存不足单可部分发货或转待补货，驳回仅用于申请本身不合理。</span>
      </div>
      <div class="requisition-filters" aria-label="叫货单筛选">
        <label class="filter-field">
          <span>叫货门店</span>
          <select v-model="selectedStoreId" aria-label="叫货门店">
            <option value="">全部门店</option>
            <option v-for="store in storeOptions" :key="store.id" :value="store.id">
              {{ store.name }}
            </option>
          </select>
        </label>
        <label class="filter-field">
          <span>提交日期</span>
          <input v-model="selectedSubmissionDate" type="date" aria-label="提交日期" />
        </label>
        <button v-if="hasActiveFilters" class="mini-button" type="button" @click="clearFilters">清空筛选</button>
      </div>
    </div>
    <p v-if="hasActiveFilters" class="filter-summary">
      共 {{ requisitions.length }} 张叫货单，当前显示 {{ filteredRequisitions.length }} 张。
    </p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>叫货单</th>
            <th>门店</th>
            <th>供货仓</th>
            <th>商品明细</th>
            <th>金额</th>
            <th>状态</th>
            <th>提交时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in filteredRequisitions" :key="row.id">
            <tr
              class="requisition-row"
              :class="{ 'shortage-row': hasCurrentShortage(row) && !['RECEIVED', 'REJECTED'].includes(row.status) }"
            >
              <td data-label="叫货单">
                <b>{{ row.id }}</b>
                <small>{{ row.note || '门店叫货' }}</small>
              </td>
              <td data-label="门店">{{ row.storeName || row.storeId }}</td>
              <td data-label="供货仓">{{ row.warehouseName || '系统自动路由' }}</td>
              <td data-label="商品明细">
                {{ lineText(row) }}
                <div v-if="hasCurrentShortage(row) && !['RECEIVED', 'REJECTED'].includes(row.status)" class="shortage-inline">
                  <AlertTriangle :size="14" />
                  <span>{{ shortageSummary(row) }}</span>
                </div>
              </td>
              <td data-label="金额">{{ Number(row.totalAmount || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</td>
              <td data-label="状态"><StatusBadge :label="statusLabel(row.status)" :tone="statusTone(row.status)" /></td>
              <td data-label="提交时间">{{ row.submittedAt || '-' }}</td>
              <td data-label="操作">
                <div class="row-actions">
                  <button class="mini-button" type="button" @click="expandedId = expandedId === row.id ? '' : row.id">
                    {{ expandedId === row.id ? '收起明细' : '查看明细' }}
                  </button>
                  <button
                    v-if="canManage && row.status === 'SUBMITTED' && !hasCurrentShortage(row)"
                    class="mini-button primary"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="emit('approve', row.id)"
                  >
                    审核通过
                  </button>
                  <button
                    v-if="canManage && canReview(row) && canShipAvailable(row) && (row.status !== 'SUBMITTED' || hasCurrentShortage(row))"
                    class="mini-button primary"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="emit('fulfillAvailable', row.id)"
                  >
                    {{ row.status === 'SUBMITTED' ? '按可用库存发货' : '补货后发货' }}
                  </button>
                  <button
                    v-if="canManage && row.status === 'APPROVED'"
                    class="mini-button primary"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="emit('ship', row.id)"
                  >
                    发货出库
                  </button>
                  <button
                    v-if="canManage && canReview(row) && row.status !== 'BACKORDERED'"
                    class="mini-button"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="emit('markBackorder', row.id)"
                  >
                    标记缺货
                  </button>
                  <button
                    v-if="canManage && canReview(row) && row.status !== 'WAITING_REPLENISHMENT'"
                    class="mini-button"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="emit('waitReplenishment', row.id)"
                  >
                    等补货后再发
                  </button>
                  <button
                    v-if="canManage && row.status === 'SUBMITTED' && !hasShipped(row)"
                    class="mini-button"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="emit('reject', row.id)"
                  >
                    驳回
                  </button>
                  <WarehousePrintButtons
                    v-if="['PARTIALLY_SHIPPED', 'SHIPPED', 'RECEIVED', 'BACKORDERED', 'WAITING_REPLENISHMENT'].includes(row.status) && hasShipped(row)"
                    label="下载出库单"
                    :disabled="downloadingId.includes(row.id)"
                    @download="emit('downloadDelivery', row.id)"
                  />
                </div>
              </td>
            </tr>
            <tr v-if="expandedId === row.id" class="requisition-detail-row">
              <td colspan="8">
                <div class="detail-panel">
                  <div v-if="hasCurrentShortage(row) && !['RECEIVED', 'REJECTED'].includes(row.status)" class="shortage-alert">
                    <AlertTriangle :size="16" />
                    <div>
                      <strong>缺货处理</strong>
                      <p>{{ shortageSummary(row) }}</p>
                    </div>
                  </div>
                  <div class="detail-grid">
                    <span>审核时间：{{ row.reviewedAt || '-' }}</span>
                    <span>发货时间：{{ row.shippedAt || '-' }}</span>
                    <span>收货时间：{{ row.receivedAt || '-' }}</span>
                    <span>备注：{{ row.note || '-' }}</span>
                  </div>
                  <div class="detail-lines">
                    <div
                      v-for="line in row.lines"
                      :key="line.id || line.itemId"
                      class="detail-line"
                      :class="{ 'shortage': currentShortageQuantity(line) > 0 }"
                    >
                      <b>{{ line.itemName }}</b>
                      <span>申请：{{ qty(line.requestedQuantity, line.unit) }}</span>
                      <span>待发：{{ qty(remainingQuantity(line), line.unit) }}</span>
                      <span>当前可发：{{ qty(expectedShipment(line), line.unit) }}</span>
                      <span>已发：{{ qty(line.shippedQuantity || 0, line.unit) }}</span>
                      <span>缺货：{{ qty(currentShortageQuantity(line), line.unit) }}</span>
                      <span v-if="currentShortageQuantity(line) > 0" class="shortage-text">
                        本次预计可发 {{ qty(expectedShipment(line), line.unit) }}，缺货 {{ qty(currentShortageQuantity(line), line.unit) }} 转待补货。
                      </span>
                      <span v-if="line.note">备注：{{ line.note }}</span>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </template>
          <tr v-if="!filteredRequisitions.length">
            <td colspan="8" class="empty-cell">
              {{ requisitions.length ? '没有符合筛选条件的叫货单。' : '当前没有门店叫货单。' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.requisition-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 10px;
}

.filter-field {
  display: grid;
  gap: 5px;
  min-width: 160px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
}

.filter-field select,
.filter-field input {
  min-height: 36px;
  padding: 6px 9px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--ink);
  font: inherit;
  font-weight: 500;
}

.filter-summary {
  margin: 10px 0;
  color: var(--muted);
  font-size: 13px;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.shortage-row {
  background: #fff8e6;
}

.shortage-inline {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  background: #fff3cd;
  color: #856404;
  font-size: 12px;
  line-height: 1.5;
}

.shortage-inline span {
  flex: 1;
  min-width: 0;
}

.shortage-alert {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff3cd;
  color: #856404;
  font-size: 13px;
}

.shortage-alert p {
  margin: 2px 0 0;
  opacity: 0.85;
}

.detail-panel {
  display: grid;
  gap: 12px;
  padding: 12px;
  border-radius: 6px;
  background: #f7f8fa;
}

.detail-grid,
.detail-line {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
}

.detail-lines {
  display: grid;
  gap: 8px;
}

.detail-line {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
}

.detail-line.shortage {
  border-color: #e6a817;
  background: #fffdf5;
}

.detail-line b {
  color: var(--ink);
}

.shortage-text {
  grid-column: 1 / -1;
  color: #856404;
  font-size: 12px;
  font-weight: 600;
}

@media (max-width: 900px) {
  .detail-grid,
  .detail-line {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .requisition-filters {
    display: grid;
    grid-template-columns: 1fr;
    width: 100%;
  }

  .filter-field {
    min-width: 0;
  }

  .requisition-filters .mini-button {
    width: 100%;
    min-height: 44px;
  }

  .content-card {
    padding: 0;
    border: 0;
    background: transparent;
    box-shadow: none;
  }

  .table-wrap {
    overflow: visible;
    border: 0;
    border-radius: 0;
  }

  table,
  tbody {
    display: block;
    width: 100%;
    min-width: 0;
  }

  thead {
    display: none;
  }

  .requisition-row {
    display: grid;
    width: 100%;
    margin-top: 10px;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: #fff;
  }

  .requisition-row.shortage-row {
    border-color: #e6a817;
    background: #fffdf5;
  }

  .requisition-row td {
    display: grid;
    grid-template-columns: 82px minmax(0, 1fr);
    gap: 10px;
    min-width: 0;
    padding: 10px 12px;
    border-bottom: 1px solid var(--line);
    overflow-wrap: anywhere;
  }

  .requisition-row td::before {
    content: attr(data-label);
    color: var(--muted);
    font-size: 12px;
    font-weight: 700;
  }

  .requisition-row td:last-child {
    border-bottom: 0;
  }

  .row-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
  }

  .shortage-inline {
    grid-column: 1 / -1;
  }

  .row-actions :deep(.mini-button) {
    width: 100%;
    min-height: 44px;
    justify-content: center;
    white-space: normal;
  }

  .requisition-detail-row {
    display: block;
    margin-bottom: 10px;
  }

  .requisition-detail-row td {
    display: block;
    padding: 0;
    border: 0;
  }

  .detail-panel {
    border: 1px solid var(--line);
    border-top: 0;
    border-radius: 0 0 6px 6px;
  }
}
</style>
