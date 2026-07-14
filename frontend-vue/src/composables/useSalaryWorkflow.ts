import { reactive, ref, type Ref } from 'vue'
import {
  approveSalaryRecord, deleteSalaryRecord, exportSalaryCsv,
  generateSalaryWithReport, lockSalaryRecord,
  markSalaryPaid, previewSalaryGeneration, rejectSalaryRecord,
  saveSalaryRecord, submitSalaryRecord, type SalaryGenerateReport,
  type SalaryRecord, type SalaryRecordPayload,
} from '../api/finance'
import { currentMonth, userError, isEditable } from './useSalaryPage'

export const WAGE_FIELDS: Array<{ key: keyof SalaryRecordPayload; label: string; kind: 'add' | 'minus' }> = [
  { key: 'base', label: '基本工资', kind: 'add' },
  { key: 'social', label: '社保补助', kind: 'add' },
  { key: 'post', label: '岗位工资', kind: 'add' },
  { key: 'meal', label: '餐补', kind: 'add' },
  { key: 'fullAttendance', label: '全勤', kind: 'add' },
  { key: 'commission', label: '提成', kind: 'add' },
  { key: 'overtime', label: '加班工资', kind: 'add' },
  { key: 'seniority', label: '工龄工资', kind: 'add' },
  { key: 'lateNight', label: '深夜班', kind: 'add' },
  { key: 'subsidy', label: '补贴', kind: 'add' },
  { key: 'performance', label: '绩效', kind: 'add' },
  { key: 'deductUniform', label: '扣工服费', kind: 'minus' },
  { key: 'returnUniform', label: '返工服费', kind: 'minus' },
]

export interface SalaryActionConfirmation {
  kind: 'reject' | 'delete' | 'mark-paid' | 'lock'
  record: SalaryRecord
  title: string
  message: string
  confirmLabel: string
  confirmVariant: 'primary' | 'danger'
  noteLabel?: string
  notePlaceholder?: string
}

export function emptyForm(): SalaryRecordPayload {
  return {
    storeId: '', month: currentMonth(), employeeId: '', employeeName: '',
    position: '', attendance: '', gross: 0, normalHours: 0, otHours: 0,
    workHours: 0, vacationLeft: 0, vacationNote: '', base: 0, social: 0,
    post: 0, meal: 0, fullAttendance: 0, commission: 0, overtime: 0,
    seniority: 0, lateNight: 0, subsidy: 0, performance: 0,
    deductUniform: 0, returnUniform: 0,
  }
}

export function recalcGross(form: SalaryRecordPayload) {
  const t = WAGE_FIELDS.reduce((sum, f) => {
    const v = Number(form[f.key] || 0)
    return sum + (f.kind === 'minus' ? -v : v)
  }, 0)
  form.gross = Math.max(0, Math.round(t))
}

export function useSalaryWorkflow(opts: {
  selectedMonth: Ref<string>,
  selectedStoreId: Ref<string>,
  selectedBrandId?: Ref<number | undefined>,
  hasValidMonth: Ref<boolean>,
  canGenerate: Ref<boolean>,
  canEdit: Ref<boolean>,
  pageError: Ref<string>,
  successMessage: Ref<string>,
  loadPage: () => Promise<void>,
}) {
  const generating = ref(false)
  const saving = ref(false)
  const actioningId = ref('')
  const deletingId = ref('')
  const formError = ref('')
  const previewLoading = ref(false)
  const previewData = ref<SalaryGenerateReport | null>(null)
  const showPreview = ref(false)
  const showDrawer = ref(false)
  const drawerMode = ref<'view' | 'edit'>('view')
  const drawerRecord = ref<SalaryRecord | null>(null)
  const form = reactive<SalaryRecordPayload>(emptyForm())
  const actionConfirmation = ref<SalaryActionConfirmation | null>(null)
  const actionConfirmationBusy = ref(false)
  const actionNote = ref('')

  function openDrawer(record: SalaryRecord, mode: 'view' | 'edit' = 'view') {
    drawerRecord.value = record
    drawerMode.value = opts.canEdit.value && isEditable(record.status) ? mode : 'view'
    Object.assign(form, {
      storeId: record.storeId, month: record.month,
      employeeId: record.employeeId || '', employeeName: record.employeeName,
      position: record.position || '', attendance: record.attendance || '',
      gross: Number(record.gross || 0), normalHours: Number(record.normalHours || 0),
      otHours: Number(record.otHours || 0), workHours: Number(record.workHours || 0),
      vacationLeft: Number(record.vacationLeft || 0), vacationNote: record.vacationNote || '',
      base: Number(record.base || 0), social: Number(record.social || 0),
      post: Number(record.post || 0), meal: Number(record.meal || 0),
      fullAttendance: Number(record.fullAttendance || 0), commission: Number(record.commission || 0),
      overtime: Number(record.overtime || 0), seniority: Number(record.seniority || 0),
      lateNight: Number(record.lateNight || 0), subsidy: Number(record.subsidy || 0),
      performance: Number(record.performance || 0), deductUniform: Number(record.deductUniform || 0),
      returnUniform: Number(record.returnUniform || 0),
    })
    formError.value = ''
    showDrawer.value = true
  }

  function closeDrawer() {
    showDrawer.value = false
    formError.value = ''
  }

  async function doSave() {
    formError.value = ''
    if (!form.storeId) { formError.value = '请选择门店'; return }
    if (!form.month) { formError.value = '请填写月份'; return }
    if (!form.employeeName.trim()) { formError.value = '请填写员工姓名'; return }
    saving.value = true
    try {
      await saveSalaryRecord(form, drawerRecord.value?.id)
      opts.successMessage.value = '工资记录已保存'
      showDrawer.value = false
      await opts.loadPage()
    } catch (e) {
      formError.value = userError(e, '保存失败。')
    } finally {
      saving.value = false
    }
  }

  async function doPreview() {
    if (!opts.canGenerate.value || opts.selectedStoreId.value === 'all') return
    previewLoading.value = true
    opts.pageError.value = ''
    try {
      previewData.value = await previewSalaryGeneration(opts.selectedStoreId.value, opts.selectedMonth.value)
      showPreview.value = true
    } catch (e) {
      opts.pageError.value = userError(e, '预览生成失败。')
    } finally {
      previewLoading.value = false
    }
  }

  async function doGenerate() {
    if (!opts.canGenerate.value || opts.selectedStoreId.value === 'all') return
    generating.value = true
    opts.pageError.value = ''
    opts.successMessage.value = ''
    try {
      const report = await generateSalaryWithReport({
        storeId: opts.selectedStoreId.value,
        month: opts.selectedMonth.value,
      })
      const parts = [`已生成 ${report.generated} 条工资记录`]
      if (report.skipped > 0) parts.push(`跳过 ${report.skipped} 条`)
      if (report.errors > 0) parts.push(`${report.errors} 条异常`)
      if (report.skipDetails?.length) parts.push('点击"预览"查看跳过原因')
      opts.successMessage.value = parts.join('，')
      previewData.value = null
      showPreview.value = false
      await opts.loadPage()
    } catch (e) {
      opts.pageError.value = userError(e, '工资记录生成失败。')
    } finally {
      generating.value = false
    }
  }

  async function doExport() {
    if (!opts.hasValidMonth.value) {
      opts.pageError.value = '请选择有效月份。'
      return
    }
    try {
      await exportSalaryCsv({
        month: opts.selectedMonth.value || undefined,
        brandId: opts.selectedBrandId?.value,
        storeId: opts.selectedStoreId.value === 'all' ? undefined : opts.selectedStoreId.value,
      })
      opts.successMessage.value = '工资 CSV 已导出'
    } catch (e) {
      opts.pageError.value = userError(e, '导出失败。')
    }
  }

  async function doSubmit(r: SalaryRecord) {
    actioningId.value = r.id; opts.pageError.value = ''
    try { await submitSalaryRecord(r.id); opts.successMessage.value = '已提交审核'; await opts.loadPage() }
    catch (e) { opts.pageError.value = userError(e, '提交审核失败。') }
    finally { actioningId.value = '' }
  }

  async function doApprove(r: SalaryRecord) {
    actioningId.value = r.id; opts.pageError.value = ''
    try { await approveSalaryRecord(r.id); opts.successMessage.value = '已审核通过'; await opts.loadPage() }
    catch (e) { opts.pageError.value = userError(e, '审核失败。') }
    finally { actioningId.value = '' }
  }

  function doReject(r: SalaryRecord) {
    actionNote.value = '请调整工资明细后重新提交'
    actionConfirmation.value = {
      kind: 'reject',
      record: r,
      title: '驳回工资记录',
      message: `将驳回 ${r.employeeName} ${r.month} 的工资记录。`,
      confirmLabel: '确认驳回',
      confirmVariant: 'danger',
      noteLabel: '驳回原因',
      notePlaceholder: '请输入驳回原因',
    }
  }

  function doDelete(r: SalaryRecord) {
    if (!opts.canEdit.value || !isEditable(r.status)) return
    actionNote.value = ''
    actionConfirmation.value = {
      kind: 'delete',
      record: r,
      title: '删除工资记录',
      message: `确定删除 ${r.employeeName} ${r.month} 的工资记录？`,
      confirmLabel: '确认删除',
      confirmVariant: 'danger',
    }
  }

  function doMarkPaid(r: SalaryRecord) {
    actionNote.value = ''
    actionConfirmation.value = {
      kind: 'mark-paid',
      record: r,
      title: '确认工资发放',
      message: `确定将 ${r.employeeName} 的工资标记为已发放？`,
      confirmLabel: '确认标记',
      confirmVariant: 'primary',
    }
  }

  function doLock(r: SalaryRecord) {
    actionNote.value = ''
    actionConfirmation.value = {
      kind: 'lock',
      record: r,
      title: '锁定工资记录',
      message: '锁定后该记录将不能修改，确定继续？',
      confirmLabel: '确认锁定',
      confirmVariant: 'primary',
    }
  }

  function cancelActionConfirmation() {
    if (actionConfirmationBusy.value) return
    actionConfirmation.value = null
    actionNote.value = ''
  }

  async function confirmAction() {
    const confirmation = actionConfirmation.value
    if (!confirmation || actionConfirmationBusy.value) return

    actionConfirmationBusy.value = true
    opts.pageError.value = ''
    if (confirmation.kind === 'delete') deletingId.value = confirmation.record.id
    else actioningId.value = confirmation.record.id

    try {
      if (confirmation.kind === 'reject') {
        await rejectSalaryRecord(confirmation.record.id, actionNote.value || '请调整后重新提交')
        opts.successMessage.value = '已驳回'
      } else if (confirmation.kind === 'delete') {
        await deleteSalaryRecord(confirmation.record.id)
        opts.successMessage.value = '工资记录已删除'
      } else if (confirmation.kind === 'mark-paid') {
        await markSalaryPaid(confirmation.record.id)
        opts.successMessage.value = '已标记发放'
      } else {
        await lockSalaryRecord(confirmation.record.id)
        opts.successMessage.value = '工资记录已锁定'
      }
      await opts.loadPage()
    } catch (e) {
      const fallback = confirmation.kind === 'reject'
        ? '驳回失败。'
        : confirmation.kind === 'delete'
          ? '删除失败。'
          : '操作失败。'
      opts.pageError.value = userError(e, fallback)
    } finally {
      actioningId.value = ''
      deletingId.value = ''
      actionConfirmationBusy.value = false
      actionConfirmation.value = null
      actionNote.value = ''
    }
  }

  return {
    // generation state
    generating, previewLoading, previewData, showPreview,
    // drawer state
    showDrawer, drawerMode, drawerRecord, form, formError, saving,
    // action state
    actioningId, deletingId, actionConfirmation, actionConfirmationBusy, actionNote,
    // methods
    openDrawer, closeDrawer, doSave, doPreview, doGenerate, doExport,
    doSubmit, doApprove, doReject, doDelete, doMarkPaid, doLock,
    cancelActionConfirmation, confirmAction,
  }
}
