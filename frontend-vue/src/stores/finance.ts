import { defineStore } from 'pinia'
import {
  approveExpense,
  getExpenses,
  rejectExpense,
  requestExpenseInfo,
  type ExpenseClaim,
  type ExpenseClaimQuery,
} from '../api/finance'

interface FinanceState {
  expenseReviews: ExpenseClaim[]
  loading: boolean
  actioningId: string
  error: string
  actionMessage: string
  loadSerial: number
  lastExpenseQuery: ExpenseClaimQuery
}

export const useFinanceStore = defineStore('finance', {
  state: (): FinanceState => ({
    expenseReviews: [],
    loading: false,
    actioningId: '',
    error: '',
    actionMessage: '',
    loadSerial: 0,
    lastExpenseQuery: {},
  }),
  actions: {
    async load(query: ExpenseClaimQuery = {}) {
      const requestedQuery = { ...query }
      this.lastExpenseQuery = requestedQuery
      const serial = ++this.loadSerial
      this.loading = true
      this.error = ''
      try {
        const rows = await getExpenses(requestedQuery)
        // A user can change multiple filters in quick succession.  Keep the
        // most recent response authoritative instead of rendering an older
        // query after the latest one has already been requested.
        if (serial !== this.loadSerial) return
        this.expenseReviews = rows
      } catch (error) {
        if (serial !== this.loadSerial) return
        this.error = error instanceof Error ? error.message : '报销数据加载失败'
        this.expenseReviews = []
      } finally {
        if (serial === this.loadSerial) this.loading = false
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
    async runAction(id: string, action: () => Promise<void>) {
      this.actioningId = id
      this.error = ''
      this.actionMessage = ''
      try {
        await action()
        await this.load(this.lastExpenseQuery)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '操作失败'
        throw error
      } finally {
        this.actioningId = ''
      }
    },
  },
})
