<script setup lang="ts">
import { Plus } from 'lucide-vue-next'
import type { InspectionDeductionForm } from '../../composables/useInspectionDraft'
import type { InspectionStandardClause } from '../../data/inspectionStandards'

defineProps<{
  form: InspectionDeductionForm
  dimensions: string[]
  clauses: InspectionStandardClause[]
  fullScore: number
  standardReady: boolean
}>()

const emit = defineEmits<{
  add: []
}>()

function clauseOptionLabel(clause: InspectionStandardClause) {
  return (clause.code ? clause.code + ' ' : '') + clause.item + '（建议' + clause.score + '分）'
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
        <select v-model="form.clauseKey">
          <option v-for="(clause, index) in clauses" :key="(clause.code || clause.item) + '-' + index" :value="String(index)">
            {{ clauseOptionLabel(clause) }}
          </option>
        </select>
      </label>
      <label v-else>
        <span>检查条款</span>
        <input v-model.trim="form.manualItem" placeholder="例如：吧台地面有水渍" />
      </label>
      <label>
        <span>扣分</span>
        <input v-model.number="form.deduct" type="number" min="1" :max="fullScore" placeholder="填写扣分" />
      </label>
      <label class="wide">
        <span>问题描述</span>
        <input v-model.trim="form.issue" placeholder="写给门店看的问题，例如：开封物料未贴时效标签" />
      </label>
      <button class="primary-button add-button" type="button" :disabled="!standardReady" @click="emit('add')">
        <Plus :size="16" />
        添加
      </button>
    </div>
  </section>
</template>

<style>
.inspection-add-card { overflow: hidden; }
.inspection-add-card .inspection-add-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.inspection-add-card .inspection-add-form label { display: grid; gap: 6px; }
.inspection-add-card .inspection-add-form label span { color: var(--muted); font-size: 12px; font-weight: 800; }
.inspection-add-card .inspection-add-form .wide { grid-column: 1 / -1; }
.inspection-add-card .add-button { min-height: 46px; }
@media (max-width: 720px) {
  .inspection-add-card .inspection-add-form { grid-template-columns: 1fr; }
}
</style>
