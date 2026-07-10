import { defineStore } from 'pinia'
import {
  approveExpense,
  completeFinanceTodo,
  escalateFinanceTodo,
  getExpenses,
  getFinanceTodos,
  getFinanceWorkbench,
  getProfitEntries,
  getSalaryRecords,
  rejectExpense,
  requestExpenseInfo,
  requestFinanceTodoInfo,
  type ExpenseClaim,
  type FinanceDataCheck,
  type FinanceSalaryCheck,
  type FinanceSummary,
  type FinanceWorkbench,
  type ProfitEntry,
  type SalaryRecord,
} from '../api/finance'
import type { RoleTodoItem } from '../api/todos'

export type FinanceTab = 'overview' | 'expenses' | 'profit-risk' | 'salary' | 'data-check' | 'done' | 'assistant'

interface FinanceState {
  summary: FinanceSummary
  todoItems: RoleTodoItem[]
  expenseReviews: ExpenseClaim[]
  profitRisks: ProfitEntry[]
  salaryChecks: FinanceSalaryCheck[]
  dataChecks: FinanceDataCheck[]
  doneItems: RoleTodoItem[]
  assistantPrompts: string[]
  activeTab: FinanceTab
  loading: boolean
  actioningId: string
  error: string
  actionMessage: string
}

const emptySummary: FinanceSummary = {
  pendingExpenseCount: 0,
  profitRiskCount: 0,
  salaryCheckCount: 0,
  escalatedCount: 0,
  headline: '今日财务重点：暂无必须处理的事项。',
}

export const useFinanceStore = defineStore('finance', {
  state: (): FinanceState => ({
    summary: emptySummary,
    todoItems: [],
    expenseReviews: [],
    profitRisks: [],
    salaryChecks: [],
    dataChecks: [],
    doneItems: [],
    assistantPrompts: [
      '本月利润最低的门店有哪些？',
      '哪些报销还没有审核？',
      '哪些门店工资占比偏高？',
      '利润异常主要原因是什么？',
    ],
    activeTab: 'overview',
    loading: false,
    actioningId: '',
    error: '',
    actionMessage: '',
  }),
  actions: {
    setTab(tab: string | null | undefined) {
      const normalized = String(tab || 'overview') as FinanceTab
      this.activeTab = ['overview', 'expenses', 'profit-risk', 'salary', 'data-check', 'done', 'assistant'].includes(normalized)
        ? normalized
        : 'overview'
    },
    async load() {
      this.loading = true
      this.error = ''
      try {
        let data: FinanceWorkbench
        try {
          data = await getFinanceWorkbench()
        } catch {
          data = await aggregateFinanceWorkbench()
        }
        this.applyWorkbench(data)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '财务工作台加载失败'
        this.applyWorkbench(emptyWorkbench())
      } finally {
        this.loading = false
      }
    },
    applyWorkbench(data: FinanceWorkbench) {
      this.summary = normalizeSummary(data)
      this.todoItems = data.needMyAction || data.todoItems || []
      this.expenseReviews = data.expenseReviews || []
      this.profitRisks = data.profitRisks || []
      this.salaryChecks = data.salaryChecks || []
      this.dataChecks = data.dataChecks || []
      this.doneItems = data.doneReview || data.doneItems || []
      this.assistantPrompts = data.assistantPrompts?.length ? data.assistantPrompts : this.assistantPrompts
    },
    async approveExpense(id: string) {
      await this.runAction(id, async () => {
        await approveExpense(id, '财务在 Vue3 财务工作台审核通过')
        this.actionMessage = '报销已通过'
      })
    },
    async rejectExpense(id: string, note: string) {
      await this.runAction(id, async () => {
        await rejectExpense(id, note || '财务在 Vue3 财务工作台驳回')
        this.actionMessage = '报销已驳回'
      })
    },
    async requestInfo(id: string, note: string) {
      await this.runAction(id, async () => {
        const finalNote = note || '请门店补充票据或说明'
        await requestExpenseInfo(id, finalNote)
        this.actionMessage = '已要求门店补充资料'
      })
    },
    async requestTodoInfo(todoId: string, note: string) {
      await this.runAction(todoId, async () => {
        await requestFinanceTodoInfo(todoId, note || '请补充说明后由财务复核')
        this.actionMessage = '已要求补充说明'
      })
    },
    async markProfitChecked(entry: ProfitEntry) {
      const todoId = profitTodoId(entry)
      await this.runAction(todoId, async () => {
        await completeFinanceTodo(todoId, '财务已核对利润异常')
        this.actionMessage = '利润异常已标记核对'
      })
    },
    async markSalaryChecked(check: FinanceSalaryCheck) {
      const todoId = `salary-check-${check.id}`
      await this.runAction(todoId, async () => {
        await completeFinanceTodo(todoId, '财务已核对工资数据')
        this.actionMessage = '工资事项已标记核对'
      })
    },
    async markDataCheckChecked(check: FinanceDataCheck) {
      await this.runAction(check.id, async () => {
        await completeFinanceTodo(check.id, '财务数据已核对')
        this.actionMessage = '财务数据已标记核对'
      })
    },
    async completeTodo(item: RoleTodoItem) {
      await this.runAction(item.id, async () => {
        await completeFinanceTodo(item.id, '财务已在 Vue3 工作台处理完成')
        this.actionMessage = '事项已标记处理'
      })
    },
    async escalate(todoId: string, reason: string) {
      await this.runAction(todoId, async () => {
        await escalateFinanceTodo(todoId, reason || '财务判断需要老板确认')
        this.actionMessage = '已上报老板'
      })
    },
    async runAction(id: string, action: () => Promise<void>) {
      this.actioningId = id
      this.error = ''
      this.actionMessage = ''
      try {
        await action()
        await this.load()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '操作失败'
        throw error
      } finally {
        this.actioningId = ''
      }
    },
  },
})

async function aggregateFinanceWorkbench(): Promise<FinanceWorkbench> {
  const [todos, expenses, profits, salaries] = await Promise.all([
    getFinanceTodos().catch(() => ({ items: [] as RoleTodoItem[] })),
    getExpenses().catch(() => [] as ExpenseClaim[]),
    getProfitEntries().catch(() => [] as ProfitEntry[]),
    getSalaryRecords().catch(() => [] as SalaryRecord[]),
  ])
  const openTodos = (todos.items || []).filter((item) => item.status !== 'DONE')
  const pendingExpenses = expenses.filter((item) => item.status === '待审核')
  const profitRisks = profits.filter(isProfitRisk)
  const salaryChecks = salaries.map(salaryCheck).filter((item): item is FinanceSalaryCheck => Boolean(item))
  return {
    summary: {
      pendingExpenseCount: pendingExpenses.length,
      profitRiskCount: profitRisks.length,
      salaryCheckCount: salaryChecks.length,
      escalatedCount: openTodos.filter((item) => item.escalatedToBoss).length,
      headline: `今日财务重点：${pendingExpenses.length} 条报销待审核，${profitRisks.length} 家门店利润异常，${salaryChecks.length} 条工资数据待核对。`,
    },
    todoItems: openTodos,
    expenseReviews: expenses,
    profitRisks,
    salaryChecks,
    dataChecks: profitRisks.slice(0, 8).map((entry) => ({
      id: `finance-data-profit-${entry.storeId}-${entry.month}`,
      source: '利润表',
      issue: profitIssue(entry),
      storeId: entry.storeId,
      storeName: entry.storeName,
      month: entry.month,
      status: '待重新核对',
    })),
    doneItems: (todos.items || []).filter((item) => item.status === 'DONE').slice(0, 20),
  }
}

function normalizeSummary(data: FinanceWorkbench): FinanceSummary {
  const focus = data.todayFocus
  const summary = data.summary
  const pendingExpenseCount = Number(summary?.pendingExpenseCount ?? focus?.pendingExpenseCount ?? 0)
  const profitRiskCount = Number(summary?.profitRiskCount ?? focus?.profitRiskCount ?? focus?.profitRiskStoreCount ?? 0)
  const salaryCheckCount = Number(summary?.salaryCheckCount ?? focus?.salaryCheckCount ?? 0)
  const escalatedCount = Number(summary?.escalatedCount ?? focus?.escalatedCount ?? focus?.escalatedToBossCount ?? 0)
  return {
    pendingExpenseCount,
    profitRiskCount,
    salaryCheckCount,
    escalatedCount,
    headline: summary?.headline || focus?.headline || focus?.summary
      || `今日财务重点：${pendingExpenseCount} 条报销待审核，${profitRiskCount} 家门店利润异常，${salaryCheckCount} 条工资数据待核对。`,
  }
}

function emptyWorkbench(): FinanceWorkbench {
  return {
    summary: emptySummary,
    todoItems: [],
    expenseReviews: [],
    profitRisks: [],
    salaryChecks: [],
    dataChecks: [],
    doneItems: [],
  }
}

function isProfitRisk(entry: ProfitEntry) {
  if (entry.risk && entry.risk !== '健康') return true
  if (Number(entry.net || 0) < 0) return true
  if (Number(entry.margin || 0) > 0 && Number(entry.margin || 0) < 0.05) return true
  return false
}

function salaryCheck(record: SalaryRecord): FinanceSalaryCheck | null {
  const gross = Number(record.gross || 0)
  const overtime = Number(record.overtime || 0)
  const vacationLeft = Number(record.vacationLeft || 0)
  let anomaly = ''
  if (gross >= 6000) anomaly = '应发工资偏高，请核对提成、加班和补贴'
  else if (overtime > 0) anomaly = '存在加班工资，请核对工时和加班单'
  else if (vacationLeft < 0) anomaly = '假期余额为负，请核对考勤'
  if (!anomaly) return null
  return {
    id: record.id,
    employeeName: record.employeeName,
    storeId: record.storeId,
    storeName: record.storeName,
    month: record.month,
    gross,
    anomaly,
    status: '待财务核对',
  }
}

function profitIssue(entry: ProfitEntry) {
  if (Number(entry.net || 0) < 0) return '净利润为负，请核对成本、工资、报销和费用录入是否准确'
  if (Number(entry.margin || 0) < 0.05) return '净利率偏低，请核对报销、工资和原料成本'
  return '利润状态异常，请重新核对财务数据'
}

function profitTodoId(entry: ProfitEntry) {
  return `profit-risk-${entry.storeId}-${entry.month}`
}
