<script setup lang="ts">
import { money } from '../../composables/useSalaryPage'

defineProps<{
  total: number
  employeeCount: number
  employeesLoading: boolean
  statusCounts: Record<string, number>
  grossTotal?: number
}>()
</script>

<template>
  <div class="summary-row">
    <article class="sum-card main"><span>记录数</span><b>{{ total }}</b></article>
    <article class="sum-card"><span>员工数</span><b>{{ employeesLoading ? '...' : employeeCount }}</b></article>
    <article class="sum-card"><span>草稿</span><b>{{ statusCounts['DRAFT'] || 0 }}</b></article>
    <article class="sum-card"><span>待审核</span><b>{{ (statusCounts['SUBMITTED'] || 0) + (statusCounts['PENDING_REVIEW'] || 0) }}</b></article>
    <article class="sum-card"><span>已审核</span><b>{{ statusCounts['APPROVED'] || 0 }}</b></article>
    <article class="sum-card"><span>已发放</span><b>{{ (statusCounts['PAID'] || 0) + (statusCounts['LOCKED'] || 0) }}</b></article>
    <article v-if="grossTotal !== undefined" class="sum-card"><span>应发合计</span><b>{{ money(grossTotal) }}</b></article>
  </div>
</template>

<style scoped>
.summary-row { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 12px; }
.sum-card { min-height: 82px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 12px; background: #fff; box-shadow: 0 10px 24px rgba(24,28,35,0.05); }
.sum-card.main { color: #fff; border: 0; background: #ee7e3e; box-shadow: 0 14px 28px rgba(238,126,62,0.2); }
.sum-card span { color: var(--muted); font-size: 12.5px; font-weight: 900; }
.sum-card.main span { color: rgba(255,255,255,0.86); }
.sum-card b { display: block; margin-top: 7px; font-size: 22px; line-height: 1.05; }
.sum-card.main b { color: #fff; font-size: 26px; }

@media (max-width: 1080px) { .summary-row { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 720px) { .summary-row { grid-template-columns: 1fr; width: 100%; } }
</style>
