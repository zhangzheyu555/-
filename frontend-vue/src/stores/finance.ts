import { defineStore } from 'pinia'
import {
  approveExpense,
  escalateFinanceTodo,
  getExpenses,
  rejectExpense,
  requestExpenseInfo,
  type ExpenseClaim,
} from '../api/finance'

interface FinanceState {
  expenseReviews: ExpenseClaim[]
  loading: boolean
  actioningId: string
  error: string
  actionMessage: string
}

export const useFinanceStore = defineStore('finance', {
  state: (): FinanceState => ({
    expenseReviews: [],
    loading: false,
    actioningId: '',
    error: '',
    actionMessage: '',
  }),
  actions: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        this.expenseReviews = await getExpenses()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '报销数据加载失败'
        this.expenseReviews = []
      } finally {
        this.loading = false
      }
    },
    async approveExpense(id: string) {
      await this.runAction(id, async () => {
        await approveExpense(id, '财务审核通过')
        this.actionMessage = '报销已通过'
      })
    },
    async rejectExpense(id: string, note: string) {
      await this.runAction(id, async () => {
        await rejectExpense(id, note || '票据或说明需要补充')
        this.actionMessage = '报销已驳回'
      })
    },
    async requestInfo(id: string, note: string) {
      await this.runAction(id, async () => {
        await requestExpenseInfo(id, note || '请补充票据或说明')
        this.actionMessage = '已要求补充资料'
      })
    },
    async escalate(todoId: string, reason: string) {
      await this.runAction(todoId, async () => {
        await escalateFinanceTodo(todoId, reason || '该事项需要老板确认')
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
