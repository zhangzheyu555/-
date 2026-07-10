import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AppLayout from '../layouts/AppLayout.vue'
import LoginPage from '../pages/LoginPage.vue'
import TodayTodoPage from '../pages/TodayTodoPage.vue'
import WarehousePage from '../pages/WarehousePage.vue'
import FinanceWorkbenchPage from '../pages/FinanceWorkbenchPage.vue'
import ExpensePage from '../pages/ExpensePage.vue'
import FinanceDataCheckPage from '../pages/FinanceDataCheckPage.vue'
import BossDashboardPage from '../pages/BossDashboardPage.vue'
import DataExportPage from '../pages/DataExportPage.vue'
import DataEntryPage from '../pages/DataEntryPage.vue'
import ProfitOverviewPage from '../pages/ProfitOverviewPage.vue'
import ProfitTablePage from '../pages/ProfitTablePage.vue'
import SalaryPage from '../pages/SalaryPage.vue'
import StoreDetailPage from '../pages/StoreDetailPage.vue'
import StoreManagementPage from '../pages/StoreManagementPage.vue'
import SupervisorWorkbenchPage from '../pages/SupervisorWorkbenchPage.vue'
import OperationsWorkbenchPage from '../pages/OperationsWorkbenchPage.vue'
import AssistantPage from '../pages/AssistantPage.vue'
import OperationLogPage from '../pages/OperationLogPage.vue'
import NoPermissionPage from '../pages/NoPermissionPage.vue'
import PlaceholderPage from '../pages/PlaceholderPage.vue'
import PlatformLoginPage from '../pages/PlatformLoginPage.vue'
import UserPermissionPage from '../pages/UserPermissionPage.vue'
import ExamCenterPage from '../pages/ExamCenterPage.vue'
import { useAuthStore } from '../stores/auth'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginPage },
  {
    path: '/',
    component: AppLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'home', component: PlaceholderPage, meta: { title: '正在进入工作台', subtitle: '系统会根据当前角色进入对应首页。' } },
      { path: 'boss', name: 'boss', component: BossDashboardPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER'] } },
      { path: 'todos', name: 'todos', component: TodayTodoPage },
      { path: 'warehouse', name: 'warehouse', component: WarehousePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'WAREHOUSE', 'STORE_MANAGER'] } },
      { path: 'warehouse/items', name: 'warehouse-items', component: WarehousePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'WAREHOUSE'], warehouseTab: 'catalog', title: '物料档案' } },
      { path: 'warehouse/inventory', name: 'warehouse-inventory', component: WarehousePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'WAREHOUSE'], warehouseTab: 'inventory', title: '库存物料' } },
      { path: 'warehouse/purchase', name: 'warehouse-purchase', component: WarehousePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'WAREHOUSE'], warehouseTab: 'purchase', title: '采购入库' } },
      { path: 'warehouse/movements', name: 'warehouse-movements', component: WarehousePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'WAREHOUSE'], warehouseTab: 'movements', title: '出入库记录' } },
      { path: 'warehouse/returns', name: 'warehouse-returns', component: WarehousePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'WAREHOUSE', 'STORE_MANAGER'], warehouseTab: 'returns', title: '配送退货单' } },
      { path: 'warehouse/alerts', name: 'warehouse-alerts', component: WarehousePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'WAREHOUSE'], warehouseTab: 'inventory', title: '库存预警' } },
      { path: 'warehouse/receipts', name: 'warehouse-receipts', component: WarehousePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'WAREHOUSE'], warehouseTab: 'purchase', title: '入库记录' } },
      { path: 'assistant', name: 'assistant', component: AssistantPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE', 'WAREHOUSE', 'STORE_MANAGER', 'SUPERVISOR', 'OPERATIONS', 'OPS'] } },
      { path: 'no-permission', name: 'no-permission', component: NoPermissionPage },
      { path: 'profit', name: 'profit', component: ProfitOverviewPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE'] } },
      { path: 'store-detail', name: 'store-detail', component: StoreDetailPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'STORE_MANAGER', 'FINANCE'] } },
      { path: 'finance', name: 'finance', component: FinanceWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE'] } },
      { path: 'expenses', name: 'expenses', component: ExpensePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE', 'STORE_MANAGER'] } },
      { path: 'profit-table', name: 'profit-table', component: ProfitTablePage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE'] } },
      { path: 'salary', name: 'salary', component: SalaryPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE', 'STORE_MANAGER'] } },
      { path: 'finance-data-check', name: 'finance-data-check', component: FinanceDataCheckPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE'] } },
      { path: 'export', name: 'export', component: DataExportPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE'] } },
      { path: 'data-entry', name: 'data-entry', component: DataEntryPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE', 'STORE_MANAGER'] } },
      { path: 'stores', name: 'stores', component: StoreManagementPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER'] } },
      { path: 'logs', name: 'logs', component: OperationLogPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER'] } },
      { path: 'platform-login', name: 'platform-login', component: PlatformLoginPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS'] } },
      { path: 'users', name: 'users', component: UserPermissionPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER'] } },
      { path: 'inspection', name: 'inspection', component: SupervisorWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'SUPERVISOR'], activeMenu: '/inspection' } },
      { path: 'inspection/tasks', name: 'inspection-tasks', component: SupervisorWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'SUPERVISOR'], activeMenu: '/inspection', inspectionTab: 'create', title: '发起巡检' } },
      { path: 'inspection/create', name: 'inspection-create', component: SupervisorWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'SUPERVISOR'], activeMenu: '/inspection', inspectionTab: 'create', title: '发起巡检' } },
      { path: 'inspection/records', name: 'inspection-records', component: SupervisorWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'SUPERVISOR'], activeMenu: '/inspection', inspectionTab: 'records', title: '巡检记录' } },
      { path: 'inspection/reviews', name: 'inspection-reviews', component: SupervisorWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'SUPERVISOR'], activeMenu: '/inspection', inspectionTab: 'records', title: '督导巡店' } },
      { path: 'inspection/rules', name: 'inspection-rules', component: SupervisorWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'SUPERVISOR'], activeMenu: '/inspection', inspectionTab: 'standards', title: '稽核标准' } },
      { path: 'inspection/standards', name: 'inspection-standards', component: SupervisorWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'SUPERVISOR'], activeMenu: '/inspection', inspectionTab: 'standards', title: '稽核标准' } },
      { path: 'exam-center', name: 'exam-center', component: ExamCenterPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS', 'STORE_MANAGER', 'EMPLOYEE'], activeMenu: '/exam-center', title: '培训考试' } },
      { path: 'operations', name: 'operations', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS'] } },
      { path: 'operations/analysis', name: 'operations-analysis', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS'], operationsTab: 'analysis', title: '数据分析' } },
      { path: 'operations/training', name: 'operations-training', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS'], operationsTab: 'training', title: '新人培训' } },
      { path: 'operations/exam', name: 'operations-exam', component: ExamCenterPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS', 'STORE_MANAGER', 'EMPLOYEE'], activeMenu: '/exam-center', title: '培训考试' } },
      { path: 'operations/inventory-check', name: 'operations-inventory-check', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS'], operationsTab: 'inventory-check', title: '店铺盘存' } },
      { path: 'operations/eleme', name: 'operations-eleme', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS'], operationsTab: 'eleme', title: '饿了么订单' } },
      { path: 'operations/platform', name: 'operations-platform', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS'], operationsTab: 'platform', title: '平台账号' } },
      { path: 'operations/imports', name: 'operations-imports', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'FINANCE', 'STORE_MANAGER'], operationsTab: 'imports', title: '数据导入' } },
      { path: 'operations/logs', name: 'operations-logs', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER'], operationsTab: 'logs', title: '操作日志' } },
      { path: 'operations/data-health', name: 'operations-data-health', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS'], operationsTab: 'data-health', title: '数据健康' } },
      { path: 'operations/migration', name: 'operations-migration', component: OperationsWorkbenchPage, meta: { roles: ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'OPS'], operationsTab: 'migration', title: '迁移状态' } },
    ],
  },
]

function defaultRouteForRole(role: string) {
  if (role === 'ADMIN' || role === 'BOSS' || role === 'OWNER') return { name: 'boss' }
  if (role === 'EMPLOYEE') return { name: 'exam-center' }
  return { name: 'todos' }
}

const financeTabRedirects: Record<string, string> = {
  expenses: '/expenses',
  'profit-risk': '/profit-table',
  salary: '/salary',
  'data-check': '/finance-data-check',
}

const warehouseTabRedirects: Record<string, string> = {
  catalog: '/warehouse/items',
  inventory: '/warehouse/inventory',
  purchase: '/warehouse/purchase',
  movements: '/warehouse/movements',
  returns: '/warehouse/returns',
  alerts: '/warehouse/alerts',
  receipts: '/warehouse/receipts',
}

const operationsTabRedirects: Record<string, string> = {
  analysis: '/operations/analysis',
  training: '/operations/training',
  exam: '/exam-center',
  'inventory-check': '/operations/inventory-check',
  eleme: '/operations/eleme',
  platform: '/operations/platform',
  imports: '/operations/imports',
  logs: '/operations/logs',
  'data-health': '/operations/data-health',
  migration: '/operations/migration',
}

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'home' && auth.isLoggedIn) {
    return defaultRouteForRole(auth.role)
  }
  if (to.name === 'login' && auth.isLoggedIn) {
    return defaultRouteForRole(auth.role)
  }
  if (to.name === 'finance' && auth.isLoggedIn) {
    const tab = Array.isArray(to.query.tab) ? to.query.tab[0] : to.query.tab
    if (tab && financeTabRedirects[tab]) {
      const query = { ...to.query }
      delete query.tab
      return { path: financeTabRedirects[tab], query }
    }
  }
  if (to.name === 'warehouse' && auth.isLoggedIn) {
    const tab = Array.isArray(to.query.tab) ? to.query.tab[0] : to.query.tab
    if (tab && warehouseTabRedirects[tab]) {
      const query = { ...to.query }
      delete query.tab
      return { path: warehouseTabRedirects[tab], query }
    }
  }
  if (to.name === 'operations' && auth.isLoggedIn) {
    const tab = Array.isArray(to.query.tab) ? to.query.tab[0] : to.query.tab
    if (tab && operationsTabRedirects[tab]) {
      const query = { ...to.query }
      delete query.tab
      return { path: operationsTabRedirects[tab], query }
    }
  }
  const roles = to.meta.roles as string[] | undefined
  if (roles?.length && !roles.includes(auth.role)) {
    return { name: 'no-permission', query: { from: to.fullPath } }
  }
  return true
})

export default router
