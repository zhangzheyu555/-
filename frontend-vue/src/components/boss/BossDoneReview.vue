<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { BossDoneItem } from '../../stores/boss'

defineProps<{
  items: BossDoneItem[]
}>()
</script>

<template>
  <section class="boss-panel">
    <div class="boss-panel-head">
      <div>
        <h3>已处理复盘</h3>
      </div>
    </div>

    <div v-if="!items.length" class="empty-state compact">当前没有已处理复盘。</div>

    <div v-else class="done-list">
      <article v-for="item in items" :key="item.id" class="done-card">
        <div>
          <b>{{ item.title }}</b>
          <p>{{ item.note }}</p>
          <div class="done-meta">
            <span>来源：{{ item.sourceLabel }}</span>
            <span v-if="item.ownerName">岗位：{{ item.ownerName }}</span>
            <span v-if="item.storeName">门店：{{ item.storeName }}</span>
            <span v-if="item.handledAt">时间：{{ item.handledAt }}</span>
          </div>
        </div>
        <StatusBadge :label="item.actionLabel" tone="ok" />
      </article>
    </div>
  </section>
</template>

<style scoped>
.boss-panel {
  display: grid;
  gap: 12px;
}

.boss-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.done-card p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.done-list {
  display: grid;
  gap: 10px;
}

.done-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.done-card b {
  display: block;
  margin-bottom: 5px;
  font-size: 15px;
}

.done-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 720px) {
  .done-card {
    display: grid;
  }
}
</style>
