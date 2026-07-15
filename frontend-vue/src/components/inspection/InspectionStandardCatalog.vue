<script setup lang="ts">
import { computed, ref } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import type { InspectionRiskLevel } from '../../api/inspection'
import type {
  InspectionStandardDiagnostic,
  InspectionStandardGroup,
  InspectionStandardSet,
} from '../../data/inspectionStandards'

interface CategoryStat {
  categoryCode: string
  categoryName: string
  actualCount: number
  expectedCount: number
  actualScore: number
  expectedScore: number
}

interface StandardStats {
  redlineCount: number
  yellowLineCount: number
  clauseCount: number
  fullScore: number
  passScore: number
  categoryStats: CategoryStat[]
}

const props = defineProps<{
  standard: InspectionStandardSet
  stats: StandardStats
  hasStandard: boolean
  diagnostics: InspectionStandardDiagnostic[]
  refreshing: boolean
  safeNumber: (value: unknown) => number
  riskLabel: (value: InspectionRiskLevel) => string
}>()

const emit = defineEmits<{
  refresh: []
}>()

const dimension = ref('')
const visibleGroups = computed(() => !dimension.value
  ? props.standard.groups
  : props.standard.groups.filter((group) => group.dim === dimension.value))

function groupScore(group: InspectionStandardGroup) {
  return group.items.reduce((sum, item) => sum + props.safeNumber(item.score), 0)
}

function diagnosticKey(item: InspectionStandardDiagnostic) {
  return (item.categoryCode || item.categoryName) + '-' + item.message
}

function clauseKey(group: InspectionStandardGroup, index: number) {
  const clause = group.items[index]
  return group.dim + '-' + (clause?.code || clause?.item || index) + '-' + index
}

function riskClass(level: InspectionRiskLevel) {
  return 'risk-' + level.toLowerCase()
}
</script>

<template>
  <div class="inspection-standards-view">
    <section class="content-card inspection-standard-summary">
      <div><span>标准版本</span><b>{{ standard.version || '未启用' }}</b></div>
      <div><span>红线项</span><b>{{ stats.redlineCount }} 条</b></div>
      <div><span>黄线项</span><b>{{ stats.yellowLineCount }} 条</b></div>
      <div><span>评分条款</span><b>{{ stats.clauseCount }} 条</b></div>
      <div><span>满分基准</span><b>{{ stats.fullScore }} 分</b></div>
      <div><span>合格线</span><b>{{ stats.passScore }} 分</b></div>
    </section>

    <section v-if="standard.validationError" class="content-card inspection-standard-error" role="alert">
      <div class="inspection-standard-error-head">
        <div>
          <b>标准校验未通过，以下条款仅供核对</b>
          <span>系统不会隐藏原始条款，也不会允许用错误标准保存新巡检。</span>
        </div>
        <button class="secondary-button" type="button" :disabled="refreshing" @click="emit('refresh')">
          <RefreshCw :size="15" />{{ refreshing ? '刷新中...' : '刷新标准' }}
        </button>
      </div>
      <ul class="inspection-standard-diagnostics">
        <li v-for="item in diagnostics" :key="diagnosticKey(item)">
          <b>{{ item.categoryName }}</b><span>{{ item.message }}</span>
        </li>
      </ul>
    </section>

    <section v-if="hasStandard" class="inspection-standard-category-audit" aria-label="标准分类核对">
      <article
        v-for="item in stats.categoryStats"
        :key="item.categoryCode"
        :class="{ invalid: item.actualCount !== item.expectedCount || item.actualScore !== item.expectedScore }"
      >
        <div><b>{{ item.categoryName }}</b><span>{{ item.actualCount }} 条 / {{ item.actualScore }} 分</span></div>
        <small>权威标准：{{ item.expectedCount }} 条 / {{ item.expectedScore }} 分</small>
      </article>
    </section>

    <section v-if="!hasStandard" class="content-card">
      <div class="empty-state">
        <b>暂无稽核标准</b>
        <button class="secondary-button" type="button" :disabled="refreshing" @click="emit('refresh')">
          <RefreshCw :size="15" />{{ refreshing ? '刷新中...' : '刷新标准' }}
        </button>
      </div>
    </section>

    <section v-else class="content-card inspection-standard-groups">
      <div class="inspection-card-title">
        <div>
          <span class="inspection-section-title">完整评分条款</span>
          <h3>按物料、卫生、服务查看</h3>
        </div>
      </div>
      <div class="dimension-filter">
        <button type="button" :class="{ on: !dimension }" @click="dimension = ''">全部维度</button>
        <button
          v-for="group in standard.groups"
          :key="group.dim"
          type="button"
          :class="{ on: dimension === group.dim }"
          @click="dimension = group.dim"
        >
          {{ group.dim }}
        </button>
      </div>
      <div class="standard-group-stack">
        <div v-for="group in visibleGroups" :key="group.dim" class="standard-group">
          <div class="standard-group-head">
            <h4>{{ group.dim }}</h4>
            <span>{{ group.items.length }} 条 · 合计 {{ groupScore(group) }} 分</span>
          </div>
          <div class="inspection-table-wrap">
            <table class="inspection-table standards-table" :aria-label="group.dim + '标准条款'">
              <thead>
                <tr><th>条款编号</th><th>条款标题</th><th>判定说明</th><th class="r">标准分</th><th>风险级别</th></tr>
              </thead>
              <tbody>
                <tr v-for="(clause, index) in group.items" :key="clauseKey(group, index)" :class="riskClass(clause.riskLevel)">
                  <td class="muted-cell">{{ clause.code || '—' }}</td>
                  <td><b>{{ clause.item }}</b></td>
                  <td>{{ clause.method || '—' }}</td>
                  <td class="r">{{ clause.score }}分</td>
                  <td><span class="risk-chip" :class="clause.riskLevel.toLowerCase()">{{ riskLabel(clause.riskLevel) }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style>
.inspection-standards-view, .standard-group-stack { display: grid; gap: 14px; }
.inspection-standard-summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); align-items: center; gap: 12px; }
.inspection-standard-summary > div { min-height: 72px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 12px; background: #fafbfc; }
.inspection-standard-summary span { display: block; color: var(--muted); font-size: 12px; font-weight: 700; }
.inspection-standard-summary b { display: block; margin-top: 4px; font-size: 24px; line-height: 1.1; font-weight: 900; font-variant-numeric: tabular-nums; }
.inspection-standard-error { border-color: var(--bad); background: var(--ds-danger-soft); }
.inspection-standard-error-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.inspection-standard-error-head b, .inspection-standard-error-head span { display: block; }
.inspection-standard-error-head b { color: var(--bad); }
.inspection-standard-error-head span { margin-top: 4px; color: #6f2b25; font-size: 13px; }
.inspection-standard-category-audit { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.inspection-standard-category-audit article { min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 8px; background: #fff; }
.inspection-standard-category-audit article.invalid { border-color: rgba(217, 79, 61, .32); background: var(--ds-danger-soft); }
.inspection-standard-category-audit article div { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.inspection-standard-category-audit article span { color: var(--ink); font-weight: 800; font-variant-numeric: tabular-nums; }
.inspection-standard-category-audit article small { display: block; margin-top: 4px; color: var(--muted); }
.dimension-filter { display: inline-flex; align-items: center; gap: 4px; padding: 4px; border-radius: 12px; background: #eef1f6; }
.dimension-filter button { min-height: 34px; padding: 7px 12px; border: 0; border-radius: 9px; background: transparent; color: var(--muted); font-size: 13px; font-weight: 900; cursor: pointer; white-space: nowrap; }
.dimension-filter button.on { background: var(--brand-soft, #fff); color: var(--brand-color, var(--primary-dark)); box-shadow: 0 8px 20px -14px currentColor; }
.standard-group { display: grid; gap: 10px; padding-top: 12px; border-top: 1px solid var(--line); }
.standard-group:first-child { padding-top: 0; border-top: 0; }
.standard-group-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 0; }
.standard-group-head h4 { margin: 0; font-size: 18px; font-weight: 900; }
.standard-group-head span { color: var(--muted); font-size: 13px; font-weight: 800; }
.standards-table td { line-height: 1.45; }
.muted-cell { color: var(--muted); font-variant-numeric: tabular-nums; }
.risk-chip { display: inline-flex; align-items: center; min-height: 24px; padding: 2px 8px; border-radius: 999px; background: var(--ds-surface-muted); color: var(--ds-secondary); font-size: 12px; font-weight: 800; white-space: nowrap; }
.risk-chip.red { background: var(--ds-danger-soft); color: var(--bad); }
.risk-chip.yellow { background: var(--ds-warning-soft); color: var(--warn); }
@media (max-width: 720px) {
  .inspection-standard-category-audit { grid-template-columns: 1fr; }
  .inspection-standard-error-head, .standard-group-head { flex-direction: column; }
  .dimension-filter { max-width: 100%; overflow-x: auto; }
}
</style>
