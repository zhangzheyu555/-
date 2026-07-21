import { reactive } from 'vue'
import type { ExpenseClaim } from '../api/finance'
import { useFinanceStore } from '../stores/finance'

type FinanceConfirmationKind = 'reject'

interface FinanceConfirmation {
  open: boolean
  title: string
  message: string
  confirmLabel: string
  confirmVariant: 'primary' | 'danger'
  note: string
  noteLabel: string
  notePlaceholder: string
  noteMaxLength: number
  noteRequired: boolean
  busy: boolean
}

export function useFinanceActions() {
  const finance = useFinanceStore()
  const confirmation = reactive<FinanceConfirmation>({
    open: false,
    title: '',
    message: '',
    confirmLabel: '确认',
    confirmVariant: 'primary',
    note: '',
    noteLabel: '',
    notePlaceholder: '',
    noteMaxLength: 255,
    noteRequired: false,
    busy: false,
  })
  let pendingAction: { kind: FinanceConfirmationKind; expense: ExpenseClaim } | null = null

  async function approveExpense(expense: ExpenseClaim) {
    await finance.approveExpense(expense.id)
  }

  function rejectExpense(expense: ExpenseClaim) {
    if (confirmation.busy) return
    pendingAction = { kind: 'reject', expense }
    Object.assign(confirmation, {
      open: true,
      title: '请输入驳回原因',
      message: '',
      confirmLabel: '确认驳回',
      confirmVariant: 'danger',
      note: '票据或说明不完整，请补充后重新提交',
      noteLabel: '驳回原因',
      notePlaceholder: '说明需要补充或调整的内容',
      noteRequired: true,
    })
  }

  function cancelConfirmation() {
    if (confirmation.busy) return
    resetConfirmation()
  }

  async function confirmAction() {
    if (!pendingAction || confirmation.busy) return
    const action = pendingAction
    confirmation.busy = true
    try {
      await finance.rejectExpense(action.expense.id, confirmation.note)
      resetConfirmation()
    } catch {
      // finance store 已保留业务错误，弹窗保持打开以便重试。
    } finally {
      confirmation.busy = false
    }
  }

  function resetConfirmation() {
    confirmation.open = false
    confirmation.note = ''
    confirmation.noteRequired = false
    pendingAction = null
  }

  return {
    approveExpense,
    rejectExpense,
    confirmation,
    cancelConfirmation,
    confirmAction,
  }
}
