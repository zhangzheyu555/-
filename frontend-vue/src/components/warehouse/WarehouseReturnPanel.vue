<script setup lang="ts">
import { computed, ref } from 'vue'
import StatusBadge from '../common/StatusBadge.vue'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import type { WarehouseReturnOrder } from '../../api/warehouse'

const props = withDefaults(defineProps<{
  returns: WarehouseReturnOrder[]
  actioningId: string
  downloadingId: string
  canManage?: boolean
  refreshing?: boolean
}>(), {
  canManage: false,
  refreshing: false,
})

const emit = defineEmits<{
  approve: [id: string]
  reject: [id: string]
  receive: [id: string]
  download: [id: string, returnNo: string]
  refresh: []
}>()

const expandedId = ref('')
const pendingCount = computed(() => props.returns.filter((row) => (
  ['SUBMITTED', 'APPROVED'].includes(row.status)
)).length)
const orderedReturns = computed(() => [...props.returns].sort((left, right) => {
  const priority = (status: string) => (
    status === 'SUBMITTED' ? 0 : status === 'APPROVED' ? 1 : 2
  )
  const statusOrder = priority(left.status) - priority(right.status)
  if (statusOrder !== 0) return statusOrder
  return String(right.createdAt || right.returnDate || '')
    .localeCompare(String(left.createdAt || left.returnDate || ''))
}))

function statusLabel(status: string) {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    SUBMITTED: '待仓库审核',
    APPROVED: '待退货入库',
    CHECKED: '已核对',
    RECEIVED: '仓库已收货',
    REJECTED: '已驳回',
    CANCELLED: '已作废',
  }
  return map[status] || '待处理'
}

function statusTone(status: string) {
  if (status === 'SUBMITTED' || status === 'APPROVED') return 'warn'
  if (status === 'RECEIVED' || status === 'CHECKED') return 'ok'
  if (status === 'REJECTED' || status === 'CANCELLED') return 'bad'
  return 'muted'
}

function lineText(row: WarehouseReturnOrder) {
  return row.lines.map((line) => `${line.itemName} ${Number(line.quantity || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${line.unit || ''}`).join('，')
}
</script>

<template>
  <div class="content-card warehouse-return-panel">
    <div class="table-heading">
      <div>
        <h3>配送退货单</h3>
        <span>店长提交后直接进入当前供货仓；仓库先审核，收到商品后再确认入库。</span>
      </div>
      <div class="return-heading-actions">
        <span class="return-count" :class="{ pending: pendingCount > 0 }">
          {{ pendingCount > 0 ? `${pendingCount} 单待处理` : '暂无待处理' }}
        </span>
        <button
          class="mini-button"
          type="button"
          :disabled="refreshing"
          @click="emit('refresh')"
        >
          {{ refreshing ? '刷新中…' : '刷新退货单' }}
        </button>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>单据号</th>
            <th>退货门店</th>
            <th>商品明细</th>
            <th>金额</th>
            <th>状态</th>
            <th>日期</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in orderedReturns" :key="row.id">
            <tr class="return-row">
              <td data-label="单据号">
                <b>{{ row.returnNo || row.id }}</b>
                <small>{{ row.reason || row.note || '配送退货' }}</small>
              </td>
              <td data-label="退货门店">{{ row.returnStoreName || row.returnStoreId }}</td>
              <td data-label="商品明细">{{ lineText(row) || '-' }}</td>
              <td data-label="金额">{{ Number(row.totalAmount || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</td>
              <td data-label="状态"><StatusBadge :label="statusLabel(row.status)" :tone="statusTone(row.status)" /></td>
              <td data-label="日期">{{ row.returnDate || row.createdAt || '-' }}</td>
              <td data-label="操作">
                <div class="row-actions">
                  <button class="mini-button" type="button" @click="expandedId = expandedId === row.id ? '' : row.id">
                    {{ expandedId === row.id ? '收起明细' : '查看详情' }}
                  </button>
                  <button
                    v-if="canManage && row.status === 'SUBMITTED'"
                    class="mini-button primary"
                    type="button"
                    :disabled="actioningId === `return-review-${row.id}`"
                    @click="emit('approve', row.id)"
                  >
                    审核通过
                  </button>
                  <button
                    v-if="canManage && row.status === 'SUBMITTED'"
                    class="mini-button"
                    type="button"
                    :disabled="actioningId === `return-review-${row.id}`"
                    @click="emit('reject', row.id)"
                  >
                    驳回
                  </button>
                  <button
                    v-if="canManage && row.status === 'APPROVED'"
                    class="mini-button primary"
                    type="button"
                    :disabled="actioningId === `return-receive-${row.id}`"
                    @click="emit('receive', row.id)"
                  >
                    确认退货入库
                  </button>
                  <WarehousePrintButtons
                    label="下载退货单"
                    :disabled="downloadingId.includes(row.id)"
                    @download="emit('download', row.id, row.returnNo || row.id)"
                  />
                </div>
              </td>
            </tr>
            <tr v-if="expandedId === row.id" class="return-detail-row">
              <td colspan="7">
                <div class="detail-panel">
                  <div class="detail-grid">
                    <span>收货仓：{{ row.receiveWarehouseName || '-' }}</span>
                    <span>经手人：{{ row.handledBy || row.createdBy || '-' }}</span>
                    <span>审核人：{{ row.reviewedBy || '-' }}</span>
                    <span>核对人：{{ row.checkedBy || '-' }}</span>
                    <span>来源叫货单：{{ row.sourceRequisitionId || '-' }}</span>
                    <span>备注：{{ row.note || '-' }}</span>
                  </div>
                  <div class="detail-lines">
                    <div v-for="line in row.lines" :key="line.id" class="detail-line">
                      <b>{{ line.itemName }}</b>
                      <span>数量：{{ Number(line.quantity || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) }}{{ line.unit || '' }}</span>
                      <span>单价：{{ Number(line.unitPrice || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</span>
                      <span>退货价：{{ Number(line.returnPrice || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</span>
                      <span>小计：{{ Number(line.amount || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</span>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </template>
          <tr v-if="!orderedReturns.length">
            <td colspan="7" class="empty-cell">暂无配送退货单。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.return-heading-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.return-count {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  padding: 0 10px;
  border-radius: 999px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  font-size: 12px;
  font-weight: 800;
}

.return-count.pending {
  background: #fff4df;
  color: #9b5a00;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.detail-panel {
  display: grid;
  gap: 12px;
  padding: 12px;
  border-radius: 12px;
  background: #f7f8fa;
}

.detail-grid,
.detail-line {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
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
  border-radius: 10px;
  background: #fff;
}

.detail-line b {
  color: var(--ink);
}

@media (max-width: 900px) {
  .detail-grid,
  .detail-line {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .warehouse-return-panel {
    padding: 14px;
  }

  .table-heading,
  .return-heading-actions {
    align-items: stretch;
  }

  .return-heading-actions {
    justify-content: flex-start;
  }

  .warehouse-return-panel .table-wrap {
    overflow: visible;
  }

  .warehouse-return-panel table,
  .warehouse-return-panel tbody,
  .warehouse-return-panel tr,
  .warehouse-return-panel td {
    display: block;
    width: 100%;
  }

  .warehouse-return-panel thead {
    display: none;
  }

  .warehouse-return-panel .return-row {
    display: grid;
    gap: 0;
    margin-top: 12px;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: #fff;
  }

  .warehouse-return-panel .return-row td {
    display: grid;
    grid-template-columns: minmax(72px, 88px) minmax(0, 1fr);
    align-items: start;
    gap: 10px;
    padding: 10px 12px;
    border-bottom: 1px solid var(--line);
    overflow-wrap: anywhere;
  }

  .warehouse-return-panel .return-row td:last-child {
    border-bottom: 0;
  }

  .warehouse-return-panel .return-row td::before {
    content: attr(data-label);
    color: var(--muted);
    font-size: 12px;
    font-weight: 700;
  }

  .warehouse-return-panel .row-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .warehouse-return-panel .row-actions > * {
    width: 100%;
  }

  .warehouse-return-panel .return-detail-row td {
    padding: 8px 0 14px;
    border: 0;
  }
}
</style>
