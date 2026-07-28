<script setup lang="ts">
import { computed } from 'vue'
import { Plus } from 'lucide-vue-next'
import type { InspectionDeductionForm } from '../../composables/useInspectionDraft'
import type { InspectionStandardClause } from '../../data/inspectionStandards'
import SearchableSingleSelect, {
  type SearchableSelectOption,
  type SearchableSelectValue,
} from '../common/SearchableSingleSelect.vue'

const props = defineProps<{
  form: InspectionDeductionForm
  dimensions: string[]
  clauses: InspectionStandardClause[]
  standardReady: boolean
}>()

const emit = defineEmits<{
  add: []
}>()

const clauseOptions = computed<SearchableSelectOption[]>(() => props.clauses.map((clause) => ({
  value: clause.id,
  label: [clause.code, clause.item].filter(Boolean).join(' · '),
  description: `${clause.categoryName} · 建议 ${clause.score} 分 · ${riskLabel(clause)}`,
  searchText: [
    clause.code,
    clause.item,
    clause.categoryName,
    clause.method,
    clause.description,
    clause.riskLevel,
    riskLabel(clause),
    `建议 ${clause.score} 分`,
  ].filter(Boolean).join(' '),
})))
const selectedClause = computed(() => props.clauses.find((clause) => clause.id === props.form.clauseId))
const prescribedScoreHint = computed(() => {
  const clause = selectedClause.value
  if (!clause) return '选择正式条款后自动带出规定分值，不能手动修改。'
  if (clause.riskLevel === 'RED') {
    return `红线条款：命中即判定不合格，本条款规定扣分为 ${clause.score} 分。`
  }
  return `本条款按稽核标准固定扣 ${clause.score} 分，不能手动修改。`
})

function selectClause(value: SearchableSelectValue) {
  const clauseId = Number(value)
  props.form.clauseId = Number.isInteger(clauseId) && clauseId > 0 ? clauseId : null
}

function riskLabel(clause: InspectionStandardClause) {
  if (clause.riskLevel === 'RED') return '红线'
  if (clause.riskLevel === 'YELLOW') return '黄线'
  return '普通项'
}
</script>

<template>
  <section class="content-card inspection-add-card">
    <div class="inspection-card-title">
      <div>
        <span class="inspection-section-title">添加扣分项</span>
        <h3>把现场问题记清楚</h3>
      </div>
    </div>
    <div class="inspection-add-form">
      <label>
        <span>维度</span>
        <select v-model="form.dimension">
          <option v-for="dimension in dimensions" :key="dimension" :value="dimension">{{ dimension }}</option>
        </select>
      </label>
      <label v-if="clauses.length">
        <span>检查条款</span>
        <SearchableSingleSelect
          :model-value="form.clauseId"
          :options="clauseOptions"
          placeholder="搜索并选择检查条款"
          search-placeholder="搜索编号、名称、检查方法或评分规则"
          aria-label="搜索选择检查条款"
          empty-message="当前维度没有匹配的检查条款"
          :disabled="!standardReady"
          @update:model-value="selectClause"
        />
      </label>
      <label v-else>
        <span>检查条款</span>
        <input v-model.trim="form.manualItem" placeholder="例如：吧台地面有水渍" />
      </label>
      <label>
        <span>条款规定扣分</span>
        <input
          :value="form.deduct ?? ''"
          type="number"
          readonly
          aria-readonly="true"
          aria-label="条款规定扣分"
          placeholder="选择条款后自动带出"
        />
        <small class="inspection-fixed-score-hint" :class="{ redline: selectedClause?.riskLevel === 'RED' }">
          {{ prescribedScoreHint }}
        </small>
      </label>
      <label class="wide">
        <span>问题描述</span>
        <input v-model.trim="form.issue" placeholder="写给门店看的问题，例如：开封物料未贴时效标签" />
      </label>
      <button class="primary-button add-button" type="button" @click="emit('add')">
        <Plus :size="16" />
        添加
      </button>
    </div>
  </section>
</template>

<style>
.inspection-add-card { overflow: visible; }
.inspection-add-card .inspection-add-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.inspection-add-card .inspection-add-form label { display: grid; gap: 6px; }
.inspection-add-card .inspection-add-form label span { color: var(--muted); font-size: 12px; font-weight: 800; }
.inspection-add-card .inspection-fixed-score-hint { color: var(--muted); font-size: 12px; font-weight: 600; line-height: 1.5; }
.inspection-add-card .inspection-fixed-score-hint.redline { color: var(--danger, #c33f4d); }
.inspection-add-card .inspection-add-form .wide { grid-column: 1 / -1; }
.inspection-add-card .add-button { min-height: 46px; }
@media (max-width: 720px) {
  .inspection-add-card .inspection-add-form { grid-template-columns: 1fr; }
}
</style>
