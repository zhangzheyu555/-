<script setup lang="ts">
import { ArrowRight, CheckCircle2, Send } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { RoleTodoItem } from '../../api/todos'

defineProps<{
  item: RoleTodoItem
  actioningId?: string
}>()

defineEmits<{
  open: [item: RoleTodoItem]
  complete: [item: RoleTodoItem]
  escalate: [item: RoleTodoItem]
}>()

function statusLabel(status: string) {
  const map: Record<string, string> = {
    RED: '红色风险',
    ORANGE: '橙色待处理',
    BLUE: '蓝色提醒',
    RISK: '需要核对',
    PENDING: '待财务处理',
    REMINDER: '财务提醒',
    DONE: '已处理',
  }
  return map[status] || '待财务处理'
}

function statusTone(status: string) {
  if (status === 'RED' || status === 'RISK') return 'bad'
  if (status === 'ORANGE' || status === 'PENDING') return 'warn'
  if (status === 'DONE') return 'ok'
  return 'info'
}

function sourceLabel(item: RoleTodoItem) {
  const text = `${item.id} ${item.title} ${item.summary} ${item.sourceModule} ${item.dataSource}`.toLowerCase()
  if (text.includes('expense') || text.includes('报销')) return '报销审核'
  if (text.includes('salary') || text.includes('工资')) return '工资核对'
  if (text.includes('profit') || text.includes('利润')) return '利润异常'
  if (text.includes('finance-data') || text.includes('data-check') || text.includes('数据')) return '财务数据核对'
  return '财务事项'
}

function isDone(item: RoleTodoItem) {
  return item.status === 'DONE' || item.processStatus?.includes('已处理') || item.processStatus?.includes('已完成')
}
</script>

<template>
  <article class="finance-todo-card">
    <div>
      <div class="finance-todo-title">{{ item.title }}</div>
      <p>{{ item.summary || '暂无说明' }}</p>
      <div class="finance-todo-fields">
        <span><b>来源</b>{{ sourceLabel(item) }}</span>
        <span v-if="item.storeName || item.storeId"><b>门店</b>{{ item.storeName || item.storeId }}</span>
        <span><b>截止</b>{{ item.dueAt || '今天内' }}</span>
      </div>
      <StatusBadge :label="statusLabel(item.status)" :tone="statusTone(item.status)" />
    </div>
    <div class="finance-todo-actions">
      <button class="mini-button" type="button" @click="$emit('open', item)">
        去处理
        <ArrowRight :size="14" />
      </button>
      <button
        v-if="!isDone(item)"
        class="mini-button"
        type="button"
        :disabled="actioningId === item.id"
        @click="$emit('escalate', item)"
      >
        上报老板
        <Send :size="14" />
      </button>
      <button
        v-if="!isDone(item)"
        class="mini-button primary"
        type="button"
        :disabled="actioningId === item.id"
        @click="$emit('complete', item)"
      >
        标记已处理
        <CheckCircle2 :size="14" />
      </button>
    </div>
  </article>
</template>

<style scoped>
.finance-todo-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  padding: 15px;
  border: 1px solid var(--line);
  border-left: 4px solid var(--primary);
  border-radius: 12px;
  background: #fff;
}

.finance-todo-title {
  margin-bottom: 6px;
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
}

.finance-todo-card p {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 13.5px;
  line-height: 1.65;
}

.finance-todo-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 10px;
}

.finance-todo-fields span {
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fafbfc;
  color: var(--muted);
  font-size: 12px;
}

.finance-todo-fields b {
  display: block;
  margin-bottom: 2px;
  color: var(--ink);
}

.finance-todo-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

@media (max-width: 860px) {
  .finance-todo-card {
    grid-template-columns: 1fr;
  }

  .finance-todo-actions {
    justify-content: flex-start;
  }

  .finance-todo-fields {
    grid-template-columns: 1fr;
  }
}
</style>
