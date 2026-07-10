<script setup lang="ts">
import type { TodoStat } from '../../stores/todos'

defineProps<{
  stats: TodoStat[]
}>()
</script>

<template>
  <div class="todo-stat-grid">
    <div
      v-for="stat in stats"
      :key="stat.label"
      class="todo-stat-card"
      :class="stat.tone || 'muted'"
    >
      <span>{{ stat.label }}</span>
      <b>{{ stat.value }}</b>
    </div>
  </div>
</template>

<style scoped>
.todo-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.todo-stat-card {
  min-height: 98px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.todo-stat-card span {
  display: block;
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
}

.todo-stat-card b {
  display: block;
  margin-top: 9px;
  color: var(--ink);
  font-size: 30px;
  line-height: 1;
}

.todo-stat-card.primary {
  border: 0;
  background: linear-gradient(135deg, #ff9844 0%, #ee7e3e 45%, #493025 100%);
}

.todo-stat-card.primary span,
.todo-stat-card.primary b {
  color: #fff;
}

.todo-stat-card.warn {
  border-left: 4px solid var(--warn);
}

.todo-stat-card.bad {
  border-left: 4px solid var(--bad);
}

.todo-stat-card.info {
  border-left: 4px solid var(--rx);
}

.todo-stat-card.muted {
  background: #fafbfc;
}

@media (max-width: 900px) {
  .todo-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .todo-stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
