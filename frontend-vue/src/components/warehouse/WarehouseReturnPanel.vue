<script setup lang="ts">
import { ref } from 'vue'
import StatusBadge from '../common/StatusBadge.vue'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import type { WarehouseReturnOrder } from '../../api/warehouse'

defineProps<{
  returns: WarehouseReturnOrder[]
  actioningId: string
  downloadingId: string
}>()

const emit = defineEmits<{
  approve: [id: string]
  reject: [id: string]
  receive: [id: string]
  download: [id: string, returnNo: string]
}>()

const expandedId = ref('')

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
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>配送退货单</h3>
        <span>仓库管理员审核门店退货，并在收到退货后入库。</span>
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
          <template v-for="row in returns" :key="row.id">
            <tr>
              <td>
                <b>{{ row.returnNo || row.id }}</b>
                <small>{{ row.reason || row.note || '配送退货' }}</small>
              </td>
              <td>{{ row.returnStoreName || row.returnStoreId }}</td>
              <td>{{ lineText(row) || '-' }}</td>
              <td>{{ Number(row.totalAmount || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</td>
              <td><StatusBadge :label="statusLabel(row.status)" :tone="statusTone(row.status)" /></td>
              <td>{{ row.returnDate || row.createdAt || '-' }}</td>
              <td>
                <div class="row-actions">
                  <button class="mini-button" type="button" @click="expandedId = expandedId === row.id ? '' : row.id">
                    {{ expandedId === row.id ? '收起明细' : '查看详情' }}
                  </button>
                  <button
                    v-if="row.status === 'SUBMITTED'"
                    class="mini-button primary"
                    type="button"
                    :disabled="actioningId === `return-review-${row.id}`"
                    @click="emit('approve', row.id)"
                  >
                    审核通过
                  </button>
                  <button
                    v-if="row.status === 'SUBMITTED'"
                    class="mini-button"
                    type="button"
                    :disabled="actioningId === `return-review-${row.id}`"
                    @click="emit('reject', row.id)"
                  >
                    驳回
                  </button>
                  <button
                    v-if="row.status === 'APPROVED'"
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
            <tr v-if="expandedId === row.id">
              <td colspan="7">
                <div class="detail-panel">
                  <div class="detail-grid">
                    <span>收货部门：{{ row.receiveDepartment || '采购' }}</span>
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
          <tr v-if="!returns.length">
            <td colspan="7" class="empty-cell">暂无配送退货单。</td>
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
