<script setup lang="ts">
import { Trash2 } from 'lucide-vue-next'
import type { InspectionDeductionDetail } from '../../composables/useInspectionDraft'

interface DeductionRow {
  kind: 'deduction' | 'redline'
  index: number
  item: InspectionDeductionDetail
}

defineProps<{
  rows: DeductionRow[]
  safeNumber: (value: unknown) => number
}>()

const emit = defineEmits<{
  remove: [kind: DeductionRow['kind'], index: number]
}>()

function rowKey(row: DeductionRow) {
  return row.item.id || row.kind + '-' + row.index
}

function clauseTitle(row: DeductionRow) {
  return (row.item.code ? row.item.code + ' ' : '') + (row.item.item || '现场问题')
}
</script>

<template>
  <section class="content-card inspection-deduction-card">
    <div class="inspection-card-title">
      <div>
        <span class="inspection-section-title">人工 / 模型调整记录（{{ rows.length }}）</span>
        <h3>已应用到完整条款的扣分</h3>
      </div>
    </div>
    <div v-if="!rows.length" class="empty-state compact">暂无扣分项。</div>
    <div v-else class="inspection-table-wrap">
      <table class="inspection-table deduction-table">
        <thead>
          <tr>
            <th>维度</th>
            <th>检查条款</th>
            <th class="r">扣分</th>
            <th>问题描述</th>
            <th>是否红线项</th>
            <th class="r">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="rowKey(row)">
            <td>{{ row.item.dim || '—' }}</td>
            <td>
              <b>{{ clauseTitle(row) }}</b>
              <small v-if="row.item.method">{{ row.item.method }}</small>
            </td>
            <td class="r">{{ row.item.redline ? '—' : safeNumber(row.item.deduct) + '分' }}</td>
            <td>{{ row.item.issue || '—' }}</td>
            <td><span class="result-text" :class="{ danger: row.item.redline }">{{ row.item.redline ? '是' : '否' }}</span></td>
            <td class="r">
              <button class="icon-button" type="button" aria-label="删除扣分项" @click="emit('remove', row.kind, row.index)">
                <Trash2 :size="15" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style>
.inspection-deduction-card .deduction-table small { display: block; margin-top: 4px; color: var(--muted); }
.inspection-deduction-card .deduction-table { min-width: 920px; }
.inspection-deduction-card .deduction-table th,
.inspection-deduction-card .deduction-table td { white-space: nowrap; }
.inspection-deduction-card .deduction-table th:nth-child(2),
.inspection-deduction-card .deduction-table td:nth-child(2),
.inspection-deduction-card .deduction-table th:nth-child(4),
.inspection-deduction-card .deduction-table td:nth-child(4) { white-space: normal; }
.inspection-deduction-card .deduction-table th:nth-child(1),
.inspection-deduction-card .deduction-table td:nth-child(1) { width: 100px; }
.inspection-deduction-card .deduction-table th:nth-child(5),
.inspection-deduction-card .deduction-table td:nth-child(5) { width: 92px; text-align: center; }
</style>
