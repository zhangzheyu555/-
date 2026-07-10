<script setup lang="ts">
import { ArrowRight, ListChecks } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { TodoReminder } from '../../stores/todos'

defineProps<{
  item: TodoReminder
}>()

defineEmits<{
  open: [item: TodoReminder]
  workflow: [item: TodoReminder]
}>()
</script>

<template>
  <article class="todo-card" :class="item.tone || 'muted'">
    <div class="todo-card-bar" />
    <div class="todo-card-main">
      <div class="todo-card-title">{{ item.title }}</div>
      <p>{{ item.description }}</p>
      <div class="todo-card-fields">
        <span><b>来源</b>{{ item.sourceLabel }}</span>
        <span v-if="item.storeName"><b>门店</b>{{ item.storeName }}</span>
        <span><b>截止</b>{{ item.deadline || '今天内' }}</span>
      </div>
      <div class="todo-card-meta">
        <StatusBadge :label="item.statusLabel" :tone="item.tone === 'bad' ? 'bad' : item.tone === 'warn' ? 'warn' : item.tone === 'ok' ? 'ok' : 'info'" />
      </div>
    </div>
    <div class="todo-card-action">
      <button
        v-if="item.workflowTodoId"
        class="ghost-button"
        type="button"
        @click="$emit('workflow', item)"
      >
        <ListChecks :size="15" />
        {{ item.workflowStatus === 'COMPLETED' || item.workflowStatus === 'REJECTED' ? '查看记录' : '处理进度' }}
      </button>
      <button class="ghost-button" type="button" @click="$emit('open', item)">
        {{ item.actionLabel }}
        <ArrowRight :size="15" />
      </button>
    </div>
  </article>
</template>

<style scoped>
.todo-card {
  display: grid;
  grid-template-columns: 6px minmax(0, 1fr) auto;
  gap: 14px;
  align-items: stretch;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 10px 28px rgba(22, 26, 34, 0.06);
}

.todo-card-bar {
  background: var(--muted);
}

.todo-card.warn .todo-card-bar {
  background: var(--warn);
}

.todo-card.bad .todo-card-bar {
  background: var(--bad);
}

.todo-card.info .todo-card-bar {
  background: var(--rx);
}

.todo-card.ok .todo-card-bar {
  background: var(--good);
}

.todo-card-main {
  min-width: 0;
  padding: 15px 0;
}

.todo-card-title {
  margin-bottom: 6px;
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
  line-height: 1.35;
}

.todo-card-main p {
  margin: 0;
  color: var(--muted);
  font-size: 13.5px;
  line-height: 1.65;
}

.todo-card-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.todo-card-fields span {
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fafbfc;
  color: var(--muted);
  font-size: 12px;
}

.todo-card-fields b {
  display: block;
  margin-bottom: 2px;
  color: var(--ink);
  font-size: 12px;
}

.todo-card-meta {
  margin-top: 10px;
}

.todo-card-action {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 14px 14px 0;
}

.todo-card-action .ghost-button {
  white-space: nowrap;
}

@media (max-width: 820px) {
  .todo-card {
    grid-template-columns: 6px minmax(0, 1fr);
  }

  .todo-card-action {
    grid-column: 2;
    justify-content: flex-start;
    padding: 0 14px 14px 0;
  }

  .todo-card-fields {
    grid-template-columns: 1fr;
  }
}
</style>
