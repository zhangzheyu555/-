<script setup lang="ts">
import { computed, ref } from 'vue'
import { AlertTriangle } from 'lucide-vue-next'
import SearchableSingleSelect from '../common/SearchableSingleSelect.vue'
import StatusBadge from '../common/StatusBadge.vue'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import type {
  WarehouseItem,
  WarehouseRequisition,
  WarehouseRequisitionLine,
  WarehouseRequisitionReviewAction,
} from '../../api/warehouse'
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
  approve: [action: WarehouseRequisitionReviewAction]
  reject: [id: string]
  ship: [id: string]
  downloadDelivery: [id: string]
}>()

const expandedId = ref('')
const selectedStoreId = ref('')
const selectedSubmissionDate = ref('')
const reviewingId = ref('')
const reviewedPrices = ref<Record<string, string>>({})

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
const searchableStoreOptions = computed(() => storeOptions.value.map((store) => {
  const source = props.stores.find((candidate) => String(candidate.id) === store.id)
  return {
    value: store.id,
    label: store.name,
    description: source
      ? [source.code, source.area || source.regionCode, source.status].filter(Boolean).join(' · ')
      : store.id,
    searchText: source
      ? [source.name, source.code, source.area, source.regionCode, source.status, source.brandName].filter(Boolean).join(' ')
      : `${store.name} ${store.id}`,
  }
}))

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
    RECEIVED: '已完成',
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
    .map((line) => `${line.itemName} 申请 ${qty(line.requestedQuantity, line.unit)} / 已完成 ${qty(line.shippedQuantity, line.unit)}`)
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

function hasCurrentShortage(row: WarehouseRequisition) {
  return row.lines.some((line) => currentShortageQuantity(line) > 0)
}

function canReview(row: WarehouseRequisition) {
  return ['SUBMITTED', 'BACKORDERED', 'WAITING_REPLENISHMENT'].includes(row.status)
}

const reviewingRequisition = computed(() => (
  props.requisitions.find((row) => row.id === reviewingId.value) || null
))

const reviewedPriceInvalid = computed(() => {
  const row = reviewingRequisition.value
  if (!row) return true
  return row.lines.some((line) => {
    const value = String(reviewedPrices.value[String(line.itemId)] ?? '').trim()
    if (!/^\d+(?:\.\d{1,2})?$/.test(value)) return true
    const price = Number(value)
    return !Number.isFinite(price) || price < 0 || price > 999999999999.99
  })
})

const reviewedTotal = computed(() => {
  const row = reviewingRequisition.value
  if (!row) return 0
  return row.lines.reduce((sum, line) => {
    const price = Number(reviewedPrices.value[String(line.itemId)] || 0)
    return sum + Number(line.requestedQuantity || 0) * (Number.isFinite(price) ? price : 0)
  }, 0)
})

function startReview(row: WarehouseRequisition) {
  reviewingId.value = row.id
  reviewedPrices.value = Object.fromEntries(row.lines.map((line) => [
    String(line.itemId),
    Number(line.unitPrice || 0).toFixed(2),
  ]))
  expandedId.value = row.id
}

function cancelReview() {
  reviewingId.value = ''
  reviewedPrices.value = {}
}

function submitReview() {
  const row = reviewingRequisition.value
  if (!row || reviewedPriceInvalid.value || props.actioningId === row.id) return
  const action: WarehouseRequisitionReviewAction = {
    id: row.id,
    lines: row.lines.map((line) => ({
      itemId: line.itemId,
      unitPrice: Number(reviewedPrices.value[String(line.itemId)]),
    })),
  }
  emit('approve', action)
  cancelReview()
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
  return `${details}；仍可整单审核完成，仓库不足部分将扣成负库存，门店库存按审核数量增加。`
}

function projectedWarehouseQuantity(line: WarehouseRequisitionLine) {
  return Number(itemFor(line)?.warehouseAvailableQuantity || 0) - remainingQuantity(line)
}

function currency(value: number) {
  return Number(value || 0).toLocaleString('zh-CN', {
    style: 'currency',
    currency: 'CNY',
  })
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>门店叫货待处理</h3>
        <span>仓库审核时可调整叫货单价；库存不足也直接扣成负库存，并同步增加门店库存。</span>
      </div>
      <div class="requisition-filters" aria-label="叫货单筛选">
        <label class="filter-field">
          <span>叫货门店</span>
          <SearchableSingleSelect
            v-model="selectedStoreId"
            :options="searchableStoreOptions"
            empty-option-label="全部门店"
            empty-value=""
            placeholder="全部门店"
            search-placeholder="搜索叫货门店名称或编号"
            aria-label="叫货门店"
          />
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
                    v-if="canManage && canReview(row)"
                    class="mini-button primary"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="startReview(row)"
                  >
                    {{ row.status === 'SUBMITTED' ? '审核并完成' : '审核并完成剩余数量' }}
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
                  <form
                    v-if="reviewingId === row.id"
                    class="review-price-form"
                    @submit.prevent="submitReview"
                  >
                    <div class="review-price-heading">
                      <div>
                        <strong>审核价格</strong>
                        <p>
                          可修改本次叫货单价。确认后立即完成库存划转；库存不足时仓库库存允许为负数。
                        </p>
                      </div>
                      <span>整单完成</span>
                    </div>
                    <div class="review-price-lines">
                      <label
                        v-for="line in row.lines"
                        :key="`review-price-${line.itemId}`"
                        class="review-price-line"
                      >
                        <span>
                          <b>{{ line.itemName }}</b>
                          <small>申请 {{ qty(line.requestedQuantity, line.unit) }}</small>
                        </span>
                        <span>审核单价（元）</span>
                        <input
                          v-model="reviewedPrices[String(line.itemId)]"
                          type="number"
                          min="0"
                          max="999999999999.99"
                          step="0.01"
                          inputmode="decimal"
                          :aria-label="`${line.itemName}审核单价`"
                          :disabled="actioningId === row.id"
                        />
                      </label>
                    </div>
                    <div class="review-price-summary">
                      <span>审核后整单金额：<b>{{ currency(reviewedTotal) }}</b></span>
                      <span v-if="reviewedPriceInvalid" class="review-price-error">请输入不小于 0、最多两位小数的审核单价。</span>
                    </div>
                    <div class="review-price-actions">
                      <button
                        class="mini-button"
                        type="button"
                        :disabled="actioningId === row.id"
                        @click="cancelReview"
                      >
                        取消
                      </button>
                      <button
                        class="mini-button primary"
                        type="submit"
                        :disabled="reviewedPriceInvalid || actioningId === row.id"
                      >
                        {{ actioningId === row.id ? '处理中...' : '确认审核并完成' }}
                      </button>
                    </div>
                  </form>
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
                      <span>本次审核：{{ qty(remainingQuantity(line), line.unit) }}</span>
                      <span>当前仓库库存：{{ qty(Number(itemFor(line)?.warehouseAvailableQuantity || 0), line.unit) }}</span>
                      <span>审核后仓库库存：{{ qty(projectedWarehouseQuantity(line), line.unit) }}</span>
                      <span>已完成：{{ qty(line.shippedQuantity || 0, line.unit) }}</span>
                      <span>叫货单价：{{ currency(Number(line.unitPrice || 0)) }}</span>
                      <span v-if="currentShortageQuantity(line) > 0" class="shortage-text">
                        库存不足 {{ qty(currentShortageQuantity(line), line.unit) }}，审核后直接记入仓库负库存，不转待补货。
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

.filter-field :deep(.searchable-single-select) {
  width: 100%;
}

.filter-field :deep(.searchable-single-select__control) {
  min-height: 36px;
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

.review-price-form {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid #9bc8c2;
  border-radius: 8px;
  background: #f4fbfa;
}

.review-price-heading,
.review-price-summary,
.review-price-actions,
.review-price-line {
  display: flex;
  align-items: center;
  gap: 12px;
}

.review-price-heading {
  justify-content: space-between;
}

.review-price-heading p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.review-price-heading > span {
  padding: 4px 8px;
  border-radius: 999px;
  background: #dff2ef;
  color: #276b65;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.review-price-lines {
  display: grid;
  gap: 8px;
}

.review-price-line {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 110px 150px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--muted);
  font-size: 13px;
}

.review-price-line > span:first-child {
  display: grid;
  gap: 2px;
}

.review-price-line b {
  color: var(--ink);
}

.review-price-line small {
  color: var(--muted);
}

.review-price-line input {
  width: 100%;
  min-height: 38px;
  padding: 7px 9px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--ink);
  font: inherit;
}

.review-price-summary {
  justify-content: space-between;
  flex-wrap: wrap;
}

.review-price-error {
  color: #b42318;
  font-size: 12px;
}

.review-price-actions {
  justify-content: flex-end;
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

  .review-price-line {
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
