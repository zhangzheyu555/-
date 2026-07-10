<script setup lang="ts">
import { ArrowRight } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { BossRiskGroup } from '../../stores/boss'

defineProps<{
  risks: BossRiskGroup[]
}>()

defineEmits<{
  open: [risk: BossRiskGroup]
}>()
</script>

<template>
  <section class="boss-panel">
    <div class="boss-panel-head">
      <div>
        <h3>高风险提醒</h3>
      </div>
    </div>

    <div v-if="!risks.length" class="empty-state compact">当前没有高风险提醒。</div>

    <div v-else class="boss-risk-grid">
      <article v-for="risk in risks" :key="risk.id" class="boss-risk-card">
        <div class="boss-risk-head">
          <div>
            <b>{{ risk.title }}</b>
            <span>{{ risk.storeName || '全部门店' }}{{ risk.month ? ` · ${risk.month}` : '' }}</span>
          </div>
          <StatusBadge :label="risk.highestRiskLabel" :tone="risk.tone" />
        </div>
        <p>
          {{ risk.storeName || (risk.topStores.length ? risk.topStores.join('、') : '相关门店') }}
          有 {{ risk.count }} 条风险提醒，责任岗位：{{ risk.ownerName }}。
        </p>
        <div class="boss-risk-meta">
          <span>来源：{{ risk.sourceLabel }}</span>
          <span>最早截止：{{ risk.earliestDueAt || '今天内' }}</span>
          <span v-if="risk.topStores.length">Top 门店：{{ risk.topStores.join('、') }}</span>
        </div>
        <button class="mini-button" type="button" @click="$emit('open', risk)">
          查看来源页面
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

.boss-risk-card p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.boss-risk-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.boss-risk-card {
  display: grid;
  gap: 12px;
  padding: 15px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.boss-risk-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.boss-risk-head b {
  display: block;
  font-size: 16px;
}

.boss-risk-head span {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}

.boss-risk-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  color: var(--muted);
  font-size: 12px;
}

.boss-risk-card .mini-button {
  justify-self: start;
}

@media (max-width: 920px) {
  .boss-risk-grid {
    grid-template-columns: 1fr;
  }
}
</style>
