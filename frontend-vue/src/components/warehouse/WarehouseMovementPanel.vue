<script setup lang="ts">
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import type { WarehouseStockMovement } from '../../api/warehouse'

defineProps<{
  movements: WarehouseStockMovement[]
  downloadingId: string
}>()

const emit = defineEmits<{
  downloadMovement: [movementId: number, itemName: string, movementType: string]
  downloadDelivery: [requisitionId: string]
}>()

function qty(value: number | undefined) {
  return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })
}

function sourceLabel(row: WarehouseStockMovement) {
  if (row.sourceType === 'REQUISITION') return '门店叫货发货'
  if (row.sourceType === 'RETURN_RECEIVE') return '配送退货入库'
  if (row.sourceType?.includes('TRANSFER')) return '仓间调拨'
  if (row.sourceType?.includes('RECEIVE') || row.sourceType === 'PURCHASE_ORDER') return '采购入库'
  return row.sourceType || '库存流水'
}

function documentLabel(row: WarehouseStockMovement) {
  return row.movementType === 'IN' ? '下载入库单' : '下载流水单'
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>出入库记录</h3>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>商品</th>
            <th>数量变化</th>
            <th>来源仓</th>
            <th>目标仓</th>
            <th>门店</th>
            <th>来源</th>
            <th>批次号</th>
            <th>操作人</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in movements" :key="row.id">
            <td>{{ row.createdAt || '-' }}</td>
            <td>{{ row.itemName }}</td>
            <td :class="Number(row.quantityDelta) < 0 ? 'negative' : 'positive'">{{ qty(row.quantityDelta) }}</td>
            <td>{{ row.sourceWarehouseName || (Number(row.quantityDelta) < 0 ? row.warehouseName : '') || '-' }}</td>
            <td>{{ row.targetWarehouseName || (Number(row.quantityDelta) > 0 ? row.warehouseName : '') || '-' }}</td>
            <td>{{ row.storeName || row.storeId || '-' }}</td>
            <td>{{ sourceLabel(row) }}<small v-if="row.sourceId">{{ row.sourceId }}</small></td>
            <td>{{ row.batchNo || '-' }}</td>
            <td>{{ row.operatorName || '-' }}</td>
            <td>
              <div class="row-actions">
                <WarehousePrintButtons
                  :label="documentLabel(row)"
                  :disabled="downloadingId.includes(`/movements/${row.id}`)"
                  @download="emit('downloadMovement', row.id, row.itemName, row.movementType)"
                />
                <WarehousePrintButtons
                  v-if="row.sourceType === 'REQUISITION' && row.sourceId"
                  label="下载出库单"
                  :disabled="downloadingId.includes(String(row.sourceId))"
                  @download="emit('downloadDelivery', String(row.sourceId))"
                />
              </div>
            </td>
          </tr>
          <tr v-if="!movements.length">
              <td colspan="10" class="empty-cell">暂无出入库流水。</td>
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

.positive {
  color: var(--good);
  font-weight: 900;
}

.negative {
  color: var(--bad);
  font-weight: 900;
}
</style>
