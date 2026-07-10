<script setup lang="ts">
import { CheckCircle2, Send } from 'lucide-vue-next'
import type { FinanceDataCheck } from '../../api/finance'
import StatusBadge from '../common/StatusBadge.vue'

defineProps<{
  items: FinanceDataCheck[]
  actioningId?: string
}>()

defineEmits<{
  checked: [item: FinanceDataCheck]
  escalate: [item: FinanceDataCheck]
}>()

function dataCheckTone(item: FinanceDataCheck) {
  if (item.status.includes('已')) return 'ok'
  if (item.status.includes('风险') || item.status.includes('异常')) return 'bad'
  return 'warn'
}
</script>

<template>
  <section class="finance-panel">
    <div class="finance-panel-head">
      <div>
        <h3>财务数据核对</h3>
      </div>
    </div>

    <div v-if="!items.length" class="empty-state compact">当前没有需要复查的财务数据。</div>
    <div v-else class="finance-list">
      <article v-for="item in items" :key="item.id" class="data-check-card">
        <div>
          <b>{{ item.issue }}</b>
          <p>{{ item.source }} · {{ item.storeName || item.storeId || '全部门店' }} · {{ item.month || '当前期间' }}</p>
        </div>
        <div class="data-check-side">
          <StatusBadge :label="item.status" :tone="dataCheckTone(item)" />
          <button
            class="mini-button"
            type="button"
            :disabled="actioningId === item.id"
            @click="$emit('escalate', item)"
          >
            上报老板
            <Send :size="14" />
          </button>
          <button
            class="mini-button primary"
            type="button"
            :disabled="actioningId === item.id"
            @click="$emit('checked', item)"
          >
            标记已核对
            <CheckCircle2 :size="14" />
          </button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.finance-panel {
  display: grid;
  gap: 12px;
}

.finance-panel-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.finance-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.finance-list {
  display: grid;
  gap: 10px;
}

.data-check-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.data-check-card b {
  display: block;
  margin-bottom: 4px;
  font-size: 15px;
}

.data-check-card p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.data-check-side {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

@media (max-width: 720px) {
  .data-check-card {
    align-items: flex-start;
    flex-direction: column;
  }

  .data-check-side {
    justify-content: flex-start;
  }
}
</style>
