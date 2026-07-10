<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { WarehouseItem, WarehouseStockBatch } from '../../api/warehouse'

defineProps<{
  item: WarehouseItem
  batches: WarehouseStockBatch[]
}>()

defineEmits<{
  close: []
}>()

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ''}`
}

function batchStatus(batch: WarehouseStockBatch) {
  if (Number(batch.quantity || 0) <= 0) return '已用完'
  if (batch.status === 'EXPIRED') return '过期'
  if (batch.status === 'EXPIRING') return '临期'
  return '正常'
}

function statusTone(label: string) {
  if (label === '正常') return 'ok'
  if (label === '临期') return 'warn'
  if (label === '过期' || label === '已用完') return 'bad'
  return 'muted'
}
</script>

<template>
  <aside class="batch-drawer" aria-label="批次库存明细">
    <div class="batch-drawer-head">
      <div>
        <span>批次库存明细</span>
        <h3>{{ item.name }}</h3>
      </div>
      <button class="mini-button" type="button" @click="$emit('close')">关闭</button>
    </div>
    <div v-if="!batches.length" class="empty-state compact">暂无批次库存。</div>
    <div v-else class="batch-drawer-list">
      <div v-for="batch in batches" :key="batch.id" class="batch-card">
        <div>
          <b>{{ batch.batchNo }}</b>
          <small>入库日期：{{ batch.receivedDate || '-' }} · 到期日期：{{ batch.expiryDate || '-' }}</small>
        </div>
        <StatusBadge :label="batchStatus(batch)" :tone="statusTone(batchStatus(batch))" />
        <dl>
          <div>
            <dt>当前剩余</dt>
            <dd>{{ qty(batch.quantity, batch.unit || item.unit) }}</dd>
          </div>
          <div>
            <dt>入库单价</dt>
            <dd>{{ Number(batch.unitCost || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</dd>
          </div>
          <div>
            <dt>备注</dt>
            <dd>{{ batch.note || '-' }}</dd>
          </div>
        </dl>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.batch-drawer {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid rgba(238, 126, 62, 0.22);
  border-radius: 14px;
  background: var(--primary-soft);
}

.batch-drawer-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.batch-drawer-head span {
  color: var(--primary);
  font-size: 13px;
  font-weight: 900;
}

.batch-drawer-head h3 {
  margin: 3px 0 0;
  font-size: 18px;
}

.batch-drawer-list {
  display: grid;
  gap: 10px;
}

.batch-card {
  display: grid;
  gap: 10px;
  padding: 13px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.batch-card small {
  color: var(--muted);
}

dl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin: 0;
}

dt {
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

dd {
  margin: 3px 0 0;
  font-weight: 900;
}

@media (max-width: 780px) {
  dl {
    grid-template-columns: 1fr;
  }
}
</style>
