<script setup lang="ts">
import { ChevronLeft, ChevronRight, Eye, Loader2 } from 'lucide-vue-next'
import type { SalaryRecord } from '../../api/finance'
import { money, statusClass, statusLabel } from '../../composables/useSalaryPage'

const props = defineProps<{
  rows: SalaryRecord[]
  total: number
  page: number
  totalPages: number
  loading: boolean
  selectedRowKey: string
  checkedIds: Set<string>
}>()

const emit = defineEmits<{
  'page-change': [page: number]
  select: [record: SalaryRecord]
  'toggle-row': [record: SalaryRecord, checked: boolean]
  'toggle-all': [checked: boolean]
}>()

function attendanceDays(value?: string) {
  const match = String(value || '').match(/[\d.]+/)
  return match ? match[0] : '--'
}

function allChecked() {
  return props.rows.length > 0 && props.rows.every((row) => props.checkedIds.has(row.id))
}

function rowKey(row: SalaryRecord) {
  return row.id || `employee:${row.employeeId || `${row.storeId}:${row.employeeName}`}`
}
</script>

<template>
  <section class="salary-table-section">
    <div v-if="loading && rows.length === 0" class="table-loading"><Loader2 :size="22" class="spin" />员工工资加载中</div>
    <div v-else-if="rows.length === 0" class="table-empty">当前筛选范围暂无员工</div>
    <div v-else class="table-wrap">
      <table>
        <thead><tr>
          <th class="check"><input type="checkbox" :checked="allChecked()" aria-label="选择当前页" @change="emit('toggle-all', ($event.target as HTMLInputElement).checked)" /></th>
          <th>姓名</th><th>工号</th><th>岗位</th><th class="num">出勤天数</th><th class="num">应发工资</th><th class="num">提成</th><th class="num">总工时</th><th class="num">假期余额</th><th>状态</th><th class="action">操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="rowKey(row)" :class="{ selected: rowKey(row) === selectedRowKey }" @click="emit('select', row)">
            <td class="check" @click.stop><input type="checkbox" :checked="checkedIds.has(row.id)" :aria-label="`选择${row.employeeName}`" @change="emit('toggle-row', row, ($event.target as HTMLInputElement).checked)" /></td>
            <td><b>{{ row.employeeName }}</b></td>
            <td class="muted">{{ row.employeeId || '--' }}</td>
            <td>{{ row.position || '--' }}</td>
            <td class="num">{{ attendanceDays(row.attendance) }}</td>
            <td class="num strong">{{ row.status === 'PENDING_GENERATION' ? '--' : money(row.gross) }}</td>
            <td class="num commission">{{ row.status === 'PENDING_GENERATION' ? '--' : money(row.commission) }}</td>
            <td class="num">{{ Number(row.workHours || 0).toFixed(2) }}</td>
            <td class="num">{{ Number(row.vacationLeft || 0).toFixed(1) }}</td>
            <td><span class="status-pill" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span></td>
            <td class="action"><button type="button" title="查看工资明细" @click.stop="emit('select', row)"><Eye :size="15" /></button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <footer v-if="total > 0" class="table-footer">
      <span>共 {{ total }} 条</span>
      <div class="pager">
        <button :disabled="page <= 1" @click="emit('page-change', page - 1)"><ChevronLeft :size="16" /></button>
        <b>{{ page }}</b><span>/ {{ totalPages }}</span>
        <button :disabled="page >= totalPages" @click="emit('page-change', page + 1)"><ChevronRight :size="16" /></button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.salary-table-section { min-width: 0; border: 1px solid #dfe8e6; border-radius: 6px; overflow: hidden; background: #fff; }
.table-wrap { width: 100%; overflow-x: auto; }
table { width: 100%; min-width: 720px; border-collapse: collapse; table-layout: fixed; font-size: 13px; }
th, td { height: 46px; padding: 8px 9px; border-bottom: 1px solid #e6edeb; text-align: left; white-space: nowrap; }
th { height: 40px; background: #f7faf9; color: #526765; font-size: 13px; font-weight: 600; }
tbody tr { cursor: pointer; transition: background-color .12s ease; }tbody tr:hover { background: #f4faf8; }tbody tr.selected { background: #eef7f5; box-shadow: inset 3px 0 #276b65; }
td b { color: #182424; font-weight: 600; }.muted { color: #6f817f; }.strong { color: #182424; font-weight: 700; }.commission { color: #15756b; font-weight: 600; }
td { overflow: hidden; text-overflow: ellipsis; }
.num { text-align: right; font-variant-numeric: tabular-nums; }.check { width: 36px; text-align: center; }.action { width: 46px; text-align: center; }
input[type='checkbox'] { width: 15px; height: 15px; accent-color: #276b65; cursor: pointer; }
.action button { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 1px solid #d5e2df; border-radius: 4px; background: #fff; color: #526765; cursor: pointer; }.action button:hover { border-color: #6baaa4; color: #276b65; }
.status-pill { display: inline-flex; padding: 4px 7px; border-radius: 4px; background: #e7f5ef; color: #28795f; font-size: 12px; font-weight: 600; }.status-pill.warn,.status-pill.pending { background: #fff2e2; color: #d46a16; }.status-pill.rejected { background: #fdeceb; color: #c34b40; }.status-pill.muted { background: #edf1f0; color: #637572; }
.table-footer { display: flex; align-items: center; justify-content: space-between; min-height: 48px; padding: 8px 12px; color: #526765; font-size: 13px; }.pager { display: flex; align-items: center; gap: 8px; }.pager button { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 1px solid #d9e5e3; border-radius: 4px; background: #fff; color: #276b65; cursor: pointer; }.pager button:disabled { color: #aab7b5; cursor: default; }.pager b { min-width: 25px; padding: 5px 8px; border-radius: 4px; background: #276b65; color: #fff; text-align: center; }
.table-loading,.table-empty { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 96px; color: #6f817f; font-size: 14px; }.spin { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
</style>
