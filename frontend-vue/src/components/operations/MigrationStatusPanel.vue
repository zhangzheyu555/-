<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { LegacyKvPreview, MigrationStatus } from '../../api/operations'

defineProps<{
  status: MigrationStatus | null
  legacyPreview: LegacyKvPreview | null
}>()

function stateLabel(value?: string) {
  const map: Record<string, string> = {
    PRESENT: '已迁移',
    MISSING: '待迁移',
    READY: '可迁移',
    ERROR: '异常',
  }
  return map[value || ''] || value || '待检查'
}

function tone(label: string) {
  if (label === '已迁移') return 'ok'
  if (label === '异常') return 'bad'
  return 'warn'
}
</script>

<template>
  <section class="content-card">
    <div class="table-heading">
      <div>
        <h3>迁移状态</h3>
      </div>
    </div>
    <div class="migration-summary">
      <div>
        <span>业务项总数</span>
        <b>{{ status?.businessKeyCount || 0 }}</b>
      </div>
      <div>
        <span>已迁移</span>
        <b>{{ status?.presentBusinessKeyCount || 0 }}</b>
      </div>
      <div>
        <span>待迁移</span>
        <b>{{ Math.max(0, (status?.businessKeyCount || 0) - (status?.presentBusinessKeyCount || 0)) }}</b>
      </div>
      <div>
        <span>可自动预览</span>
        <b>{{ legacyPreview?.actionableKeyCount || 0 }}</b>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>迁移项</th>
            <th>目标表</th>
            <th>数据大小</th>
            <th>状态</th>
            <th>建议动作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in legacyPreview?.items || status?.legacyBusinessKeys || []" :key="item.key">
            <td><b>{{ item.key }}</b></td>
            <td>{{ item.targetTable || '-' }}</td>
            <td>{{ Number(item.valueBytes || 0).toLocaleString('zh-CN') }} 字节</td>
            <td><StatusBadge :label="stateLabel('plannedAction' in item ? item.plannedAction : item.migrationState)" :tone="tone(stateLabel('plannedAction' in item ? item.plannedAction : item.migrationState))" /></td>
            <td>{{ 'automaticMigrationReady' in item && item.automaticMigrationReady ? '可在确认后迁移' : '查看详情后处理' }}</td>
          </tr>
          <tr v-if="!(legacyPreview?.items?.length || status?.legacyBusinessKeys?.length)">
            <td colspan="5" class="empty-cell">当前没有迁移预览数据。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.migration-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.migration-summary div {
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}

.migration-summary span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.migration-summary b {
  display: block;
  margin-top: 5px;
  font-size: 22px;
}

@media (max-width: 800px) {
  .migration-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
