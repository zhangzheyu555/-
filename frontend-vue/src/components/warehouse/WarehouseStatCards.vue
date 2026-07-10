<script setup lang="ts">
import { AlertTriangle, ClipboardList, Package, ShieldAlert, WalletCards } from 'lucide-vue-next'

defineProps<{
  itemCount: number
  stockValue: number
  lowStockCount: number
  expiringCount: number
  pendingRequisitionCount: number
}>()

function money(value: number) {
  return Number(value || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 })
}
</script>

<template>
  <div class="warehouse-stat-grid">
    <div class="warehouse-stat featured">
      <span>物料数</span>
      <b>{{ itemCount }}<small>项</small></b>
      <Package :size="20" />
    </div>
    <div class="warehouse-stat">
      <span>库存金额</span>
      <b>{{ money(stockValue) }}</b>
      <WalletCards :size="20" />
    </div>
    <div class="warehouse-stat">
      <span>低库存</span>
      <b>{{ lowStockCount }}<small>项</small></b>
      <AlertTriangle :size="20" />
    </div>
    <div class="warehouse-stat">
      <span>临期风险</span>
      <b>{{ expiringCount }}<small>项</small></b>
      <ShieldAlert :size="20" />
    </div>
    <div class="warehouse-stat">
      <span>待处理叫货</span>
      <b>{{ pendingRequisitionCount }}<small>单</small></b>
      <ClipboardList :size="20" />
    </div>
  </div>
</template>

<style scoped>
.warehouse-stat-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.warehouse-stat {
  position: relative;
  min-height: 96px;
  padding: 17px 16px;
  overflow: hidden;
  border: 1px solid #e5eaed;
  border-radius: 6px;
  background: #fff;
}

.warehouse-stat.featured {
  border-color: transparent;
  background: linear-gradient(135deg, #ff9146, #f67e36 48%, #5b403c);
  color: #fff;
}

.warehouse-stat span {
  display: block;
  color: #75808e;
  font-size: 13px;
}

.warehouse-stat.featured span {
  color: rgba(255, 255, 255, 0.86);
}

.warehouse-stat b {
  display: block;
  margin-top: 12px;
  color: #1e293b;
  font-size: 28px;
  line-height: 1;
}

.warehouse-stat.featured b {
  color: #fff;
}

.warehouse-stat b small {
  margin-left: 3px;
  font-size: 13px;
  font-weight: 700;
}

.warehouse-stat svg {
  position: absolute;
  right: 14px;
  bottom: 14px;
  opacity: 0.3;
}
</style>
