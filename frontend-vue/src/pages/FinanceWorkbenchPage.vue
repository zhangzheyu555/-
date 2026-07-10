<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { BarChart3, Bot, ClipboardPenLine, FileText, RefreshCw, ShieldAlert } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import ExpenseReviewPanel from '../components/finance/ExpenseReviewPanel.vue'
import FinanceDataCheckPanel from '../components/finance/FinanceDataCheckPanel.vue'
import FinanceStatCards from '../components/finance/FinanceStatCards.vue'
import FinanceTodoCard from '../components/finance/FinanceTodoCard.vue'
import ProfitRiskPanel from '../components/finance/ProfitRiskPanel.vue'
import SalaryCheckPanel from '../components/finance/SalaryCheckPanel.vue'
import { useFinanceActions } from '../composables/useFinanceActions'
import { useFinanceStore } from '../stores/finance'

const router = useRouter()
const finance = useFinanceStore()
const actions = useFinanceActions()

const openTodoItems = computed(() => finance.todoItems.filter((item) => item.status !== 'DONE'))
const pendingExpenses = computed(() => finance.expenseReviews.filter((item) => !['已通过', 'APPROVED'].includes(item.status)))

const shortcuts = [
  {
    title: '报销栏',
    route: '/expenses',
    icon: FileText,
  },
  {
    title: '利润表',
    route: '/profit-table',
    icon: BarChart3,
  },
  {
    title: '员工工资',
    route: '/salary',
    icon: ClipboardPenLine,
  },
  {
    title: '财务数据核对',
    route: '/finance-data-check',
    icon: ShieldAlert,
  },
  {
    title: '财务数据助手',
    route: '/assistant',
    icon: Bot,
  },
]

async function refresh() {
  await finance.load()
}

function go(route: string) {
  void router.push(route)
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel finance-page">
    <div class="page-head">
      <div>
        <h2>财务工作台</h2>
      </div>
      <button class="ghost-button" type="button" :disabled="finance.loading" @click="refresh">
        <RefreshCw :size="16" />
        刷新
      </button>
    </div>

    <div v-if="finance.error" class="error-box">{{ finance.error }}</div>
    <div v-if="finance.actionMessage" class="success-box">{{ finance.actionMessage }}</div>
    <div v-if="finance.loading && !finance.todoItems.length" class="empty-state">正在读取财务工作台...</div>

    <template v-else>
      <FinanceStatCards :summary="finance.summary" />

      <div class="finance-focus">
        <div>
          <b>今日财务重点</b>
          <span>{{ finance.summary.headline }}</span>
        </div>
      </div>

      <section class="finance-panel">
        <div class="finance-panel-head">
          <div>
            <h3>待我处理</h3>
          </div>
        </div>
        <div v-if="!openTodoItems.length" class="empty-state compact">当前没有财务待处理提醒。</div>
        <div v-else class="finance-list">
          <FinanceTodoCard
            v-for="item in openTodoItems.slice(0, 6)"
            :key="item.id"
            :item="item"
            :actioning-id="finance.actioningId"
            @open="actions.openTodoSource"
            @complete="actions.completeTodo"
            @escalate="actions.escalateTodo"
          />
        </div>
      </section>

      <section class="finance-panel">
        <div class="finance-panel-head">
          <div>
            <h3>财务功能入口</h3>
          </div>
        </div>
        <div class="finance-shortcuts">
          <button v-for="item in shortcuts" :key="item.route" class="finance-shortcut" type="button" @click="go(item.route)">
            <component :is="item.icon" :size="22" />
            <span>
              <b>{{ item.title }}</b>
            </span>
          </button>
        </div>
      </section>

      <div class="finance-preview-grid">
        <ExpenseReviewPanel
          :expenses="pendingExpenses.slice(0, 4)"
          :actioning-id="finance.actioningId"
          @approve="actions.approveExpense"
          @reject="actions.rejectExpense"
          @request-info="actions.requestExpenseInfo"
          @escalate="actions.escalateExpense"
        />
        <ProfitRiskPanel
          :risks="finance.profitRisks.slice(0, 4)"
          :actioning-id="finance.actioningId"
          @open-report="actions.openProfitReport"
          @request-info="actions.requestProfitInfo"
          @checked="actions.markProfitChecked"
          @escalate="actions.escalateProfit"
        />
        <SalaryCheckPanel
          :checks="finance.salaryChecks.slice(0, 4)"
          :actioning-id="finance.actioningId"
          @checked="finance.markSalaryChecked"
          @escalate="actions.escalateSalary"
        />
        <FinanceDataCheckPanel
          :items="finance.dataChecks.slice(0, 4)"
          :actioning-id="finance.actioningId"
          @checked="actions.markDataCheckChecked"
          @escalate="actions.escalateDataCheck"
        />
      </div>
    </template>
  </section>
</template>

<style scoped>
.finance-page {
  display: grid;
  gap: 18px;
}

.finance-focus {
  padding: 16px;
  border: 1px solid rgba(238, 126, 62, 0.22);
  border-left: 4px solid var(--primary);
  border-radius: 14px;
  background: var(--primary-soft);
}

.finance-focus b {
  display: block;
  margin-bottom: 4px;
  color: var(--primary-dark);
  font-size: 14px;
  font-weight: 900;
}

.finance-focus span {
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
  line-height: 1.55;
}

.finance-panel,
.finance-list {
  display: grid;
  gap: 12px;
}

.finance-panel-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.finance-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.finance-shortcuts {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.finance-shortcut {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: flex-start;
  gap: 10px;
  min-height: 96px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  color: var(--ink);
  text-align: left;
}

.finance-shortcut:hover {
  border-color: rgba(238, 126, 62, 0.42);
  background: var(--primary-soft);
}

.finance-shortcut b {
  display: block;
  font-size: 14px;
}

.finance-preview-grid {
  display: grid;
  gap: 14px;
}

@media (max-width: 1180px) {
  .finance-shortcuts {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .finance-shortcuts {
    grid-template-columns: 1fr;
  }
}
</style>
