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
  overflow: hidden;
  border: 1px solid var(--ds-line, #e1ebe9);
  border-radius: 12px;
  background: var(--ds-surface, #fff);
}

.warehouse-stat {
  position: relative;
  min-width: 0;
  min-height: 88px;
  padding: 16px 48px 15px 18px;
  overflow: hidden;
  border-right: 1px solid var(--ds-line, #e1ebe9);
  background: transparent;
}

.warehouse-stat:last-child {
  border-right: 0;
}

.warehouse-stat.featured {
  background: var(--ds-primary-soft, #e9f6f5);
}

.warehouse-stat span {
  display: block;
  overflow: hidden;
  color: var(--ds-muted, #6f817f);
  font-size: 12px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.warehouse-stat.featured span {
  color: var(--ds-primary-hover, #285f5c);
}

.warehouse-stat b {
  display: block;
  margin-top: 10px;
  color: var(--ds-ink, #182424);
  font-size: 26px;
  line-height: 1;
}

.warehouse-stat.featured b {
  color: var(--ds-primary-active, #1f4d4a);
}

.warehouse-stat b small {
  margin-left: 3px;
  font-size: 13px;
  font-weight: 700;
}

.warehouse-stat svg {
  position: absolute;
  top: 50%;
  right: 17px;
  color: var(--ds-primary-hover, #285f5c);
  opacity: 0.5;
  transform: translateY(-50%);
}

@media (max-width: 768px) {
  .warehouse-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .warehouse-stat {
    min-height: 82px;
    padding: 14px;
    border-bottom: 1px solid var(--ds-line, #e1ebe9);
  }

  .warehouse-stat:nth-child(2n) {
    border-right: 0;
  }

  .warehouse-stat:last-child {
    border-bottom: 0;
  }

  .warehouse-stat b {
    margin-top: 9px;
    font-size: 23px;
  }

  .warehouse-stat svg {
    right: 12px;
  }
}
</style>
