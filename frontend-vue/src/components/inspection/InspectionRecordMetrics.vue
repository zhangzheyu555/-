<script setup lang="ts">
defineProps<{
  total: number
  monthCount: number
  averageScore: number | null
  invalidScoreCount: number
  redlineCount: number
  maxScore: number
  formatScore: (score: number | null | undefined) => string
}>()
</script>

<template>
  <div class="inspection-metrics">
    <div class="content-card inspection-metric">
      <span class="lab">巡检总次数</span>
      <b class="val">{{ total }}</b>
    </div>
    <div class="content-card inspection-metric">
      <span class="lab">本月巡检</span>
      <b class="val">{{ monthCount }}</b>
    </div>
    <div class="content-card inspection-metric">
      <span class="lab">平均得分</span>
      <b class="val">{{ averageScore === null ? '—' : formatScore(averageScore) }}<small> / {{ maxScore }}</small></b>
      <small v-if="invalidScoreCount" class="metric-warning">{{ invalidScoreCount }} 条评分数据待修复</small>
    </div>
    <div class="content-card inspection-metric">
      <span class="lab">红线门店次数</span>
      <b class="val red">{{ redlineCount }}</b>
    </div>
  </div>
</template>

<style>
.inspection-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.inspection-metric { min-width: 0; }
.inspection-metric .lab { color: var(--muted); font-size: 12px; font-weight: 600; }
.inspection-metric .val { display: block; margin-top: 3px; color: var(--ink); font-size: 25px; line-height: 1.15; font-weight: 900; font-variant-numeric: tabular-nums; }
.inspection-metric .val small, .metric-warning { font-size: 12px; font-weight: 700; }
.metric-warning { display: block; margin-top: 5px; color: var(--warn); }
.inspection-metric:first-child { border-color: var(--primary); background: var(--primary); }
.inspection-metric:first-child .lab, .inspection-metric:first-child .val { color: #fff; }
.inspection-metric:nth-child(2) .val { color: var(--good); }
.inspection-metric .val.red { color: var(--bad); }
@media (max-width: 900px) {
  .inspection-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 480px) {
  .inspection-metrics { grid-template-columns: 1fr; }
}
</style>
