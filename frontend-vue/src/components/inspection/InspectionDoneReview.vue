<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { RoleTodoItem } from '../../api/todos'

defineProps<{
  items: RoleTodoItem[]
}>()

function doneTime(item: RoleTodoItem) {
  return item.updatedAt || item.occurredAt || item.dueAt || '-'
}
</script>

<template>
  <section class="inspection-panel">
    <div class="inspection-panel-head">
      <div>
        <h3>已处理复盘</h3>
      </div>
    </div>

    <div v-if="!items.length" class="empty-state compact">当前没有已处理复盘。</div>

    <div v-else class="inspection-done-list">
      <article v-for="item in items" :key="item.id" class="inspection-done-card">
        <div>
          <b>{{ item.title }}</b>
          <p>{{ item.summary || '事项已处理。' }}</p>
          <span>{{ item.storeName || item.storeId || '全部门店' }} · {{ doneTime(item) }}</span>
        </div>
        <StatusBadge label="已完成" tone="ok" />
      </article>
    </div>
  </section>
</template>

<style scoped>
.inspection-panel,
.inspection-done-list {
  display: grid;
  gap: 12px;
}

.inspection-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.inspection-done-card p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.inspection-done-card {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.inspection-done-card b {
  display: block;
  margin-bottom: 5px;
  font-size: 15px;
}

.inspection-done-card span {
  display: block;
  margin-top: 8px;
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 720px) {
  .inspection-done-card {
    display: grid;
  }
}
</style>
