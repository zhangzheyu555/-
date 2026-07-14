<script setup lang="ts">
import { computed } from 'vue'
import { Building2, Check, Clock3, Send, UserRound, XCircle } from 'lucide-vue-next'
import type { SalaryRecord } from '../../api/finance'
import { money, statusClass, statusLabel } from '../../composables/useSalaryPage'

const props = defineProps<{
  record: SalaryRecord | null
  revenue: number
  canEdit: boolean
  canReview: boolean
  canPay: boolean
  actioningId: string
}>()

const emit = defineEmits<{
  submit: [record: SalaryRecord]
  approve: [record: SalaryRecord]
  reject: [record: SalaryRecord]
  markPaid: [record: SalaryRecord]
  preview: []
}>()

const allowance = computed(() => {
  const row = props.record
  return Number(row?.social || 0) + Number(row?.post || 0) + Number(row?.meal || 0)
    + Number(row?.fullAttendance || 0) + Number(row?.seniority || 0)
    + Number(row?.lateNight || 0) + Number(row?.subsidy || 0) + Number(row?.performance || 0)
})
const deduction = computed(() => Number(props.record?.deductUniform || 0) + Number(props.record?.returnUniform || 0))
const commissionRate = computed(() => props.revenue > 0
  ? `${(Number(props.record?.commission || 0) / props.revenue * 100).toFixed(2)}%`
  : '--')
const attendanceDays = computed(() => {
  const match = String(props.record?.attendance || '').match(/[\d.]+/)
  return match ? match[0] : '--'
})
const busy = computed(() => Boolean(props.record && props.actioningId === props.record.id))
const isPendingGeneration = computed(() => props.record?.status === 'PENDING_GENERATION')
</script>

<template>
  <aside class="salary-detail-panel">
    <div v-if="!record" class="detail-empty">
      <UserRound :size="25" />
      <b>暂无员工</b>
      <span>当前筛选范围没有员工记录</span>
    </div>

    <template v-else>
      <header class="detail-head">
        <div><h2>{{ record.employeeName }} · 工资明细</h2><span>{{ record.month }}</span></div>
        <span class="status-pill" :class="statusClass(record.status)">{{ statusLabel(record.status) }}</span>
      </header>

      <div class="identity-row">
        <span><Building2 :size="15" />{{ record.storeName || record.storeId }}</span>
        <span><UserRound :size="15" />{{ record.position || '未设置岗位' }}</span>
      </div>

      <div class="detail-kpis">
        <div><span>出勤（天）</span><b>{{ attendanceDays }}</b></div>
        <div><span>总工时（小时）</span><b>{{ isPendingGeneration ? '--' : Number(record.workHours || 0).toFixed(2) }}</b></div>
        <div><span>假期余额（天）</span><b>{{ isPendingGeneration ? '--' : Number(record.vacationLeft || 0).toFixed(1) }}</b></div>
      </div>

      <section v-if="isPendingGeneration" class="pending-generation">
        <b>尚未生成工资</b>
        <span>金额、计算依据和审核记录将在工资生成后显示。</span>
      </section>

      <section v-else class="amount-list">
        <div><span>基础工资<small>固定底薪</small></span><b>{{ money(record.base) }}</b></div>
        <div><span>提成<small>营业额提成</small></span><b class="accent">{{ money(record.commission) }}</b></div>
        <div><span>补贴<small>岗位、餐补、绩效等</small></span><b>{{ money(allowance) }}</b></div>
        <div><span>扣款<small>工服及其他扣款</small></span><b class="danger">-{{ money(deduction) }}</b></div>
        <div class="gross"><span>应发工资</span><b>{{ money(record.gross) }}</b></div>
        <div v-if="['PAID', 'LOCKED'].includes(record.status || '')"><span>实发工资</span><b class="accent">{{ money(record.netPay ?? record.gross) }}</b></div>
      </section>

      <section v-if="!isPendingGeneration" class="commission-box">
        <header><b>提成计算依据</b><span>当前工资记录</span></header>
        <div><span>营业额</span><b>{{ revenue > 0 ? money(revenue) : '--' }}</b></div>
        <div><span>提成比例</span><b>{{ commissionRate }}</b></div>
        <div><span>提成金额</span><b class="accent">{{ money(record.commission) }}</b></div>
      </section>

      <section v-if="!isPendingGeneration" class="audit-timeline">
        <h3>审核记录</h3>
        <div><i /><span><b>工资记录生成</b><small>{{ record.month }}</small></span></div>
        <div v-if="record.reviewedAt"><i /><span><b>{{ record.reviewNote || '审核完成' }}</b><small>{{ record.reviewedAt }}</small></span></div>
        <div v-if="record.paidAt"><i /><span><b>工资已发放</b><small>{{ record.paidAt }}</small></span></div>
        <div v-if="!record.reviewedAt && ['SUBMITTED', 'PENDING_REVIEW'].includes(record.status || '')"><i /><span><b>等待审核</b><small>工资已提交</small></span></div>
      </section>

      <footer class="detail-actions">
        <button v-if="canEdit && isPendingGeneration" class="primary-action wide" :disabled="busy" @click="emit('preview')"><Clock3 :size="16" />进入生成预览</button>
        <template v-else>
          <button v-if="canReview && ['SUBMITTED', 'PENDING_REVIEW'].includes(record.status || '')" class="secondary-action" :disabled="busy" @click="emit('reject', record)"><XCircle :size="16" />退回修改</button>
          <button v-if="canReview && ['SUBMITTED', 'PENDING_REVIEW'].includes(record.status || '')" class="primary-action" :disabled="busy" @click="emit('approve', record)"><Check :size="16" />审核通过</button>
          <button v-else-if="canEdit && ['DRAFT', 'REJECTED'].includes(record.status || '')" class="primary-action wide" :disabled="busy" @click="emit('submit', record)"><Send :size="16" />提交审核</button>
          <button v-else-if="canPay && record.status === 'APPROVED'" class="primary-action wide" :disabled="busy" @click="emit('markPaid', record)"><Clock3 :size="16" />确认发放</button>
        </template>
      </footer>
    </template>
  </aside>
</template>

<style scoped>
.salary-detail-panel { min-width: 0; border-left: 1px solid #dfe8e6; background: #fff; padding: 0 0 0 18px; color: #182424; }
.detail-empty { min-height: 120px; display: grid; place-items: center; align-content: center; gap: 7px; color: #6f817f; text-align: center; }
.detail-empty b { color: #182424; font-size: 15px; }.detail-empty span { font-size: 13px; }
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 3px 0 14px; border-bottom: 1px solid #e4ecea; }
.detail-head h2 { margin: 0; font-size: 17px; line-height: 1.35; }.detail-head > div > span { color: #6f817f; font-size: 13px; }
.identity-row { display: flex; gap: 18px; padding: 11px 0; color: #526765; font-size: 13px; }.identity-row span { display: inline-flex; align-items: center; gap: 5px; }
.detail-kpis { display: grid; grid-template-columns: repeat(3, 1fr); border-block: 1px solid #e4ecea; }
.detail-kpis div { padding: 11px 8px; text-align: center; border-right: 1px solid #e4ecea; }.detail-kpis div:last-child { border-right: 0; }
.detail-kpis span { display: block; color: #6f817f; font-size: 12px; }.detail-kpis b { display: block; margin-top: 5px; font-size: 17px; font-variant-numeric: tabular-nums; }
.pending-generation { display: grid; gap: 6px; margin-top: 12px; padding: 14px; border: 1px solid #dce8e6; border-radius: 5px; background: #f7fbfa; }.pending-generation b { color: #276b65; font-size: 14px; }.pending-generation span { color: #627572; font-size: 12px; line-height: 1.55; }
.amount-list { padding: 9px 0; border-bottom: 1px solid #dfe8e6; }
.amount-list > div { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 7px 2px; }
.amount-list span { font-size: 14px; }.amount-list small { display: block; margin-top: 2px; color: #6f817f; font-size: 11px; }.amount-list b { font-variant-numeric: tabular-nums; }
.amount-list .gross { margin-top: 4px; padding-top: 12px; border-top: 1px dashed #d9e5e3; }.amount-list .gross b { color: #176c64; font-size: 22px; }
.accent { color: #15756b !important; }.danger { color: #d8583f !important; }
.commission-box { margin-top: 10px; padding: 10px 12px; border: 1px solid #dce8e6; border-radius: 5px; background: #f7fbfa; }
.commission-box header, .commission-box > div { display: flex; justify-content: space-between; gap: 10px; }.commission-box header { margin-bottom: 8px; }.commission-box header span, .commission-box > div { color: #526765; font-size: 12px; }.commission-box > div { padding: 3px 0; }.commission-box b { color: #182424; }
.audit-timeline { margin-top: 10px; padding: 10px 12px; border: 1px solid #dfe8e6; border-radius: 5px; }.audit-timeline h3 { margin: 0 0 9px; font-size: 14px; }
.audit-timeline > div { position: relative; display: flex; gap: 9px; padding: 4px 0 7px; }.audit-timeline i { width: 7px; height: 7px; margin-top: 5px; border-radius: 50%; background: #276b65; }.audit-timeline span { display: grid; }.audit-timeline b { font-size: 12px; }.audit-timeline small { color: #6f817f; font-size: 11px; }
.detail-actions { display: flex; gap: 10px; padding-top: 12px; }.detail-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 38px; flex: 1; border-radius: 5px; font-size: 14px; font-weight: 600; cursor: pointer; }.detail-actions .wide { flex-basis: 100%; }
.primary-action { border: 1px solid #276b65; background: #276b65; color: #fff; }.secondary-action { border: 1px solid #4f948e; background: #fff; color: #276b65; }.detail-actions button:disabled { opacity: .55; cursor: wait; }
.status-pill { display: inline-flex; padding: 4px 8px; border-radius: 4px; background: #e7f5ef; color: #28795f; font-size: 12px; font-weight: 600; white-space: nowrap; }.status-pill.warn,.status-pill.pending { background: #fff2e2; color: #d46a16; }.status-pill.rejected { background: #fdeceb; color: #c34b40; }.status-pill.muted { background: #edf1f0; color: #637572; }
</style>
