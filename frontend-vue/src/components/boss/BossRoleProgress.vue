<script setup lang="ts">
import { ArrowRight } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { BossRoleProgressItem } from '../../stores/boss'

defineProps<{
  items: BossRoleProgressItem[]
}>()

defineEmits<{
  open: [item: BossRoleProgressItem]
}>()
</script>

<template>
  <section class="boss-panel">
    <div class="boss-panel-head">
      <div>
        <h3>各岗位处理中</h3>
      </div>
    </div>

    <div v-if="!items.length" class="empty-state compact">当前没有岗位处理中事项。</div>

    <div v-else class="role-progress-grid">
      <article v-for="item in items" :key="item.id" class="role-progress-card">
        <div class="role-progress-head">
          <div>
            <b>{{ item.ownerName }}</b>
            <span>{{ item.earliestDueAt ? `最早截止 ${item.earliestDueAt}` : '暂无紧急截止' }}</span>
          </div>
          <StatusBadge :label="item.riskCount ? `${item.riskCount} 条风险` : '处理中'" :tone="item.tone" />
        </div>
        <p>
          {{ item.openCount }} 条处理中，其中风险 {{ item.riskCount }} 条，待处理 {{ item.pendingCount }} 条。
        </p>
        <div class="role-progress-sources">
          <span v-for="source in item.topSources" :key="source">{{ source }}</span>
          <span v-if="!item.topSources.length">业务事项</span>
        </div>
        <button class="mini-button" type="button" @click="$emit('open', item)">
          查看进度来源
          <ArrowRight :size="14" />
        </button>
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

.role-progress-card p {
  margin: 0;
  color: var(--ds-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.role-progress-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.role-progress-card {
  display: grid;
  gap: 12px;
  padding: 15px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
}

.role-progress-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}

.role-progress-head b {
  display: block;
  font-size: 16px;
  font-weight: 700;
}

.role-progress-head span {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 13px;
}

.role-progress-sources {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.role-progress-sources span {
  padding: 5px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: #fafbfc;
  color: var(--muted);
  font-size: 13px;
  font-weight: 600;
}

.role-progress-card .mini-button {
  justify-self: start;
  font-size: 14px;
  font-weight: 600;
}

@media (max-width: 1080px) {
  .role-progress-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .role-progress-grid {
    grid-template-columns: 1fr;
  }
}
</style>
