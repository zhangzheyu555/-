<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { WarehouseRequisition } from '../../api/warehouse'

defineProps<{
  requisitions: WarehouseRequisition[]
}>()

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit || ''}`
}

function statusLabel(row: WarehouseRequisition) {
  const map: Record<string, string> = {
    SUBMITTED: '待仓库处理',
    APPROVED: '待仓库发货',
    BACKORDERED: '缺货待处理',
    WAITING_REPLENISHMENT: '待补货',
    PARTIALLY_SHIPPED: '部分发货 / 待补货',
    SHIPPED: '待门店收货',
    RECEIVED: '门店已收货',
    REJECTED: '已驳回',
  }
  return row.statusLabel || map[row.status] || row.status
}

function tone(status: string) {
  if (status === 'RECEIVED') return 'ok'
  if (status === 'REJECTED') return 'bad'
  if (['SHIPPED', 'PARTIALLY_SHIPPED'].includes(status)) return 'info'
  if (['SUBMITTED', 'APPROVED', 'BACKORDERED', 'WAITING_REPLENISHMENT'].includes(status)) return 'warn'
  return 'muted'
}

function lineText(row: WarehouseRequisition) {
  return row.lines.map((line) => `${line.itemName} × ${qty(line.requestedQuantity, line.unit)}`).join('，')
}

function progressText(row: WarehouseRequisition) {
  return row.lines.map((line) => {
    const shipped = Number(line.shippedQuantity || 0)
    const outstanding = Math.max(0, Number(line.requestedQuantity || 0) - shipped)
    if (['BACKORDERED', 'WAITING_REPLENISHMENT', 'PARTIALLY_SHIPPED'].includes(row.status)) {
      return `${line.itemName}：已发 ${qty(shipped, line.unit)}，缺货 ${qty(outstanding, line.unit)}`
    }
    if (row.status === 'SUBMITTED') {
      return `${line.itemName}：已发 ${qty(shipped, line.unit)}，待仓库处理 ${qty(outstanding, line.unit)}`
    }
    if (row.status === 'APPROVED') {
      return `${line.itemName}：已发 ${qty(shipped, line.unit)}，待发 ${qty(outstanding, line.unit)}`
    }
    return `${line.itemName}：已发 ${qty(shipped, line.unit)}，未发 ${qty(outstanding, line.unit)}`
  }).join('；')
}

function actionText(row: WarehouseRequisition) {
  if (['SHIPPED', 'PARTIALLY_SHIPPED'].includes(row.status)) return '请确认本次收货'
  if (['BACKORDERED', 'WAITING_REPLENISHMENT'].includes(row.status)) return '等待仓库补发'
  return '-'
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>我的叫货单</h3>
        <span>刷新后从后端重新读取，不在浏览器保存叫货单。</span>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>单号</th>
            <th>商品</th>
            <th>发货进度</th>
            <th>状态</th>
            <th>仓库说明</th>
            <th>提交时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in requisitions" :key="row.id">
            <td>
              <b>{{ row.id }}</b>
              <small>{{ row.note || '' }}</small>
            </td>
            <td>{{ lineText(row) }}</td>
            <td>{{ progressText(row) }}</td>
            <td><StatusBadge :label="statusLabel(row)" :tone="tone(row.status)" /></td>
            <td>{{ row.note || '-' }}</td>
            <td>{{ row.submittedAt || '-' }}</td>
            <td>{{ actionText(row) }}</td>
          </tr>
          <tr v-if="!requisitions.length">
            <td colspan="7" class="empty-cell">还没有叫货单。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
