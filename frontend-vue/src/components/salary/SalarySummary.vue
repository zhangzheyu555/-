<script setup lang="ts">
import { CalendarDays, Clock3, Users, WalletCards } from 'lucide-vue-next'
import { money } from '../../composables/useSalaryPage'

defineProps<{
  employeeCount: number
  employeesLoading: boolean
  grossTotal: number
  workHoursTotal: number
  vacationBalanceTotal: number
}>()
</script>

<template>
  <section class="salary-summary-strip" aria-label="工资汇总">
    <div><Users :size="20" /><span>员工</span><b>{{ employeesLoading ? '--' : employeeCount }}</b><small>人</small></div>
    <div><WalletCards :size="20" /><span>应发合计</span><b>{{ employeesLoading ? '--' : money(grossTotal) }}</b></div>
    <div><Clock3 :size="20" /><span>总工时</span><b>{{ employeesLoading ? '--' : workHoursTotal.toFixed(2) }}</b><small>小时</small></div>
    <div><CalendarDays :size="20" /><span>假期余额合计</span><b>{{ employeesLoading ? '--' : vacationBalanceTotal.toFixed(1) }}</b><small>天</small></div>
  </section>
</template>

<style scoped>
.salary-summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  min-height: 60px;
  border: 1px solid #d9e7e5;
  border-radius: 6px;
  background: #f8fbfa;
}
.salary-summary-strip > div {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 13px 22px;
  border-right: 1px solid #dfe9e7;
  color: #276b65;
}
.salary-summary-strip > div:last-child { border-right: 0; }
.salary-summary-strip span { color: #526765; font-size: 14px; font-weight: 500; }
.salary-summary-strip b { color: #176c64; font-size: 20px; font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.salary-summary-strip small { color: #526765; font-size: 13px; }
</style>
