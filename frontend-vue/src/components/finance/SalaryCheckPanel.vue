<script setup lang="ts">
import { CheckCircle2, Send } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { FinanceSalaryCheck } from '../../api/finance'

defineProps<{
  checks: FinanceSalaryCheck[]
  actioningId?: string
}>()

defineEmits<{
  checked: [check: FinanceSalaryCheck]
  escalate: [check: FinanceSalaryCheck]
}>()

function money(value?: number) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}
</script>

<template>
  <section class="finance-panel">
    <div class="finance-panel-head">
      <div>
        <h3>工资核对</h3>
      </div>
    </div>

    <div v-if="!checks.length" class="empty-state compact">当前没有工资核对提醒。</div>

    <div v-else class="salary-list">
      <article v-for="check in checks" :key="check.id" class="salary-card">
        <div>
          <div class="salary-title">
            <b>{{ check.employeeName }}</b>
            <span>{{ check.storeName || check.storeId || '未关联门店' }} · {{ check.month }}</span>
          </div>
          <p>{{ check.anomaly }}</p>
          <div class="salary-meta">
            <span>应发工资：{{ money(check.gross) }}</span>
          </div>
        </div>
        <div class="salary-actions">
          <StatusBadge :label="check.status || '待财务核对'" tone="warn" />
          <button
            class="mini-button"
            type="button"
            :disabled="actioningId === `salary-check-${check.id}`"
            @click="$emit('escalate', check)"
          >
            上报老板
            <Send :size="14" />
          </button>
          <button
            class="mini-button primary"
            type="button"
            :disabled="actioningId === `salary-check-${check.id}`"
            @click="$emit('checked', check)"
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

.finance-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.salary-card p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.salary-list {
  display: grid;
  gap: 10px;
}

.salary-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.salary-title {
  margin-bottom: 6px;
}

.salary-title b {
  display: block;
  font-size: 16px;
}

.salary-title span {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}

.salary-meta {
  margin-top: 10px;
  color: var(--muted);
  font-size: 12px;
}

.salary-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

@media (max-width: 760px) {
  .salary-card {
    grid-template-columns: 1fr;
  }

  .salary-actions {
    justify-content: flex-start;
  }
}
</style>
