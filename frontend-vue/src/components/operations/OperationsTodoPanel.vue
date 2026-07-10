<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { RoleTodoItem } from '../../api/todos'

defineProps<{
  items: RoleTodoItem[]
  actioningId: string
}>()

const emit = defineEmits<{
  open: [item: RoleTodoItem]
  resolve: [item: RoleTodoItem]
  escalate: [item: RoleTodoItem]
}>()

function statusLabel(item: RoleTodoItem) {
  if (item.escalatedToBoss) return '已上报老板'
  const map: Record<string, string> = {
    RISK: '数据异常',
    PENDING: '待处理',
    DONE: '已处理',
    RED: '红色风险',
    ORANGE: '待处理',
    BLUE: '需要检查',
  }
  return map[item.status] || item.processStatus || '待处理'
}

function tone(item: RoleTodoItem) {
  if (item.status === 'RISK' || item.status === 'RED') return 'bad'
  if (item.status === 'ORANGE' || item.status === 'PENDING') return 'warn'
  if (item.escalatedToBoss) return 'info'
  return 'muted'
}

function clean(text?: string) {
  return (text || '')
    .replace(/legacy_kv/gi, '旧数据')
    .replace(/local.?storage/gi, '旧数据')
    .replace(/sourceModule/gi, '来源')
    .replace(/todo_id/gi, '事项编号')
}
</script>

<template>
  <section class="content-card">
    <div class="table-heading">
      <div>
        <h3>待我处理</h3>
      </div>
    </div>
    <div v-if="!items.length" class="empty-state compact">当前没有运营待处理事项。</div>
    <div v-else class="operations-card-list">
      <article v-for="item in items" :key="item.id" class="operations-todo-card">
        <div class="todo-main">
          <StatusBadge :label="statusLabel(item)" :tone="tone(item)" />
          <h4>{{ clean(item.title) || '运营待处理事项' }}</h4>
          <p>{{ clean(item.summary) || '暂无说明' }}</p>
          <div class="todo-meta">
            <span>来源：{{ clean(item.sourceModule || item.dataSource) || '运营中心' }}</span>
            <span>门店：{{ item.storeName || item.storeId || '全部门店' }}</span>
            <span>月份：{{ item.month || '当前月份' }}</span>
          </div>
        </div>
        <div class="todo-actions">
          <button class="mini-button" type="button" @click="emit('open', item)">查看详情</button>
          <button class="mini-button primary" type="button" :disabled="actioningId === item.id" @click="emit('resolve', item)">标记已处理</button>
          <button class="mini-button" type="button" :disabled="actioningId === item.id" @click="emit('escalate', item)">上报老板</button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.operations-card-list {
  display: grid;
  gap: 12px;
}

.operations-todo-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.todo-main {
  display: grid;
  gap: 7px;
}

.todo-main h4 {
  margin: 0;
  font-size: 16px;
}

.todo-main p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.todo-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.todo-actions {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

@media (max-width: 820px) {
  .operations-todo-card {
    grid-template-columns: 1fr;
  }

  .todo-actions {
    justify-content: flex-start;
  }
}
</style>
