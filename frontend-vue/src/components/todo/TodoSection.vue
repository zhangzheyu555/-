<script setup lang="ts">
import TodoReminderCard from './TodoReminderCard.vue'
import type { TodoReminder } from '../../stores/todos'

defineProps<{
  title: string
  emptyText: string
  items: TodoReminder[]
}>()

defineEmits<{
  open: [item: TodoReminder]
  workflow: [item: TodoReminder]
}>()
</script>

<template>
  <section class="todo-section-block">
    <div class="todo-section-head">
      <div>
        <h3>{{ title }}</h3>
      </div>
      <span>{{ items.length }} 条</span>
    </div>
    <div v-if="items.length" class="todo-section-list">
      <TodoReminderCard
        v-for="item in items"
        :key="item.id"
        :item="item"
        @open="$emit('open', item)"
        @workflow="$emit('workflow', item)"
      />
    </div>
    <div v-else class="empty-state compact">{{ emptyText }}</div>
  </section>
</template>

<style scoped>
.todo-section-block {
  display: grid;
  gap: 12px;
}

.todo-section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.todo-section-head h3 {
  margin: 0;
  font-size: 18px;
}

.todo-section-head span {
  color: var(--muted);
  font-size: 13px;
  font-weight: 900;
  white-space: nowrap;
}

.todo-section-list {
  display: grid;
  gap: 12px;
}

@media (max-width: 720px) {
  .todo-section-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
