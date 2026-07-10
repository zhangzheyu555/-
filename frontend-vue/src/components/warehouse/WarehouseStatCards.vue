<script setup lang="ts">
import { AlertTriangle, ClipboardList, Package, Truck, Undo2, Warehouse } from 'lucide-vue-next'

defineProps<{
  itemCount: number
  totalStock: number
  lowStockCount: number
  pendingRequisitionCount: number
  pendingReturnCount: number
  todayOutboundCount: number
}>()
</script>

<template>
  <div class="warehouse-stat-grid">
    <div class="metric-card">
      <span>商品总数</span>
      <b>{{ itemCount }}</b>
      <Package :size="18" />
    </div>
    <div class="metric-card">
      <span>当前库存总数</span>
      <b>{{ totalStock.toLocaleString('zh-CN', { maximumFractionDigits: 1 }) }}</b>
      <Warehouse :size="18" />
    </div>
    <div class="metric-card">
      <span>低库存商品</span>
      <b>{{ lowStockCount }}</b>
      <AlertTriangle :size="18" />
    </div>
    <div class="metric-card">
      <span>待处理叫货</span>
      <b>{{ pendingRequisitionCount }}</b>
      <ClipboardList :size="18" />
    </div>
    <div class="metric-card">
      <span>待处理退货</span>
      <b>{{ pendingReturnCount }}</b>
      <Undo2 :size="18" />
    </div>
    <div class="metric-card">
      <span>今日出库单</span>
      <b>{{ todayOutboundCount }}</b>
      <Truck :size="18" />
    </div>
  </div>
</template>

<style scoped>
.warehouse-stat-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}

.metric-card {
  position: relative;
  min-height: 94px;
}

.metric-card svg {
  position: absolute;
  right: 14px;
  bottom: 14px;
  opacity: 0.45;
}

@media (max-width: 1100px) {
  .warehouse-stat-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 680px) {
  .warehouse-stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
