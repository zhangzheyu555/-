<script setup lang="ts">
import type { InspectionRecord } from '../../api/inspection'
import type { InspectionBrandOption } from '../../composables/useInspectionDraft'

interface RecordScore {
  scoreText: string
  error?: string
}

defineProps<{
  brandOptions: InspectionBrandOption[]
  monthOptions: string[]
  filterBrand: string
  filterMonth: string
  selectedRecordId: string
  records: InspectionRecord[]
  loading: boolean
  recordBrandName: (record: InspectionRecord) => string
  brandMeta: (brand: string) => { color: string; soft: string }
  recordScore: (record: InspectionRecord) => RecordScore
  resultIsFailed: (record: InspectionRecord) => boolean
  resultLabel: (record: InspectionRecord) => string
  deductionCount: (record: InspectionRecord) => number
}>()

const emit = defineEmits<{
  'update:brand': [brand: string]
  'update:month': [month: string]
  select: [recordId: string]
}>()

function updateMonth(event: Event) {
  emit('update:month', (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <section class="inspection-record-list">
    <div class="inspection-filter-row">
      <div class="inspection-brand-filter" aria-label="品牌筛选">
        <button type="button" class="inspection-filter-chip" :class="{ on: !filterBrand }" @click="emit('update:brand', '')">
          全部品牌
        </button>
        <button
          v-for="brand in brandOptions"
          :key="brand.name"
          type="button"
          class="inspection-filter-chip"
          :class="{ on: filterBrand === brand.name }"
          :style="{ '--brand-color': brand.color, '--brand-soft': brand.soft }"
          @click="emit('update:brand', brand.name)"
        >
          {{ brand.name }}
        </button>
      </div>
      <select class="inspection-month-select" aria-label="月份筛选" :value="filterMonth" @change="updateMonth">
        <option value="">全部月份</option>
        <option v-for="month in monthOptions" :key="month" :value="month">{{ month.slice(0, 4) }}年{{ month.slice(5) }}月</option>
      </select>
    </div>

    <slot v-if="selectedRecordId" />

    <section v-else class="content-card inspection-table-card">
      <div class="inspection-table-head">
        <span class="inspection-section-title">{{ filterBrand || filterMonth ? '筛选后 ' + records.length + ' 次' : '共 ' + records.length + ' 次巡检' }}</span>
      </div>
      <div v-if="loading && !records.length" class="empty-state">正在读取巡检记录...</div>
      <div v-else-if="!records.length" class="empty-state">
        <b>{{ filterBrand || filterMonth ? '当前筛选下没有巡检记录' : '暂无巡检记录' }}</b>
      </div>
      <div v-else class="inspection-table-wrap">
        <table class="inspection-table">
          <thead>
            <tr><th>日期</th><th>门店</th><th>品牌</th><th>督导</th><th class="r">得分</th><th>结果</th><th class="r">扣分项</th></tr>
          </thead>
          <tbody>
            <tr v-for="record in records" :key="record.id" class="clickable-row" @click="emit('select', String(record.id))">
              <td>{{ record.inspectionDate || '—' }}</td>
              <td><b>{{ record.storeName || record.storeId }}</b></td>
              <td>
                <span class="brand-pill" :style="{ background: brandMeta(recordBrandName(record)).soft, color: brandMeta(recordBrandName(record)).color }">
                  <span class="dotc" :style="{ background: brandMeta(recordBrandName(record)).color }" />
                  {{ recordBrandName(record) || '—' }}
                </span>
              </td>
              <td>{{ record.inspector || '—' }}</td>
              <td class="r score-cell" :class="{ danger: resultIsFailed(record) }" :title="recordScore(record).error">{{ recordScore(record).scoreText }}</td>
              <td><span class="result-text" :class="{ danger: resultIsFailed(record) }">{{ resultLabel(record) }}</span></td>
              <td class="r muted-cell">{{ deductionCount(record) }}项</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style>
.inspection-record-list { display: grid; gap: 14px; }
.inspection-filter-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.inspection-brand-filter { display: flex; min-width: 0; gap: 6px; flex-wrap: wrap; }
.inspection-filter-chip { min-height: 34px; padding: 6px 11px; border: 1px solid var(--line); border-radius: 999px; background: #fff; color: var(--muted); font-size: 13px; font-weight: 800; cursor: pointer; }
.inspection-filter-chip.on { border-color: var(--brand-color, var(--primary)); background: var(--brand-soft, var(--primary-soft)); color: var(--brand-color, var(--primary-dark)); }
.inspection-month-select { width: 150px; flex: 0 0 auto; }
.inspection-table-head { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.inspection-section-title { display: block; margin: 0 0 6px; color: var(--muted); font-size: 13px; font-weight: 700; }
.brand-pill { display: inline-flex; align-items: center; gap: 5px; min-height: 22px; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 700; }
.brand-pill .dotc { width: 7px; height: 7px; border-radius: 50%; }
.clickable-row { cursor: pointer; }
.score-cell.danger, .result-text.danger { color: var(--bad); }
.result-text { color: var(--good); font-weight: 900; }
.muted-cell { color: var(--muted); font-variant-numeric: tabular-nums; }
@media (max-width: 720px) {
  .inspection-filter-row { align-items: stretch; flex-direction: column; }
  .inspection-month-select { width: 100%; }
}
</style>
