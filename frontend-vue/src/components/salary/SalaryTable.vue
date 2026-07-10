<script setup lang="ts">
import { ChevronLeft, ChevronRight, Loader2 } from 'lucide-vue-next'
import type { SalaryRecord } from '../../api/finance'
import { money, statusClass, statusLabel } from '../../composables/useSalaryPage'
import SalaryWorkflowActions from './SalaryWorkflowActions.vue'

defineProps<{
  rows: SalaryRecord[]
  total: number
  page: number
  totalPages: number
  loading: boolean
  canEdit: boolean
  canReview: boolean
  actioningId: string
  deletingId: string
}>()

const emit = defineEmits<{
  'page-change': [page: number]
  view: [record: SalaryRecord]
  edit: [record: SalaryRecord]
  submit: [record: SalaryRecord]
  approve: [record: SalaryRecord]
  reject: [record: SalaryRecord]
  delete: [record: SalaryRecord]
  markPaid: [record: SalaryRecord]
  lock: [record: SalaryRecord]
}>()

function deductionTotal(r: SalaryRecord) {
  return Number(r.deductUniform || 0) + Number(r.returnUniform || 0)
}

function allowanceTotal(r: SalaryRecord) {
  return Number(r.social || 0) + Number(r.post || 0) + Number(r.meal || 0) + Number(r.subsidy || 0)
}

function bonusTotal(r: SalaryRecord) {
  return Number(r.fullAttendance || 0) + Number(r.seniority || 0) + Number(r.lateNight || 0)
}
</script>

<template>
  <div v-if="loading && total === 0" class="content-card loading-block"><Loader2 :size="24" class="spin" /> 工资明细加载中...</div>
  <section v-if="total > 0 || loading" class="content-card table-card">
    <div class="table-head"><h3>工资明细</h3><span class="table-count">共 {{ total }} 条 · 第 {{ page }}/{{ totalPages }} 页</span></div>
    <div class="table-wrap">
      <table class="salary-table">
        <thead><tr>
            <th>工号</th><th>姓名</th><th>门店</th><th>岗位</th>
            <th class="r">基本工资</th><th class="r">加班</th><th class="r">绩效</th>
            <th class="r">提成</th><th class="r">津贴</th><th class="r">奖金</th>
            <th class="r">扣款</th><th class="r">应发</th><th>状态</th><th class="r">操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id" @click="emit('view', r)">
            <td class="muted">{{ r.employeeId || '-' }}</td>
            <td><b>{{ r.employeeName }}</b></td>
            <td class="muted">{{ r.storeName || r.storeId }}</td>
            <td>{{ r.position || '-' }}</td>
            <td class="r">{{ money(r.base) }}</td>
            <td class="r">{{ money(r.overtime) }}</td>
            <td class="r">{{ money(r.performance) }}</td>
            <td class="r">{{ money(r.commission) }}</td>
            <td class="r">{{ money(allowanceTotal(r)) }}</td>
            <td class="r">{{ money(bonusTotal(r)) }}</td>
            <td class="r">{{ money(deductionTotal(r)) }}</td>
            <td class="r strong">{{ money(r.gross) }}</td>
            <td><span class="status-pill" :class="statusClass(r.status)">{{ statusLabel(r.status) }}</span></td>
            <SalaryWorkflowActions
              :record="r"
              :can-edit="canEdit"
              :can-review="canReview"
              :actioning-id="actioningId"
              :deleting-id="deletingId"
              :store-name="r.storeName || r.storeId"
              @view="emit('view', $event)"
              @edit="emit('edit', $event)"
              @submit="emit('submit', $event)"
              @approve="emit('approve', $event)"
              @reject="emit('reject', $event)"
              @delete="emit('delete', $event)"
              @mark-paid="emit('markPaid', $event)"
              @lock="emit('lock', $event)"
            />
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="totalPages > 1" class="pager">
      <button :disabled="page <= 1" @click="emit('page-change', page - 1)"><ChevronLeft :size="16" /></button>
      <template v-for="p in totalPages" :key="p">
        <button v-if="p === 1 || p === totalPages || Math.abs(p - page) <= 2" :class="{ active: p === page }" @click="emit('page-change', p)">{{ p }}</button>
        <span v-else-if="p === page - 3 || p === page + 3" class="pager-ellipsis">…</span>
      </template>
      <button :disabled="page >= totalPages" @click="emit('page-change', page + 1)"><ChevronRight :size="16" /></button>
    </div>
  </section>
</template>

<style scoped>
.table-card { padding: 18px 20px 20px; border-radius: 16px; box-shadow: 0 16px 38px rgba(24,28,35,0.06); }
.table-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.table-head h3 { margin: 0; font-size: 17px; }
.table-count { color: var(--muted); font-size: 13px; }
.table-wrap { overflow-x: auto; }
.salary-table { width: 100%; min-width: 1200px; border-collapse: collapse; font-size: 13.5px; }
.salary-table th, .salary-table td { padding: 10px 8px; border-bottom: 1px solid var(--line); text-align: left; vertical-align: middle; }
.salary-table th { color: var(--muted); font-size: 12px; font-weight: 900; white-space: nowrap; position: sticky; top: 0; background: #fff; z-index: 1; }
.salary-table tbody tr { cursor: pointer; }
.salary-table tbody tr:hover { background: var(--bg); }
.salary-table .r { text-align: right; font-variant-numeric: tabular-nums; }
.muted { color: var(--muted); }
.strong { color: var(--ink); font-weight: 900; }
.status-pill { display: inline-flex; align-items: center; min-height: 24px; padding: 3px 9px; border-radius: 999px; background: rgba(39,174,96,0.12); color: var(--good); font-size: 11.5px; font-weight: 900; }
.status-pill.warn, .status-pill.pending { background: rgba(245,158,11,0.14); color: #a16207; }
.status-pill.done { background: rgba(39,174,96,0.12); color: var(--good); }
.status-pill.rejected { background: rgba(220,38,38,0.12); color: var(--bad); }
.status-pill.muted { background: rgba(107,114,128,0.13); color: var(--muted); }
.pager { display: flex; justify-content: center; align-items: center; gap: 4px; margin-top: 16px; }
.pager button { display: inline-flex; align-items: center; justify-content: center; min-width: 34px; height: 34px; padding: 0 8px; border: 1px solid var(--line); border-radius: 8px; background: #fff; color: var(--ink); font-size: 13px; font-weight: 700; cursor: pointer; }
.pager button.active { background: #ee7e3e; border-color: #ee7e3e; color: #fff; }
.pager button:disabled { opacity: 0.4; cursor: default; }
.pager-ellipsis { padding: 0 6px; color: var(--muted); }
.loading-block { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 40px; color: var(--muted); font-size: 15px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
