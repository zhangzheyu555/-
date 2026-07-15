<script setup lang="ts">
import { RefreshCw } from 'lucide-vue-next'
import type { InspectionStandardDiagnostic, InspectionStandardSet } from '../../data/inspectionStandards'

interface InspectionStandardStatsView {
  fullScore: number
  passScore: number
  redlineCount: number
  yellowLineCount: number
}

const props = defineProps<{
  standard: InspectionStandardSet
  stats: InspectionStandardStatsView
  ready: boolean
  hasStandard: boolean
  diagnostics: InspectionStandardDiagnostic[]
  refreshing: boolean
}>()

const emit = defineEmits<{ refresh: [] }>()
</script>

<template>
  <div class="inspection-standard-note" :class="{ muted: props.ready, invalid: props.hasStandard && !props.ready }">
    <div>
      <span v-if="props.ready">{{ props.standard.title }} {{ props.standard.version }} · 105条（物料40 / 卫生47 / 服务18） · {{ props.stats.fullScore }}分（满分200） · 合格线{{ props.stats.passScore }}分 · 红线 {{ props.stats.redlineCount }} 条 · 黄线 {{ props.stats.yellowLineCount }} 条</span>
      <span v-else-if="props.standard.validationError" class="danger">当前标准未通过校验，只能只读查看，不能保存巡检。</span>
      <span v-else>暂无稽核标准</span>
    </div>
    <button class="secondary-button" type="button" :disabled="props.refreshing" @click="emit('refresh')">
      <RefreshCw :size="15" />{{ props.refreshing ? '刷新中...' : '刷新标准' }}
    </button>
  </div>
  <ul v-if="props.hasStandard && !props.ready" class="inspection-standard-diagnostics compact">
    <li v-for="item in props.diagnostics" :key="`${item.categoryCode || item.categoryName}-${item.message}`">
      <b>{{ item.categoryName }}</b><span>{{ item.message }}</span>
    </li>
  </ul>
</template>

<style scoped>
.inspection-standard-note {
  display: flex;
  grid-column: 1 / -1;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 9px 12px;
  border: 1px solid rgba(215, 131, 34, 0.24);
  border-radius: 10px;
  background: #fff7ed;
  color: var(--warn);
  font-size: 13px;
}

.inspection-standard-note.muted {
  border-color: rgba(30, 158, 106, 0.22);
  background: #eaf8f0;
  color: var(--good);
}

.inspection-standard-note.invalid {
  border-color: rgba(217, 79, 61, 0.32);
  background: var(--ds-danger-soft);
  color: var(--bad);
}

.inspection-standard-note > div { min-width: 0; flex: 1; }
.inspection-standard-note .secondary-button { flex: none; }

.inspection-standard-diagnostics {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.inspection-standard-diagnostics li {
  display: grid;
  gap: 3px;
  padding: 9px 10px;
  border: 1px solid rgba(217, 79, 61, 0.25);
  border-radius: 8px;
  background: #fff;
}

.inspection-standard-diagnostics b { color: var(--bad); font-size: 13px; }
.inspection-standard-diagnostics span { color: var(--ds-secondary); font-size: 12px; line-height: 1.5; }
</style>
