<script setup lang="ts">
import type { WarehouseRequisition } from '../../api/warehouse'

defineProps<{
  requisitions: WarehouseRequisition[]
  receivingId: string
}>()

const emit = defineEmits<{
  receive: [requisitionId: string]
}>()

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit || ''}`
}

function lineText(row: WarehouseRequisition) {
  return row.lines.map((line) => `${line.itemName} × ${qty(line.shippedQuantity || line.approvedQuantity || line.requestedQuantity, line.unit)}`).join('，')
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>待确认收货</h3>
        <span>仓库已发货后，店长在这里确认本店已收到商品。</span>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>单号</th>
            <th>商品</th>
            <th>发货时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in requisitions" :key="row.id">
            <td><b>{{ row.id }}</b></td>
            <td>{{ lineText(row) }}</td>
            <td>{{ row.shippedAt || '-' }}</td>
            <td>
              <button class="mini-button primary" type="button" :disabled="receivingId === row.id" @click="emit('receive', row.id)">
                {{ receivingId === row.id ? '确认中...' : '确认已收货' }}
              </button>
            </td>
          </tr>
          <tr v-if="!requisitions.length">
            <td colspan="4" class="empty-cell">当前没有待确认收货的叫货单。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
