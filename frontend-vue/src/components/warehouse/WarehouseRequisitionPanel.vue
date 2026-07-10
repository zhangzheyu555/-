<script setup lang="ts">
import { ref } from 'vue'
import StatusBadge from '../common/StatusBadge.vue'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import type { WarehouseRequisition } from '../../api/warehouse'

defineProps<{
  requisitions: WarehouseRequisition[]
  actioningId: string
  downloadingId: string
}>()

const emit = defineEmits<{
  approve: [id: string]
  reject: [id: string]
  ship: [id: string]
  downloadDelivery: [id: string]
}>()

const expandedId = ref('')

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ''}`
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    SUBMITTED: '待仓库审核',
    APPROVED: '待仓库发货',
    SHIPPED: '待门店收货',
    RECEIVED: '门店已收货',
    REJECTED: '已驳回',
  }
  return map[status] || '待处理'
}

function statusTone(status: string) {
  if (status === 'SUBMITTED' || status === 'APPROVED') return 'warn'
  if (status === 'SHIPPED') return 'info'
  if (status === 'RECEIVED') return 'ok'
  if (status === 'REJECTED') return 'bad'
  return 'muted'
}

function lineText(row: WarehouseRequisition) {
  return row.lines
    .map((line) => `${line.itemName} ${qty(line.approvedQuantity || line.requestedQuantity, line.unit)}`)
    .join('，')
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>门店叫货待处理</h3>
        <span>仓库管理员只处理待审核和待发货；已发货转为店长待收货。</span>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>叫货单</th>
            <th>门店</th>
            <th>商品明细</th>
            <th>金额</th>
            <th>状态</th>
            <th>提交时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in requisitions" :key="row.id">
            <tr>
              <td>
                <b>{{ row.id }}</b>
                <small>{{ row.note || '门店叫货' }}</small>
              </td>
              <td>{{ row.storeName || row.storeId }}</td>
              <td>{{ lineText(row) }}</td>
              <td>{{ Number(row.totalAmount || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</td>
              <td><StatusBadge :label="statusLabel(row.status)" :tone="statusTone(row.status)" /></td>
              <td>{{ row.submittedAt || '-' }}</td>
              <td>
                <div class="row-actions">
                  <button class="mini-button" type="button" @click="expandedId = expandedId === row.id ? '' : row.id">
                    {{ expandedId === row.id ? '收起明细' : '查看明细' }}
                  </button>
                  <button
                    v-if="row.status === 'SUBMITTED'"
                    class="mini-button primary"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="emit('approve', row.id)"
                  >
                    审核通过
                  </button>
                  <button
                    v-if="row.status === 'APPROVED'"
                    class="mini-button primary"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="emit('ship', row.id)"
                  >
                    发货出库
                  </button>
                  <button
                    v-if="row.status === 'SUBMITTED' || row.status === 'APPROVED'"
                    class="mini-button"
                    type="button"
                    :disabled="actioningId === row.id"
                    @click="emit('reject', row.id)"
                  >
                    驳回
                  </button>
                  <WarehousePrintButtons
                    v-if="row.status === 'SHIPPED' || row.status === 'RECEIVED'"
                    label="下载出库单"
                    :disabled="downloadingId.includes(row.id)"
                    @download="emit('downloadDelivery', row.id)"
                  />
                </div>
              </td>
            </tr>
            <tr v-if="expandedId === row.id">
              <td colspan="7">
                <div class="detail-panel">
                  <div class="detail-grid">
                    <span>审核时间：{{ row.reviewedAt || '-' }}</span>
                    <span>发货时间：{{ row.shippedAt || '-' }}</span>
                    <span>收货时间：{{ row.receivedAt || '-' }}</span>
                    <span>备注：{{ row.note || '-' }}</span>
                  </div>
                  <div class="detail-lines">
                    <div v-for="line in row.lines" :key="line.id || line.itemId" class="detail-line">
                      <b>{{ line.itemName }}</b>
                      <span>申请：{{ qty(line.requestedQuantity, line.unit) }}</span>
                      <span>通过：{{ qty(line.approvedQuantity || line.requestedQuantity, line.unit) }}</span>
                      <span>已发：{{ qty(line.shippedQuantity || 0, line.unit) }}</span>
                      <span v-if="line.note">备注：{{ line.note }}</span>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </template>
          <tr v-if="!requisitions.length">
            <td colspan="7" class="empty-cell">当前没有门店叫货单。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
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
</style>
