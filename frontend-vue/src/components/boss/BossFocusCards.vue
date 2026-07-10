<script setup lang="ts">
import type { BossFocus } from '../../stores/boss'

const props = defineProps<{
  focus: BossFocus
}>()

const cards = [
  { key: 'needsBossActionCount', label: '需要我处理', tone: 'warn' },
  { key: 'highRiskCount', label: '高风险提醒', tone: 'bad' },
  { key: 'roleWorkCount', label: '各岗位处理中', tone: 'info' },
  { key: 'doneCount', label: '已处理复盘', tone: 'muted' },
] as const
</script>

<template>
  <div class="boss-focus-grid">
    <div v-for="card in cards" :key="card.key" class="boss-focus-card" :class="card.tone">
      <span>{{ card.label }}</span>
      <b>{{ props.focus[card.key] }}</b>
    </div>
  </div>
</template>

<style scoped>
.boss-focus-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.boss-focus-card {
  min-height: 100px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.boss-focus-card span {
  display: block;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.boss-focus-card b {
  display: block;
  margin-top: 9px;
  color: var(--ink);
  font-size: 30px;
  line-height: 1;
}

.boss-focus-card.warn {
  border-left: 4px solid var(--warn);
}

.boss-focus-card.bad {
  border-left: 4px solid var(--bad);
}

.boss-focus-card.info {
  border-left: 4px solid var(--rx);
}

.boss-focus-card.muted {
  background: #fafbfc;
}

@media (max-width: 900px) {
  .boss-focus-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .boss-focus-grid {
    grid-template-columns: 1fr;
  }
}
</style>
