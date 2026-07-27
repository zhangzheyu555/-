export const EXPENSE_STATUS_OPTIONS = [
  { value: '草稿', label: '草稿' },
  { value: '待审核', label: '待审核' },
  { value: '待补资料', label: '待补资料' },
  { value: '已驳回', label: '已驳回' },
  { value: '已完成', label: '已完成' },
] as const

export type CanonicalExpenseStatus = typeof EXPENSE_STATUS_OPTIONS[number]['value']
export type ExpenseStatusTone = 'ok' | 'bad' | 'info' | 'warn'

const STATUS_ALIASES: Record<string, CanonicalExpenseStatus> = {
  DRAFT: '草稿',
  PENDING: '待审核',
  REQUEST_INFO: '待补资料',
  REJECTED: '已驳回',
  APPROVED: '已完成',
  草稿: '草稿',
  待审核: '待审核',
  待补资料: '待补资料',
  已驳回: '已驳回',
  已完成: '已完成',
  已通过: '已完成',
}

export function normalizeExpenseStatus(status?: string | null) {
  const value = String(status || '').trim()
  if (!value) return ''
  return STATUS_ALIASES[value] || STATUS_ALIASES[value.toUpperCase()] || value
}

export function expenseStatusLabel(status?: string | null) {
  return normalizeExpenseStatus(status) || '待审核'
}

export function expenseStatusTone(status?: string | null): ExpenseStatusTone {
  const normalized = normalizeExpenseStatus(status)
  if (normalized === '已完成') return 'ok'
  if (normalized === '已驳回') return 'bad'
  if (normalized === '草稿') return 'info'
  return 'warn'
}

export function isExpenseCompleted(status?: string | null) {
  return normalizeExpenseStatus(status) === '已完成'
}

export function isExpenseUnfinished(status?: string | null) {
  return !isExpenseCompleted(status)
}

export function isExpenseReviewable(status?: string | null) {
  return normalizeExpenseStatus(status) === '待审核'
}

export function isExpenseEditable(status?: string | null) {
  return ['草稿', '待补资料', '已驳回'].includes(normalizeExpenseStatus(status))
}

export function isExpenseSupplementable(status?: string | null) {
  return normalizeExpenseStatus(status) === '待补资料'
}

export function needsExpenseSubmission(status?: string | null) {
  return isExpenseEditable(status)
}
