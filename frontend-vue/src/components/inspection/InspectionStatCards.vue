<script setup lang="ts">
import { computed } from 'vue'
import type { InspectionSummary } from '../../api/inspection'

const props = defineProps<{
  summary: InspectionSummary
}>()

const cards = computed(() => [
  { label: '今日巡店', value: props.summary.todayTaskCount, tone: 'warn' },
  { label: '待复查整改', value: props.summary.reviewCount, tone: 'info' },
  { label: '巡店异常', value: props.summary.issueCount, tone: 'bad' },
  { label: '已上报老板', value: props.summary.escalatedCount, tone: 'muted' },
])
</script>

<template>
  <div class="inspection-stat-grid">
    <div v-for="card in cards" :key="card.label" class="inspection-stat-card" :class="card.tone">
      <span>{{ card.label }}</span>
      <b>{{ card.value }}</b>
    </div>
  </div>
</template>

<style scoped>
.inspection-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.inspection-stat-card {
  min-height: 98px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.inspection-stat-card span {
  display: block;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.inspection-stat-card b {
  display: block;
  margin-top: 9px;
  color: var(--ink);
  font-size: 30px;
  line-height: 1;
}

.inspection-stat-card.warn {
  border-left: 4px solid var(--warn);
}

.inspection-stat-card.bad {
  border-left: 4px solid var(--bad);
}

.inspection-stat-card.info {
  border-left: 4px solid var(--rx);
}

.inspection-stat-card.muted {
  background: #fafbfc;
}

@media (max-width: 900px) {
  .inspection-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .inspection-stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
