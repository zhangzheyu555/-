<script setup lang="ts">
import { computed } from 'vue'
import type { FinanceSummary } from '../../api/finance'

const props = defineProps<{
  summary: FinanceSummary
}>()

const cards = computed(() => [
  { label: '报销待审核', value: props.summary.pendingExpenseCount, tone: 'warn' },
  { label: '利润异常', value: props.summary.profitRiskCount, tone: 'bad' },
  { label: '工资待核对', value: props.summary.salaryCheckCount, tone: 'info' },
  { label: '已上报老板', value: props.summary.escalatedCount, tone: 'muted' },
])
</script>

<template>
  <div class="finance-stat-grid">
    <div v-for="card in cards" :key="card.label" class="finance-stat-card" :class="card.tone">
      <span>{{ card.label }}</span>
      <b>{{ card.value }}</b>
    </div>
  </div>
</template>

<style scoped>
.finance-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.finance-stat-card {
  min-height: 98px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.finance-stat-card span {
  display: block;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.finance-stat-card b {
  display: block;
  margin-top: 9px;
  color: var(--ink);
  font-size: 30px;
  line-height: 1;
}

.finance-stat-card.warn {
  border-left: 4px solid var(--warn);
}

.finance-stat-card.bad {
  border-left: 4px solid var(--bad);
}

.finance-stat-card.info {
  border-left: 4px solid var(--rx);
}

.finance-stat-card.muted {
  background: #fafbfc;
}

@media (max-width: 900px) {
  .finance-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .finance-stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
