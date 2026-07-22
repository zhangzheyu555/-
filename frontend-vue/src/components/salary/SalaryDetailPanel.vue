<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Building2, Check, ChevronDown, Clock3, History, Save, Send, UserRound, XCircle } from 'lucide-vue-next'
import type { SalaryRecord, SalaryRecordPayload } from '../../api/finance'
import { isHourlySalaryRecord, money, statusClass, statusLabel, wholeNumber } from '../../composables/useSalaryPage'

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
  saveDetails: [record: SalaryRecord, attendanceDays: number, overtimeHours: number, normalHours: number, attendanceChanged: boolean, payload: SalaryRecordPayload]
}>()

const attendanceInput = ref(0)
const partTimeHoursInput = ref(0)
const overtimeInput = ref(0)
const performanceInput = ref(0)
const lateNightInput = ref(0)
const subsidyInput = ref(0)
const seniorityInput = ref(0)
const birthdayBenefitInput = ref(0)
const deductionInput = ref(0)
const commissionInput = ref(0)
const vacationLeftInput = ref(0)
const vacationNoteInput = ref('')
const legacyCalculationNote = ref('')
const showHistory = ref(false)
const dirtyFields = ref(new Set<string>())

function truncatedInput(value?: number) {
  const number = Number(value || 0)
  return Number.isFinite(number) ? Math.trunc(number) : 0
}

function markDirty(field: string) {
  const next = new Set(dirtyFields.value)
  next.add(field)
  dirtyFields.value = next
}

function effectiveInput(field: string, displayedValue: number, originalValue?: number) {
  return dirtyFields.value.has(field) ? Number(displayedValue || 0) : Number(originalValue || 0)
}

function revenueMoney(value?: number) {
  return money(value)
}

const isPartTime = computed(() => isHourlySalaryRecord(props.record))
const attendanceError = computed(() => {
  if (!isPartTime.value && (attendanceInput.value < 0 || attendanceInput.value > 31)) return '出勤天数应在0—31天之间'
  if (isPartTime.value && (partTimeHoursInput.value < 0 || partTimeHoursInput.value > 744)) return '正常工时应在0—744小时之间'
  if (overtimeInput.value < 0 || overtimeInput.value > 300) return '加班小时应在0—300小时之间'
  return ''
})
const vacationError = computed(() => {
  const vacationLeft = Number(vacationLeftInput.value)
  if (!Number.isFinite(vacationLeft) || vacationLeft < 0 || vacationLeft > 365) return '假期余额应在0—365天之间'
  if (vacationNoteInput.value.trim().length > 255) return '休息日期备注不能超过255个字'
  return ''
})
const adjustmentError = computed(() => {
  const seniority = Number(seniorityInput.value)
  if (!Number.isFinite(seniority) || seniority < 0) return '工龄工资不能小于0元'
  const birthdayBenefit = Number(birthdayBenefitInput.value)
  if (!Number.isFinite(birthdayBenefit) || birthdayBenefit < 0) return '员工福利（生日）不能小于0元'
  const lateNight = Number(lateNightInput.value)
  if (!Number.isFinite(lateNight) || lateNight < 0) return '深夜加班金额不能小于0元'
  return ''
})
const originalNormalHours = computed(() => props.record?.normalHours !== undefined && props.record?.normalHours !== null
  ? Number(props.record.normalHours)
  : Math.max(0, Number(props.record?.workHours || 0) - Number(props.record?.otHours || 0)))
const effectiveNormalHours = computed(() => isPartTime.value
  ? effectiveInput('normalHours', partTimeHoursInput.value, originalNormalHours.value)
  : dirtyFields.value.has('attendanceDays')
    ? Number(attendanceInput.value || 0) * 8
    : originalNormalHours.value)
const effectiveOvertimeHours = computed(() => effectiveInput('otHours', overtimeInput.value, props.record?.otHours))
const attendanceChanged = computed(() => ['attendanceDays', 'normalHours', 'otHours']
  .some((field) => dirtyFields.value.has(field)))
const calculatedTotalHours = computed(() => {
  if (!attendanceChanged.value && props.record?.workHours !== undefined && props.record?.workHours !== null) {
    return Number(props.record.workHours)
  }
  return effectiveNormalHours.value + effectiveOvertimeHours.value
})
watch(() => props.record, (record) => {
  const match = String(record?.attendance || '').match(/[\d.]+/)
  attendanceInput.value = match ? Number(match[0]) : 0
  partTimeHoursInput.value = truncatedInput(record?.normalHours !== undefined && record?.normalHours !== null
    ? Number(record.normalHours)
    : Math.max(0, Number(record?.workHours || 0) - Number(record?.otHours || 0)))
  overtimeInput.value = truncatedInput(record?.otHours)
  performanceInput.value = truncatedInput(record?.performance)
  lateNightInput.value = truncatedInput(record?.lateNight)
  subsidyInput.value = truncatedInput(record?.subsidy)
  seniorityInput.value = truncatedInput(record?.seniority)
  birthdayBenefitInput.value = truncatedInput(record?.birthdayBenefit)
  deductionInput.value = truncatedInput(record?.deductUniform)
  commissionInput.value = truncatedInput(record?.commission)
  vacationLeftInput.value = Number(record?.vacationLeft || 0)
  const note = String(record?.vacationNote || '').trim()
  if (/^考勤来源[：:]/.test(note)) {
    legacyCalculationNote.value = note
    vacationNoteInput.value = ''
  } else {
    legacyCalculationNote.value = ''
    vacationNoteInput.value = note
  }
  dirtyFields.value = new Set()
  showHistory.value = false
}, { immediate: true })

const effectivePerformance = computed(() => effectiveInput('performance', performanceInput.value, props.record?.performance))
const effectiveLateNight = computed(() => effectiveInput('lateNight', lateNightInput.value, props.record?.lateNight))
const effectiveSubsidy = computed(() => effectiveInput('subsidy', subsidyInput.value, props.record?.subsidy))
const effectiveSeniority = computed(() => effectiveInput('seniority', seniorityInput.value, props.record?.seniority))
const effectiveBirthdayBenefit = computed(() => effectiveInput('birthdayBenefit', birthdayBenefitInput.value, props.record?.birthdayBenefit))
const effectiveDeduction = computed(() => effectiveInput('deductUniform', deductionInput.value, props.record?.deductUniform))
const effectiveCommission = computed(() => effectiveInput('commission', commissionInput.value, props.record?.commission))

const fixedAllowance = computed(() => {
  const row = props.record
  return Number(row?.social || 0) + Number(row?.post || 0) + Number(row?.meal || 0)
    + Number(row?.fullAttendance || 0)
})
const manualAllowance = computed(() => effectiveSubsidy.value + effectivePerformance.value)
const deduction = computed(() => effectiveDeduction.value + Number(props.record?.returnUniform || 0))
const commissionRate = computed(() => props.revenue > 0
  ? `${(effectiveCommission.value / props.revenue * 100).toFixed(2)}%`
  : '--')
const busy = computed(() => Boolean(props.record && props.actioningId && props.actioningId === props.record.id))
const isPendingGeneration = computed(() => props.record?.status === 'PENDING_GENERATION')
const canEditRecord = computed(() => props.canEdit && (
  isPendingGeneration.value || ['DRAFT', 'REJECTED'].includes(props.record?.status || '')
))
const productivityHours = computed(() => calculatedTotalHours.value)

// 保底补足和部分历史调整只体现在后端生成的 gross 中，没有独立 API 字段。
// 保留原 gross 与原始全部明细净额的有符号差额，保证修改任一可编辑项时不会覆盖历史工资结果。
const preservedGuaranteeTopUp = computed(() => {
  const row = props.record
  if (!row) return 0
  const storedGross = Number(row.gross)
  if (row.gross === undefined || row.gross === null || !Number.isFinite(storedGross)) return 0
  const originalComponentGross = Number(row.base || 0) + Number(row.social || 0) + Number(row.post || 0)
    + Number(row.meal || 0) + Number(row.fullAttendance || 0) + Number(row.commission || 0)
    + Number(row.overtime || 0) + Number(row.seniority || 0) + Number(row.birthdayBenefit || 0)
    + Number(row.lateNight || 0) + Number(row.subsidy || 0) + Number(row.performance || 0)
    - Number(row.deductUniform || 0) - Number(row.returnUniform || 0)
  return storedGross - originalComponentGross
})
const expectedBeforeLateNight = computed(() => {
  const row = props.record
  return Number(row?.base || 0) + Number(row?.social || 0) + Number(row?.post || 0) + Number(row?.meal || 0)
    + Number(row?.fullAttendance || 0) + effectiveSeniority.value + effectiveBirthdayBenefit.value
    + effectiveSubsidy.value + effectivePerformance.value + effectiveCommission.value
    + Number(row?.overtime || 0) - effectiveDeduction.value - Number(row?.returnUniform || 0)
    + preservedGuaranteeTopUp.value
})
const wageAdjustmentDirty = computed(() => [
  'seniority', 'birthdayBenefit', 'performance', 'lateNight', 'subsidy', 'deductUniform', 'commission',
].some((field) => dirtyFields.value.has(field)))
const expectedGross = computed(() => {
  const originalGross = Number(props.record?.gross)
  if (!wageAdjustmentDirty.value && props.record?.gross !== undefined && props.record?.gross !== null && Number.isFinite(originalGross)) {
    return originalGross
  }
  return expectedBeforeLateNight.value + effectiveLateNight.value
})
function detailPayload(): SalaryRecordPayload {
  const row = props.record!
  return {
    storeId: row.storeId, month: row.month, employeeId: row.employeeId, employeeName: row.employeeName,
    position: row.position,
    attendance: dirtyFields.value.has('attendanceDays') ? `${attendanceInput.value}天` : row.attendance,
    normalHours: effectiveNormalHours.value,
    otHours: effectiveOvertimeHours.value, workHours: calculatedTotalHours.value, vacationLeft: Number(vacationLeftInput.value || 0),
    vacationNote: vacationNoteInput.value.trim() || legacyCalculationNote.value || undefined,
    base: row.base, social: row.social, post: row.post, meal: row.meal,
    fullAttendance: row.fullAttendance, commission: effectiveCommission.value, overtime: row.overtime,
    seniority: effectiveSeniority.value, birthdayBenefit: effectiveBirthdayBenefit.value,
    lateNight: effectiveLateNight.value, subsidy: effectiveSubsidy.value,
    performance: effectivePerformance.value, deductUniform: effectiveDeduction.value, returnUniform: row.returnUniform,
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
          <label v-if="!isPartTime">出勤天数<input v-model.number="attendanceInput" :disabled="!canEditRecord" type="number" min="0" max="31" step="0.5" @input="markDirty('attendanceDays')" /></label>
          <label v-else>正常工时<input v-model.number="partTimeHoursInput" :disabled="!canEditRecord" type="number" min="0" max="744" step="1" @input="markDirty('normalHours')" /></label>
          <label>加班小时<input v-model.number="overtimeInput" :disabled="!canEditRecord" type="number" min="0" max="300" step="1" @input="markDirty('otHours')" /></label>
          <div class="wide-field overtime-kind"><span>加班类型</span><b>普通加班</b></div>
        </div>
        <div class="hour-summary">
          <div><span>正常工时</span><b>{{ wholeNumber(effectiveNormalHours) }} 小时</b></div>
          <div><span>总工时</span><b>{{ wholeNumber(calculatedTotalHours) }} 小时</b></div>
          <div><span>产值工时</span><b>{{ wholeNumber(productivityHours) }} 小时</b></div>
        </div>
        <p class="formula-note">{{ isPartTime ? '实习、兼职、长期兼职按实际录入工时计算工资；正常与加班合计后，按半工时计入门店经营指标。' : '正常工时 = 出勤天数 × 8小时；正常工时与加班工时都计入月产值。' }}</p>
        <small v-if="attendanceError" class="attendance-error">{{ attendanceError }}</small>
      </section>

      <section v-if="!isPendingGeneration" class="editor-section">
        <h3>人工调整</h3>
        <div class="input-grid adjustments">
          <label>工龄工资（元）<input v-model.number="seniorityInput" :disabled="!canEditRecord" type="number" min="0" step="1" @input="markDirty('seniority')" /></label>
          <label>员工福利（生日）<input v-model.number="birthdayBenefitInput" :disabled="!canEditRecord" type="number" min="0" step="1" @input="markDirty('birthdayBenefit')" /></label>
          <label>绩效奖罚<input v-model.number="performanceInput" :disabled="!canEditRecord" type="number" step="1" @input="markDirty('performance')" /></label>
          <label>深夜加班（元）<input v-model.number="lateNightInput" :disabled="!canEditRecord" type="number" min="0" step="1" @input="markDirty('lateNight')" /></label>
          <label>其他补贴<input v-model.number="subsidyInput" :disabled="!canEditRecord" type="number" step="1" @input="markDirty('subsidy')" /></label>
          <label>其他扣款<input v-model.number="deductionInput" :disabled="!canEditRecord" type="number" min="0" step="1" @input="markDirty('deductUniform')" /></label>
          <label class="wide-field">最终提成金额<input v-model.number="commissionInput" :disabled="!canEditRecord" type="number" min="0" step="1" @input="markDirty('commission')" /></label>
        </div>
        <p class="adjustment-help">工龄工资会按现有档位带入，也可直接手动输入金额。深夜加班按元手动输入，会在保底工资之外直接相加。<span v-if="preservedGuaranteeTopUp !== 0">当前系统生成的保底补足/历史差额 {{ money(preservedGuaranteeTopUp) }} 会原样保留。</span></p>
        <div class="night-overtime-preview">
          <span>不含深夜加班 {{ money(expectedBeforeLateNight) }}</span>
          <span>＋ 深夜加班 {{ money(lateNightInput) }}</span>
          <b>＝ {{ money(expectedGross) }}</b>
        </div>
        <small v-if="adjustmentError" class="attendance-error">{{ adjustmentError }}</small>
      </section>

      <section v-if="!isPendingGeneration" class="editor-section vacation-section">
        <h3>假期</h3>
        <div class="input-grid">
          <label>假期余额（天）<input v-model.number="vacationLeftInput" :disabled="!canEditRecord" type="number" min="0" max="365" step="0.5" /></label>
          <label class="wide-field">休息日期备注<textarea v-model="vacationNoteInput" :disabled="!canEditRecord" rows="3" maxlength="255" placeholder="例如：7月5日、12日、19日休息" /></label>
        </div>
        <div class="vacation-note-meta"><span>记录本月具体休息日期，方便后续核对。</span><span>{{ vacationNoteInput.length }}/255</span></div>
        <small v-if="vacationError" class="attendance-error">{{ vacationError }}</small>
      </section>

      <section v-if="!isPendingGeneration" class="amount-list result-section">
        <h3>工资计算结果</h3>
        <div><span>基础工资<small>固定底薪</small></span><b>{{ money(record.base) }}</b></div>
        <div><span>提成<small>当前调整后的最终提成</small></span><b class="accent">{{ money(commissionInput) }}</b></div>
        <div><span>加班工资<small>根据已生成的加班金额</small></span><b>{{ money(record.overtime) }}</b></div>
        <div><span>工龄工资<small>沿用现有工龄档位</small></span><b>{{ money(seniorityInput) }}</b></div>
        <div><span>员工福利（生日）<small>符合条件员工生日当月享受</small></span><b>{{ money(birthdayBenefitInput) }}</b></div>
        <div v-if="preservedGuaranteeTopUp !== 0"><span>保底补足/历史差额<small>保留原工资结果中未单独列出的金额</small></span><b>{{ money(preservedGuaranteeTopUp) }}</b></div>
        <div><span>深夜加班<small>手填金额，在保底工资之外直接相加</small></span><b class="accent">{{ money(lateNightInput) }}</b></div>
        <div><span>固定补助<small>社保、岗位、餐补、全勤</small></span><b>{{ money(fixedAllowance) }}</b></div>
        <div><span>人工补助/奖罚<small>绩效、其他补贴</small></span><b>{{ money(manualAllowance) }}</b></div>
        <div><span>扣款<small>工服及其他扣款</small></span><b class="danger">-{{ money(deduction) }}</b></div>
        <div class="gross"><span>预计应发工资</span><b>{{ money(expectedGross) }}</b></div>
        <div v-if="['PAID', 'LOCKED'].includes(record.status || '')"><span>实发工资</span><b class="accent">{{ money(record.netPay ?? record.gross) }}</b></div>
      </section>

      <section v-if="!isPendingGeneration" class="commission-box">
        <header><b>提成计算依据</b><span>当前工资记录</span></header>
        <div><span>营业额</span><b>{{ revenue > 0 ? revenueMoney(revenue) : '--' }}</b></div>
        <div><span>提成比例</span><b>{{ commissionRate }}</b></div>
        <div><span>提成金额</span><b class="accent">{{ money(commissionInput) }}</b></div>
      </section>

      <details v-if="!isPendingGeneration" class="calculation-details">
        <summary>查看计算过程 <ChevronDown :size="16" /></summary>
        <div>
          <p>应发 = 基础工资 + 保底补足/历史差额 + 提成 + 普通加班工资 + 工龄工资 + 员工福利（生日） + 深夜加班 + 固定补助 + 人工补助/奖罚 − 扣款。</p>
          <p>深夜加班按手填金额直接加在保底工资之外。例如保底3000元、深夜加班200元，合计就是3200元。</p>
          <p>工龄工资仍按满半年100元、满1年200元、满1年半300元、满2年及以上400元封顶；在职全职与长期兼职员工生日所在工资月份享受200元生日福利，普通兼职不享受。</p>
          <p>产值使用正常工时与加班工时合计 {{ wholeNumber(productivityHours) }} 小时；兼职、实习在门店经营指标中按半工时计算。</p>
        </div>
      </details>
      <section v-if="showHistory && !isPendingGeneration" class="audit-timeline">
        <h3>修改与审核记录</h3>
        <div><i /><span><b>工资记录生成</b><small>{{ record.month }}</small></span></div>
        <div v-if="record.reviewedAt"><i /><span><b>{{ record.reviewNote || '审核完成' }}</b><small>{{ record.reviewedAt }}</small></span></div>
        <div v-if="record.paidAt"><i /><span><b>工资已发放</b><small>{{ record.paidAt }}</small></span></div>
        <div v-if="!record.reviewedAt"><i /><span><b>休假记录</b><small>{{ vacationNoteInput || '暂无休息日期备注' }}</small></span></div>
      </section>

      <footer class="detail-actions">
        <button v-if="canEditRecord" class="primary-action wide" :disabled="busy || Boolean(attendanceError) || Boolean(vacationError) || Boolean(adjustmentError)" @click="isPendingGeneration ? emit('saveAttendance', record, isPartTime ? 0 : attendanceInput, effectiveOvertimeHours, effectiveNormalHours) : emit('saveDetails', record, isPartTime ? 0 : attendanceInput, effectiveOvertimeHours, effectiveNormalHours, attendanceChanged, detailPayload())"><Save :size="16" />{{ isPendingGeneration ? '保存工时' : '保存工资与假期' }}</button>
        <button v-if="canEditRecord && isPendingGeneration" class="secondary-action wide" :disabled="busy" @click="emit('preview')"><Clock3 :size="16" />进入生成预览</button>
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
.formula-note { margin: 8px 0 0; color: #748481; font-size: 11px; line-height: 1.5; }.adjustment-help { margin: 9px 0 0; color: #748481; font-size: 11px; line-height: 1.6; }.adjustment-help span { display: block; margin-top: 3px; }.result-section { padding-top: 15px; }
.night-overtime-preview { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; margin-top: 9px; padding: 9px 10px; border: 1px solid #dce8e6; border-radius: 5px; background: #f7fbfa; color: #526765; font-size: 12px; }.night-overtime-preview b { margin-left: auto; color: #176c64; font-size: 14px; font-variant-numeric: tabular-nums; }
.overtime-kind { display: flex; align-items: center; justify-content: space-between; min-height: 36px; padding: 0 10px; border: 1px solid #dce8e6; border-radius: 5px; background: #f7fbfa; color: #526765; font-size: 12px; }.overtime-kind b { color: #244c48; font-size: 13px; }
.calculation-details { margin-top: 10px; border: 1px solid #dce8e6; border-radius: 5px; background: #f9fbfb; }.calculation-details summary { display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; color: #276b65; font-size: 13px; font-weight: 600; cursor: pointer; list-style: none; }.calculation-details summary::-webkit-details-marker { display: none; }.calculation-details[open] summary svg { transform: rotate(180deg); }.calculation-details div { padding: 10px 12px; border-top: 1px solid #e1eae8; color: #617370; font-size: 12px; line-height: 1.6; }.calculation-details p { margin: 0; }.calculation-details p + p { margin-top: 7px; }
.vacation-note-meta { display: flex; justify-content: space-between; gap: 12px; margin-top: 7px; color: #748481; font-size: 11px; }
.detail-actions { flex-wrap: wrap; }.history-action { border: 0; background: transparent; color: #5c706d; font-weight: 500 !important; }
</style>
