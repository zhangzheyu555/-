<script setup lang="ts">
import { AlertTriangle, Database, Send, UserRoundCog } from 'lucide-vue-next'

defineProps<{
  dataIssueCount: number
  importTodoCount: number
  platformIssueCount: number
  escalatedCount: number
}>()
</script>

<template>
  <div class="operations-stat-grid">
    <div class="metric-card">
      <span>数据异常</span>
      <b>{{ dataIssueCount }}</b>
      <AlertTriangle :size="18" />
    </div>
    <div class="metric-card">
      <span>待导入数据</span>
      <b>{{ importTodoCount }}</b>
      <Database :size="18" />
    </div>
    <div class="metric-card">
      <span>平台账号异常</span>
      <b>{{ platformIssueCount }}</b>
      <UserRoundCog :size="18" />
    </div>
    <div class="metric-card">
      <span>已上报老板</span>
      <b>{{ escalatedCount }}</b>
      <Send :size="18" />
    </div>
  </div>
</template>

<style scoped>
.operations-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-card {
  position: relative;
}

.metric-card svg {
  position: absolute;
  right: 14px;
  bottom: 14px;
  opacity: 0.45;
}

@media (max-width: 900px) {
  .operations-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .operations-stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
