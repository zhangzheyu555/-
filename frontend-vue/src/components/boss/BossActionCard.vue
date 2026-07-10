<script setup lang="ts">
import { ArrowRight, CheckCircle2, ShieldCheck } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { RoleTodoItem } from '../../api/todos'
import { cleanText, sourceLabel, statusLabel } from '../../stores/boss'

defineProps<{
  item: RoleTodoItem
  actioningId?: string
}>()

defineEmits<{
  open: [item: RoleTodoItem]
  resolve: [item: RoleTodoItem]
  close: [item: RoleTodoItem]
}>()

function tone(item: RoleTodoItem) {
  if (item.status === 'RED' || item.status === 'RISK' || item.priority >= 3) return 'bad'
  if (item.status === 'ORANGE' || item.status === 'PENDING' || item.priority === 2) return 'warn'
  if (item.status === 'DONE') return 'ok'
  return 'info'
}

function deadline(value?: string) {
  if (!value) return '今天内'
  if (value.includes('T')) {
    const [date, time] = value.split('T')
    return `${date} ${time.slice(0, 5)}`
  }
  return value
}
</script>

<template>
  <article class="boss-action-card">
    <div class="boss-action-main">
      <div class="boss-action-title">{{ cleanText(item.title || '岗位上报事项') }}</div>
      <p>{{ cleanText(item.summary || '暂无说明') }}</p>
      <div class="boss-action-fields">
        <span><b>来源岗位</b>{{ item.ownerName || '岗位上报' }}</span>
        <span><b>来源模块</b>{{ sourceLabel(item.sourceModule || item.dataSource || '') }}</span>
        <span v-if="item.storeName || item.storeId"><b>门店</b>{{ cleanText(item.storeName || item.storeId || '') }}</span>
        <span><b>截止</b>{{ deadline(item.dueAt) }}</span>
      </div>
      <div class="boss-action-meta">
        <StatusBadge :label="statusLabel(item.status)" :tone="tone(item)" />
        <span v-if="item.escalatedToBoss">已上报老板</span>
      </div>
    </div>
    <div class="boss-action-buttons">
      <button class="mini-button" type="button" @click="$emit('open', item)">
        查看来源
        <ArrowRight :size="14" />
      </button>
      <button
        class="mini-button"
        type="button"
        :disabled="actioningId === item.id"
        @click="$emit('close', item)"
      >
        无影响关闭
        <ShieldCheck :size="14" />
      </button>
      <button
        class="mini-button primary"
        type="button"
        :disabled="actioningId === item.id"
        @click="$emit('resolve', item)"
      >
        处理完成
        <CheckCircle2 :size="14" />
      </button>
    </div>
  </article>
</template>

<style scoped>
.boss-action-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  padding: 15px;
  border: 1px solid var(--line);
  border-left: 4px solid var(--primary);
  border-radius: 12px;
  background: #fff;
}

.boss-action-title {
  margin-bottom: 6px;
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
  line-height: 1.35;
}

.boss-action-main p {
  margin: 0;
  color: var(--muted);
  font-size: 13.5px;
  line-height: 1.65;
}

.boss-action-fields {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.boss-action-fields span {
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fafbfc;
  color: var(--muted);
  font-size: 12px;
}

.boss-action-fields b {
  display: block;
  margin-bottom: 2px;
  color: var(--ink);
}

.boss-action-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  color: var(--muted);
  font-size: 12px;
}

.boss-action-buttons {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

@media (max-width: 980px) {
  .boss-action-card {
    grid-template-columns: 1fr;
  }

  .boss-action-buttons {
    justify-content: flex-start;
  }

  .boss-action-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .boss-action-fields {
    grid-template-columns: 1fr;
  }
}
</style>
