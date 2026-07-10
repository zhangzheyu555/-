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

function statusLabel(item: RoleTodoItem) {
  if (item.processStatus && !['RISK', 'PENDING', 'DONE', 'REMINDER'].includes(item.processStatus)) {
    return item.processStatus
  }
  const map: Record<string, string> = {
    RED: '红色风险',
    ORANGE: '待处理',
    BLUE: '提醒',
    RISK: '巡店异常',
    PENDING: '待处理',
    REMINDER: '提醒',
    DONE: '已完成',
  }
  return map[item.status] || '待处理'
}

function statusTone(status: string) {
  if (status === 'RED' || status === 'RISK') return 'bad'
  if (status === 'ORANGE' || status === 'PENDING') return 'warn'
  if (status === 'DONE') return 'ok'
  return 'info'
}

function sourceLabel(item: RoleTodoItem) {
  const text = `${item.id} ${item.sourceModule} ${item.dataSource} ${item.title}`.toLowerCase()
  if (text.includes('rectification') || text.includes('整改') || text.includes('review') || text.includes('复查')) return '整改复查'
  if (text.includes('task') || text.includes('任务')) return '巡店任务'
  if (text.includes('escalation') || text.includes('上报')) return '上报老板'
  return '督导巡店'
}

function isDone(item: RoleTodoItem) {
  return item.status === 'DONE' || item.processStatus?.includes('已完成') || item.processStatus?.includes('已处理')
}
</script>

<template>
  <article class="inspection-issue-card">
    <div>
      <div class="inspection-issue-title">{{ item.title }}</div>
      <p>{{ item.summary || '暂无说明' }}</p>
      <div class="inspection-issue-fields">
        <span><b>来源</b>{{ sourceLabel(item) }}</span>
        <span v-if="item.storeName || item.storeId"><b>门店</b>{{ item.storeName || item.storeId }}</span>
        <span><b>截止</b>{{ item.dueAt || '今天内' }}</span>
      </div>
      <StatusBadge :label="statusLabel(item)" :tone="statusTone(item.status)" />
    </div>
    <div class="inspection-issue-actions">
      <button class="mini-button" type="button" @click="$emit('open', item)">
        查看详情
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
        标记已完成
        <CheckCircle2 :size="14" />
      </button>
    </div>
  </article>
</template>

<style scoped>
.inspection-issue-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  padding: 15px;
  border: 1px solid var(--line);
  border-left: 4px solid var(--primary);
  border-radius: 12px;
  background: #fff;
}

.inspection-issue-title {
  margin-bottom: 6px;
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
}

.inspection-issue-card p {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 13.5px;
  line-height: 1.65;
}

.inspection-issue-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 10px;
}

.inspection-issue-fields span {
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fafbfc;
  color: var(--muted);
  font-size: 12px;
}

.inspection-issue-fields b {
  display: block;
  margin-bottom: 2px;
  color: var(--ink);
}

.inspection-issue-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

@media (max-width: 860px) {
  .inspection-issue-card {
    grid-template-columns: 1fr;
  }

  .inspection-issue-actions {
    justify-content: flex-start;
  }

  .inspection-issue-fields {
    grid-template-columns: 1fr;
  }
}
</style>
