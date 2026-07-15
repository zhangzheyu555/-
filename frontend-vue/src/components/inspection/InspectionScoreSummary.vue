<script setup lang="ts">
import { Upload } from 'lucide-vue-next'

interface CategoryScoreView {
  code: string
  name: string
  score: number
  fullScore: number
}

const props = defineProps<{
  categoryScores: CategoryScoreView[]
  scoreTone: string
  scoreDisplay: string
  recognitionReady: boolean
  deductionTotal: number
  resultText: string
  passLine: number
  yellowRiskCount: number
  saving: boolean
  uploading: boolean
  saveBlockedReason: string
}>()

const emit = defineEmits<{ save: [] }>()
</script>

<template>
  <section class="content-card inspection-score-summary">
    <div v-for="category in props.categoryScores" :key="category.code" class="category-score">
      <span>{{ category.name }}得分</span><b>{{ category.score }}<small> 分（满分{{ category.fullScore }}）</small></b>
    </div>
    <div><span>总分</span><b :class="props.scoreTone">{{ props.scoreDisplay }}<small v-if="props.recognitionReady"> / 200</small></b></div>
    <div><span>扣分合计</span><b :class="props.recognitionReady ? 'bad' : 'pending'">{{ props.recognitionReady ? `-${props.deductionTotal}` : '—' }}</b></div>
    <div><span>结果</span><b :class="props.scoreTone">{{ props.resultText }}</b><small>合格线 {{ props.passLine }} 分，最终以保存后服务端判定为准</small></div>
    <div v-if="props.yellowRiskCount" class="yellow-risk-summary"><span>黄线风险</span><b>{{ props.yellowRiskCount }} 项</b><small>黄线仅扣分，不触发一票否决</small></div>
    <button class="primary-button save-button" type="button" :disabled="props.saving || props.uploading || Boolean(props.saveBlockedReason)" :title="props.saveBlockedReason" @click="emit('save')"><Upload :size="16" />{{ props.saving ? '保存中...' : '保存巡检' }}</button>
    <small v-if="props.saveBlockedReason" class="inspection-save-hint">{{ props.saveBlockedReason }}</small>
  </section>
</template>

<style>
.inspection-score-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));align-items:center;gap:12px}.inspection-score-summary>div{min-height:72px;padding:10px 12px;border:1px solid var(--line);border-radius:12px;background:#fafbfc}.inspection-score-summary span{display:block;color:var(--muted);font-size:12px;font-weight:700}.inspection-score-summary b{display:block;margin-top:4px;font-size:24px;line-height:1.1;font-weight:900;font-variant-numeric:tabular-nums}.inspection-score-summary small{display:block;margin-top:4px;color:var(--muted);font-size:12px}.inspection-score-summary b small{display:inline;color:var(--muted);font-size:13px;font-weight:700}.inspection-score-summary .yellow-risk-summary{border-color:#e7c36b;background:var(--ds-warning-soft)}.inspection-score-summary .yellow-risk-summary b{color:var(--warn)}.inspection-score-summary .bad{color:var(--bad)}.inspection-score-summary .pending{color:var(--muted)}.inspection-score-summary .save-button{min-height:46px}.inspection-score-summary .inspection-save-hint{grid-column:1/-1;color:var(--warn);text-align:right}
</style>
