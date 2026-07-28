<script setup lang="ts">
import { computed } from 'vue'
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
}>()

const emit = defineEmits<{ save: [] }>()

const redLineHit = computed(() => props.resultText.includes('命中红线'))
const resultLabel = computed(() => redLineHit.value ? '不合格' : props.resultText)
</script>

<template>
  <section class="content-card inspection-score-summary">
    <header class="score-summary-heading">
      <div>
        <span>巡检评分</span>
        <h2>200 分制评分结果</h2>
      </div>
      <p>满分 200 分 · 合格线 {{ props.passLine }} 分</p>
    </header>

    <div v-for="category in props.categoryScores" :key="category.code" class="category-score">
      <span>{{ category.name }}得分</span>
      <b>{{ category.score }}<small> / {{ category.fullScore }} 分</small></b>
    </div>

    <div class="total-score-card">
      <span>最终得分（200分制）</span>
      <b :class="props.scoreTone">
        {{ props.scoreDisplay }}<small v-if="props.recognitionReady"> / 200 分</small>
      </b>
    </div>

    <div class="deduction-score-card">
      <span>累计扣分</span>
      <b :class="props.recognitionReady ? 'bad' : 'pending'">
        {{ props.recognitionReady ? `扣 ${props.deductionTotal} 分` : '—' }}
      </b>
    </div>

    <div class="result-score-card" :class="{ 'red-line-result': redLineHit }">
      <span>判定结果</span>
      <b :class="props.scoreTone">{{ resultLabel }}</b>
      <small v-if="redLineHit">命中红线，一票否决；红线优先于总分判定</small>
      <small v-else>合格线 {{ props.passLine }} 分，最终以保存后服务端判定为准</small>
    </div>

    <div v-if="props.yellowRiskCount" class="yellow-risk-summary">
      <span>黄线风险</span>
      <b>{{ props.yellowRiskCount }} 项</b>
      <small>黄线仅扣分，不触发一票否决</small>
    </div>

    <div class="score-summary-actions">
      <button class="primary-button save-button" type="button" :disabled="props.saving || props.uploading" @click="emit('save')">
        <Upload :size="16" />{{ props.saving ? '保存中...' : '保存巡检' }}
      </button>
    </div>
  </section>
</template>

<style>
.inspection-score-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  align-items: stretch;
  gap: 12px;
}

.score-summary-heading,
.score-summary-actions {
  grid-column: 1 / -1;
}

.score-summary-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 2px;
}

.score-summary-heading span {
  color: var(--primary);
  font-size: 12px;
  font-weight: 800;
}

.score-summary-heading h2 {
  margin: 4px 0 0;
  font-size: 18px;
}

.score-summary-heading p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.inspection-score-summary > div:not(.score-summary-actions) {
  min-height: 88px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fafbfc;
}

.inspection-score-summary > div:not(.score-summary-actions) > span {
  display: block;
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
}

.inspection-score-summary b {
  display: block;
  margin-top: 6px;
  font-size: 24px;
  line-height: 1.1;
  font-weight: 900;
  font-variant-numeric: tabular-nums;
}

.inspection-score-summary small {
  display: block;
  margin-top: 6px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.45;
}

.inspection-score-summary b small {
  display: inline;
  color: var(--muted);
  font-size: 13px;
  font-weight: 700;
}

.inspection-score-summary .total-score-card {
  border-color: rgba(39, 107, 101, 0.3);
  background: var(--primary-soft);
}

.inspection-score-summary .red-line-result {
  border-color: rgba(220, 38, 38, 0.28);
  background: rgba(220, 38, 38, 0.05);
}

.inspection-score-summary .yellow-risk-summary {
  border-color: #e7c36b;
  background: var(--ds-warning-soft);
}

.inspection-score-summary .yellow-risk-summary b {
  color: var(--warn);
}

.inspection-score-summary .bad {
  color: var(--bad);
}

.inspection-score-summary .pending {
  color: var(--muted);
}

.score-summary-actions {
  display: flex;
  justify-content: flex-start;
  padding-top: 4px;
}

.inspection-score-summary .save-button {
  min-width: 176px;
  min-height: 46px;
}

@media (max-width: 700px) {
  .score-summary-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  .score-summary-actions .save-button {
    width: 100%;
  }
}
</style>
