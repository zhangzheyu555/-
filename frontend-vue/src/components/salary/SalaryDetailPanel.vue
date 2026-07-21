<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Building2, Check, ChevronDown, Clock3, History, Save, Send, UserRound, XCircle } from 'lucide-vue-next'
import type { SalaryRecord, SalaryRecordPayload } from '../../api/finance'
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
  saveAttendance: [record: SalaryRecord, attendanceDays: number, overtimeHours: number, normalHours: number]
  saveDetails: [record: SalaryRecord, attendanceDays: number, overtimeHours: number, normalHours: number, payload: SalaryRecordPayload]
}>()

const attendanceInput = ref(0)
const partTimeHoursInput = ref(0)
const overtimeInput = ref(0)
const performanceInput = ref(0)
const lateNightInput = ref(0)
const subsidyInput = ref(0)
const deductionInput = ref(0)
const commissionInput = ref(0)
const adjustmentReason = ref('')
const showHistory = ref(false)
const isPartTime = computed(() => {
  const position = String(props.record?.position || '')
  return position.includes('兼职') || position.includes('实习') || position.includes('长期') || position.includes('水果')
})
const attendanceError = computed(() => {
  if (attendanceInput.value < 0 || attendanceInput.value > 31) return '出勤天数应在0—31天之间'
  if (partTimeHoursInput.value < 0 || partTimeHoursInput.value > 744) return '正常工时应在0—744小时之间'
  if (overtimeInput.value < 0 || overtimeInput.value > 300) return '加班小时应在0—300小时之间'
  return ''
})
const calculatedNormalHours = computed(() => isPartTime.value ? Number(partTimeHoursInput.value || 0) : Number(attendanceInput.value || 0) * 8)
const calculatedTotalHours = computed(() => calculatedNormalHours.value + Number(overtimeInput.value || 0))
watch(() => props.record, (record) => {
  const match = String(record?.attendance || '').match(/[\d.]+/)
  attendanceInput.value = match ? Number(match[0]) : 0
  partTimeHoursInput.value = record?.normalHours !== undefined && record?.normalHours !== null
    ? Number(record.normalHours)
    : Math.max(0, Number(record?.workHours || 0) - Number(record?.otHours || 0))
  overtimeInput.value = Number(record?.otHours || 0)
  performanceInput.value = Number(record?.performance || 0)
  lateNightInput.value = Number(record?.lateNight || 0)
  subsidyInput.value = Number(record?.subsidy || 0)
  deductionInput.value = Number(record?.deductUniform || 0)
  commissionInput.value = Number(record?.commission || 0)
  adjustmentReason.value = record?.vacationNote || ''
  showHistory.value = false
}, { immediate: true })

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
const busy = computed(() => Boolean(props.record && props.actioningId && props.actioningId === props.record.id))
const isPendingGeneration = computed(() => props.record?.status === 'PENDING_GENERATION')
const productivityHours = computed(() => calculatedNormalHours.value)
const expectedGross = computed(() => {
  const row = props.record
  return Number(row?.base || 0) + Number(row?.social || 0) + Number(row?.post || 0) + Number(row?.meal || 0)
    + Number(row?.fullAttendance || 0) + Number(row?.seniority || 0) + Number(lateNightInput.value || 0)
    + Number(subsidyInput.value || 0) + Number(performanceInput.value || 0) + Number(commissionInput.value || 0)
    + Number(row?.overtime || 0) - Number(deductionInput.value || 0) - Number(row?.returnUniform || 0)
})
function detailPayload(): SalaryRecordPayload {
  const row = props.record!
  return {
    storeId: row.storeId, month: row.month, employeeId: row.employeeId, employeeName: row.employeeName,
    position: row.position, attendance: isPartTime.value ? '按小时' : `${attendanceInput.value}天`, normalHours: calculatedNormalHours.value,
    otHours: overtimeInput.value, workHours: calculatedTotalHours.value, vacationLeft: row.vacationLeft,
    vacationNote: adjustmentReason.value, base: row.base, social: row.social, post: row.post, meal: row.meal,
    fullAttendance: row.fullAttendance, commission: commissionInput.value, overtime: row.overtime,
    seniority: row.seniority, lateNight: lateNightInput.value, subsidy: subsidyInput.value,
    performance: performanceInput.value, deductUniform: deductionInput.value, returnUniform: row.returnUniform,
    gross: expectedGross.value,
  }
}
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

      <section class="editor-section">
        <h3>考勤录入</h3>
        <div class="input-grid">
          <label v-if="!isPartTime">出勤天数<input v-model.number="attendanceInput" :disabled="!canEdit" type="number" min="0" max="31" step="0.5" /></label>
          <label v-else>正常工时<input v-model.number="partTimeHoursInput" :disabled="!canEdit" type="number" min="0" max="744" step="0.5" /></label>
          <label>加班小时<input v-model.number="overtimeInput" :disabled="!canEdit" type="number" min="0" max="300" step="0.5" /></label>
          <div class="wide-field overtime-kind"><span>加班类型</span><b>普通加班</b></div>
        </div>
        <div class="hour-summary">
          <div><span>正常工时</span><b>{{ calculatedNormalHours.toFixed(1) }} 小时</b></div>
          <div><span>总工时</span><b>{{ calculatedTotalHours.toFixed(1) }} 小时</b></div>
          <div><span>产值工时</span><b>{{ productivityHours.toFixed(1) }} 小时</b></div>
        </div>
        <p class="formula-note">{{ isPartTime ? '实习、兼职、长期兼职按实际录入工时计算工资，不按出勤天数折算。' : '正常工时 = 出勤天数 × 8小时；加班不计入月产值。' }}</p>
        <small v-if="attendanceError" class="attendance-error">{{ attendanceError }}</small>
      </section>

      <section v-if="!isPendingGeneration" class="editor-section">
        <h3>人工调整</h3>
        <div class="input-grid adjustments">
          <label>绩效奖罚<input v-model.number="performanceInput" :disabled="!canEdit" type="number" step="1" /></label>
          <label>夜班补贴<input v-model.number="lateNightInput" :disabled="!canEdit" type="number" step="1" /></label>
          <label>其他补贴<input v-model.number="subsidyInput" :disabled="!canEdit" type="number" step="1" /></label>
          <label>其他扣款<input v-model.number="deductionInput" :disabled="!canEdit" type="number" min="0" step="1" /></label>
          <label class="wide-field">最终提成金额<input v-model.number="commissionInput" :disabled="!canEdit" type="number" min="0" step="1" /></label>
          <label class="wide-field">调整原因<textarea v-model="adjustmentReason" :disabled="!canEdit" rows="2" placeholder="选填，记录本次调整原因" /></label>
        </div>
      </section>

      <section v-if="!isPendingGeneration" class="amount-list result-section">
        <h3>工资计算结果</h3>
        <div><span>基础工资<small>固定底薪</small></span><b>{{ money(record.base) }}</b></div>
        <div><span>提成<small>营业额提成</small></span><b class="accent">{{ money(record.commission) }}</b></div>
        <div><span>补贴<small>岗位、餐补、绩效等</small></span><b>{{ money(allowance) }}</b></div>
        <div><span>扣款<small>工服及其他扣款</small></span><b class="danger">-{{ money(deduction) }}</b></div>
        <div class="gross"><span>预计应发工资</span><b>{{ money(expectedGross) }}</b></div>
        <div v-if="['PAID', 'LOCKED'].includes(record.status || '')"><span>实发工资</span><b class="accent">{{ money(record.netPay ?? record.gross) }}</b></div>
      </section>

      <section v-if="!isPendingGeneration" class="commission-box">
        <header><b>提成计算依据</b><span>当前工资记录</span></header>
        <div><span>营业额</span><b>{{ revenue > 0 ? money(revenue) : '--' }}</b></div>
        <div><span>提成比例</span><b>{{ commissionRate }}</b></div>
        <div><span>提成金额</span><b class="accent">{{ money(record.commission) }}</b></div>
      </section>

      <details v-if="!isPendingGeneration" class="calculation-details"><summary>查看计算过程 <ChevronDown :size="16" /></summary><div>应发 = 基础工资 + 提成 + 各项补贴 + 加班工资 − 扣款。产值只使用正常工时 {{ productivityHours.toFixed(1) }} 小时，加班工时不参与产值计算。</div></details>
      <section v-if="showHistory && !isPendingGeneration" class="audit-timeline">
        <h3>修改与审核记录</h3>
        <div><i /><span><b>工资记录生成</b><small>{{ record.month }}</small></span></div>
        <div v-if="record.reviewedAt"><i /><span><b>{{ record.reviewNote || '审核完成' }}</b><small>{{ record.reviewedAt }}</small></span></div>
        <div v-if="record.paidAt"><i /><span><b>工资已发放</b><small>{{ record.paidAt }}</small></span></div>
        <div v-if="!record.reviewedAt"><i /><span><b>当前工资调整</b><small>{{ adjustmentReason || '暂无调整说明' }}</small></span></div>
      </section>

      <footer class="detail-actions">
        <button v-if="canEdit" class="primary-action wide" :disabled="busy || Boolean(attendanceError)" @click="isPendingGeneration ? emit('saveAttendance', record, isPartTime ? 0 : attendanceInput, overtimeInput, calculatedNormalHours) : emit('saveDetails', record, isPartTime ? 0 : attendanceInput, overtimeInput, calculatedNormalHours, detailPayload())"><Save :size="16" />{{ isPendingGeneration ? '保存工时' : '保存并重新计算' }}</button>
        <button v-if="canEdit && isPendingGeneration" class="secondary-action wide" :disabled="busy" @click="emit('preview')"><Clock3 :size="16" />进入生成预览</button>
        <template v-else>
          <button v-if="canReview && ['SUBMITTED', 'PENDING_REVIEW'].includes(record.status || '')" class="secondary-action" :disabled="busy" @click="emit('reject', record)"><XCircle :size="16" />退回修改</button>
          <button v-if="canReview && ['SUBMITTED', 'PENDING_REVIEW'].includes(record.status || '')" class="primary-action" :disabled="busy" @click="emit('approve', record)"><Check :size="16" />审核通过</button>
          <button v-else-if="canEdit && ['DRAFT', 'REJECTED'].includes(record.status || '')" class="primary-action wide" :disabled="busy" @click="emit('submit', record)"><Send :size="16" />提交审核</button>
          <button v-else-if="canPay && record.status === 'APPROVED'" class="primary-action wide" :disabled="busy" @click="emit('markPaid', record)"><Clock3 :size="16" />确认发放</button>
        </template>
        <button v-if="!isPendingGeneration" class="history-action wide" type="button" @click="showHistory = !showHistory"><History :size="16" />{{ showHistory ? '收起修改记录' : '查看修改记录' }}</button>
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
.attendance-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; margin-top: 8px; }
.attendance-form label { display: grid; gap: 5px; color: #526765; font-size: 12px; }
.attendance-form input { min-width: 0; min-height: 36px; padding: 7px 9px; border: 1px solid #ccdcd9; border-radius: 5px; background: #fff; font-size: 14px; }
.attendance-form > div { padding: 8px 9px; border: 1px solid #dce8e6; border-radius: 5px; background: #fff; }
.attendance-form > div span,.attendance-form > div b { display: block; }.attendance-form > div b { margin-top: 3px; color: #182424; }
.attendance-form button { grid-column: 1 / -1; min-height: 36px; border: 1px solid #276b65; border-radius: 5px; background: #276b65; color: #fff; font-weight: 600; cursor: pointer; }
.attendance-form button:disabled { opacity: .55; cursor: not-allowed; }.attendance-error { grid-column: 1 / -1; color: #c34b40; }
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
.salary-detail-panel { border: 1px solid #dfe8e6; border-radius: 7px; padding: 18px; box-shadow: 0 2px 10px rgba(25,73,68,.04); }
.editor-section { padding: 15px 0; border-bottom: 1px solid #e4ecea; }.editor-section h3,.result-section h3 { margin: 0 0 12px; font-size: 14px; color: #263a38; }
.input-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 10px; }.input-grid label { display: grid; gap: 5px; color: #526765; font-size: 12px; }.input-grid .wide-field { grid-column: 1/-1; }
.input-grid input,.input-grid select,.input-grid textarea { width: 100%; min-width: 0; min-height: 36px; box-sizing: border-box; padding: 7px 9px; border: 1px solid #ccdcd9; border-radius: 5px; background: #fff; color: #182424; font: inherit; }.input-grid textarea { resize: vertical; }.input-grid :disabled { background: #f3f6f5; color: #73827f; }
.hour-summary { display: grid; grid-template-columns: repeat(3,1fr); margin-top: 11px; border: 1px solid #dce8e6; border-radius: 5px; background: #f7fbfa; }.hour-summary div { padding: 9px 6px; text-align: center; border-right: 1px solid #dce8e6; }.hour-summary div:last-child { border-right: 0; }.hour-summary span { display: block; color: #6f817f; font-size: 11px; }.hour-summary b { display: block; margin-top: 4px; color: #244c48; font-size: 13px; }
.formula-note { margin: 8px 0 0; color: #748481; font-size: 11px; line-height: 1.5; }.result-section { padding-top: 15px; }
.overtime-kind { display: flex; align-items: center; justify-content: space-between; min-height: 36px; padding: 0 10px; border: 1px solid #dce8e6; border-radius: 5px; background: #f7fbfa; color: #526765; font-size: 12px; }.overtime-kind b { color: #244c48; font-size: 13px; }
.calculation-details { margin-top: 10px; border: 1px solid #dce8e6; border-radius: 5px; background: #f9fbfb; }.calculation-details summary { display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; color: #276b65; font-size: 13px; font-weight: 600; cursor: pointer; list-style: none; }.calculation-details summary::-webkit-details-marker { display: none; }.calculation-details[open] summary svg { transform: rotate(180deg); }.calculation-details div { padding: 10px 12px; border-top: 1px solid #e1eae8; color: #617370; font-size: 12px; line-height: 1.6; }
.detail-actions { flex-wrap: wrap; }.history-action { border: 0; background: transparent; color: #5c706d; font-weight: 500 !important; }
</style>
