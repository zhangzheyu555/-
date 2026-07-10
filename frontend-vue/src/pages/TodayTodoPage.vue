<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ClipboardList, RefreshCw } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import TodoSection from '../components/todo/TodoSection.vue'
import TodoStatCards from '../components/todo/TodoStatCards.vue'
import TodoWorkflowDrawer from '../components/todo/TodoWorkflowDrawer.vue'
import { useAuthStore } from '../stores/auth'
import { useTodoStore, type TodoReminder } from '../stores/todos'

const auth = useAuthStore()
const todos = useTodoStore()
const router = useRouter()
const activeTodoId = ref('')

const quickLinks = computed(() => {
  if (auth.role === 'STORE_MANAGER') {
    return [
      { label: '去仓库中心', route: '/warehouse' },
      { label: '查看本店数据', route: '/store-detail' },
    ]
  }
  if (auth.role === 'WAREHOUSE') {
    return [
      { label: '门店叫货', route: '/warehouse?tab=requisitions' },
      { label: '采购入库', route: '/warehouse?tab=purchase' },
      { label: '库存预警', route: '/warehouse?tab=alerts' },
      { label: '配送退货单', route: '/warehouse?tab=returns' },
      { label: '出入库记录', route: '/warehouse?tab=movements' },
    ]
  }
  if (auth.role === 'FINANCE') {
    return [
      { label: '去报销栏', route: '/expenses' },
      { label: '查看利润表', route: '/profit-table' },
      { label: '核对员工工资', route: '/salary' },
    ]
  }
  if (auth.role === 'SUPERVISOR') {
    return [
      { label: '督导工作台', route: '/inspection' },
      { label: '巡店任务', route: '/inspection?tab=tasks' },
      { label: '整改复查', route: '/inspection?tab=reviews' },
    ]
  }
  if (auth.role === 'OPERATIONS' || auth.role === 'OPS') {
    return [
      { label: '运营中心', route: '/operations' },
      { label: '数据分析', route: '/operations?tab=analysis' },
      { label: '新人培训', route: '/operations?tab=training' },
      { label: '培训考试', route: '/exam-center' },
      { label: '店铺盘存', route: '/operations?tab=inventory-check' },
      { label: '饿了么订单', route: '/operations?tab=eleme' },
      { label: '数据健康', route: '/operations?tab=data-health' },
      { label: '数据导入', route: '/operations?tab=imports' },
      { label: '平台账号', route: '/operations?tab=platform' },
      { label: '迁移状态', route: '/operations?tab=migration' },
    ]
  }
  if (auth.role === 'BOSS' || auth.role === 'OWNER') {
    return [
      { label: '老板驾驶舱', route: '/boss' },
      { label: '需要我处理', route: '/boss?section=needs-action' },
      { label: '高风险提醒', route: '/boss?section=risks' },
    ]
  }
  return [
    { label: '查看利润概览', route: '/profit' },
    { label: '查看仓库中心', route: '/warehouse' },
  ]
})

async function refresh() {
  await todos.loadForRole(auth.role)
}

function goToTodoSource(todo: TodoReminder) {
  void router.push(todo.targetRoute)
}

function openWorkflow(todo: TodoReminder) {
  activeTodoId.value = todo.workflowTodoId || ''
}

function closeWorkflow() {
  activeTodoId.value = ''
}

async function handleWorkflowUpdated() {
  await refresh()
}

function openWorkflowSource(route: string) {
  closeWorkflow()
  void router.push(route)
}

function goRoute(route: string) {
  void router.push(route)
}

watch(
  () => auth.role,
  () => {
    void refresh()
  },
)

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel todo-page">
    <div class="page-head">
      <div>
        <h2>{{ todos.workbench.roleName }}今日待办</h2>
      </div>
      <button class="ghost-button" type="button" :disabled="todos.loading" @click="refresh">
        <RefreshCw :size="16" />
        刷新
      </button>
    </div>

    <div v-if="todos.error" class="error-box">{{ todos.error }}</div>
    <div v-if="todos.loading && !todos.workbench.stats.length" class="empty-state">正在读取今日提醒...</div>

    <template v-else>
      <TodoStatCards :stats="todos.workbench.stats" />

      <div class="todo-headline">
        <ClipboardList :size="22" />
        <div>
          <b>今日重点</b>
          <span>{{ todos.workbench.headline }}</span>
        </div>
      </div>

      <div class="todo-layout">
        <TodoSection
          title="需要我关注"
          empty-text="当前没有必须关注的未处理提醒。"
          :items="todos.workbench.attentionItems"
          @open="goToTodoSource"
          @workflow="openWorkflow"
        />

        <TodoSection
          title="风险提醒"
          empty-text="当前没有风险提醒。"
          :items="todos.workbench.riskItems"
          @open="goToTodoSource"
          @workflow="openWorkflow"
        />

        <TodoSection
          v-if="todos.workbench.progressItems.length"
          title="各岗位处理中"
          empty-text="当前没有岗位处理中摘要。"
          :items="todos.workbench.progressItems"
          @open="goToTodoSource"
          @workflow="openWorkflow"
        />

        <TodoSection
          title="已处理"
          empty-text="当前没有已处理复盘。"
          :items="todos.workbench.doneItems"
          @open="goToTodoSource"
          @workflow="openWorkflow"
        />

        <section class="todo-section-block">
          <div class="todo-section-head">
            <div>
              <h3>跳转入口</h3>
            </div>
          </div>
          <div class="quick-grid todo-quick-grid">
            <button
              v-for="link in quickLinks"
              :key="link.route"
              class="question-card"
              type="button"
              @click="goRoute(link.route)"
            >
              {{ link.label }}
            </button>
          </div>
        </section>
      </div>
    </template>

    <TodoWorkflowDrawer
      v-if="activeTodoId"
      :todo-id="activeTodoId"
      @close="closeWorkflow"
      @updated="handleWorkflowUpdated"
      @open-source="openWorkflowSource"
    />
  </section>
</template>

<style scoped>
.todo-page {
  display: grid;
  gap: 18px;
}

.todo-headline {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  border: 1px solid rgba(238, 126, 62, 0.22);
  border-left: 4px solid var(--primary);
  border-radius: 14px;
  background: var(--primary-soft);
}

.todo-headline b {
  display: block;
  margin-bottom: 4px;
  color: var(--primary-dark);
  font-size: 14px;
  font-weight: 900;
}

.todo-headline span {
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
  line-height: 1.55;
}

.todo-layout {
  display: grid;
  gap: 18px;
}

.todo-section-block {
  display: grid;
  gap: 12px;
}

.todo-section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.todo-section-head h3 {
  margin: 0;
  font-size: 18px;
}

.todo-quick-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

@media (max-width: 900px) {
  .todo-quick-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .todo-quick-grid {
    grid-template-columns: 1fr;
  }
}
</style>
