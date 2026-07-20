<script setup lang="ts">
import { AlertTriangle, ArrowLeftRight, ClipboardList, ShieldAlert, WalletCards } from 'lucide-vue-next'

defineProps<{
  stockValue: number
  lowStockCount: number
  expiringCount: number
  pendingTransferCount: number
  pendingRequisitionCount: number
}>()

function money(value: number) {
  return Number(value || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 })
}
</script>

<template>
  <div class="warehouse-stat-grid">
    <div class="warehouse-stat">
      <span>库存总值</span>
      <b>{{ money(stockValue) }}</b>
      <WalletCards :size="20" />
    </div>
    <div class="warehouse-stat" :class="{ featured: lowStockCount > 0 }">
      <span>低库存</span>
      <b>{{ lowStockCount }}<small>项</small></b>
      <AlertTriangle :size="20" />
    </div>
    <div class="warehouse-stat" :class="{ featured: lowStockCount <= 0 && expiringCount > 0 }">
      <span>临期风险</span>
      <b>{{ expiringCount }}<small>项</small></b>
      <ShieldAlert :size="20" />
    </div>
    <div class="warehouse-stat" :class="{ featured: pendingTransferCount > 0 }">
      <span>待处理调拨</span>
      <b>{{ pendingTransferCount }}<small>笔</small></b>
      <ArrowLeftRight :size="20" />
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
  border-color: var(--primary, #76bdb8);
  background-color: var(--primary, #76bdb8);
  background-image: none;
  color: #fff;
}

.warehouse-stat span {
  display: block;
  color: #75808e;
  font-size: 13px;
}

.warehouse-stat.featured span {
  color: #fff;
  opacity: 1;
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

@media (max-width: 768px) {
  .warehouse-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .warehouse-stat {
    min-height: 82px;
    padding: 14px;
  }

  .warehouse-stat b {
    margin-top: 9px;
    font-size: 23px;
  }
}
</style>
