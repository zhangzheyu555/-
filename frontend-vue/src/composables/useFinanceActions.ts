import { useRouter, type RouteLocationRaw } from 'vue-router'
import type { ExpenseClaim, FinanceDataCheck, FinanceSalaryCheck, ProfitEntry } from '../api/finance'
import type { RoleTodoItem } from '../api/todos'
import { useFinanceStore } from '../stores/finance'

export function useFinanceActions() {
  const router = useRouter()
  const finance = useFinanceStore()

  function financeRouteFor(item: RoleTodoItem): RouteLocationRaw {
    const text = `${item.id} ${item.title} ${item.summary} ${item.sourceModule} ${item.dataSource}`.toLowerCase()
    if (text.includes('expense') || text.includes('报销')) return '/expenses'
    if (text.includes('salary') || text.includes('工资')) return '/salary'
    if (text.includes('data') || text.includes('数据')) return '/finance-data-check'
    if (text.includes('profit') || text.includes('利润')) return '/profit-table'
    return '/finance'
  }

  function openTodoSource(item: RoleTodoItem) {
    void router.push(financeRouteFor(item))
  }

  async function approveExpense(expense: ExpenseClaim) {
    await finance.approveExpense(expense.id)
  }

  async function rejectExpense(expense: ExpenseClaim) {
    const note = window.prompt('请输入驳回原因', '票据或说明不完整，请补充后重新提交')
    if (note === null) return
    await finance.rejectExpense(expense.id, note)
  }

  async function requestExpenseInfo(expense: ExpenseClaim) {
    const note = window.prompt('请输入需要补充的资料', '请补充票据、付款截图或报销说明')
    if (note === null) return
    await finance.requestInfo(expense.id, note)
  }

  async function escalateExpense(expense: ExpenseClaim) {
    const reason = window.prompt('请输入上报老板的原因', '报销金额或票据情况需要老板确认')
    if (reason === null) return
    await finance.escalate(`expense-${expense.id}`, reason)
  }

  async function completeTodo(item: RoleTodoItem) {
    await finance.completeTodo(item)
  }

  async function escalateTodo(item: RoleTodoItem) {
    const reason = window.prompt('请输入上报老板的原因', '财务判断该事项需要老板确认')
    if (reason === null) return
    await finance.escalate(item.id, reason)
  }

  async function markProfitChecked(entry: ProfitEntry) {
    await finance.markProfitChecked(entry)
  }

  function openProfitReport(entry: ProfitEntry) {
    void router.push({ path: '/profit-table', query: { storeId: entry.storeId, month: entry.month } })
  }

  async function requestProfitInfo(entry: ProfitEntry) {
    const note = window.prompt('请输入需要门店补充说明的内容', '请说明本月利润异常原因，并核对成本、工资和报销数据')
    if (note === null) return
    await finance.requestTodoInfo(`profit-risk-${entry.storeId}-${entry.month}`, note)
  }

  async function escalateProfit(entry: ProfitEntry) {
    const reason = window.prompt('请输入上报老板的原因', '利润异常需要老板确认处理方向')
    if (reason === null) return
    await finance.escalate(`profit-risk-${entry.storeId}-${entry.month}`, reason)
  }

  async function escalateSalary(check: Pick<FinanceSalaryCheck, 'id' | 'employeeName'>) {
    const reason = window.prompt('请输入上报老板的原因', `${check.employeeName || '员工'}工资异常需要老板确认`)
    if (reason === null) return
    await finance.escalate(`salary-check-${check.id}`, reason)
  }

  async function markDataCheckChecked(item: FinanceDataCheck) {
    await finance.markDataCheckChecked(item)
  }

  async function escalateDataCheck(item: FinanceDataCheck) {
    const reason = window.prompt('请输入上报老板的原因', '财务数据核对结果需要老板确认')
    if (reason === null) return
    await finance.escalate(item.id, reason)
  }

  return {
    approveExpense,
    completeTodo,
    escalateDataCheck,
    escalateExpense,
    escalateProfit,
    escalateSalary,
    escalateTodo,
    financeRouteFor,
    markDataCheckChecked,
    markProfitChecked,
    openProfitReport,
    openTodoSource,
    rejectExpense,
    requestExpenseInfo,
    requestProfitInfo,
  }
}
