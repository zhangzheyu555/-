import { createRouter, createWebHistory, type RouteLocationRaw, type RouteRecordRaw } from 'vue-router'
import { canUseFinanceProfitImport, PERMISSIONS, type PermissionCode } from '../permissions/permissions'
import { isBossRole, normalizeRoleCode } from '../permissions/roles'
import { resolveAvailableWorkspace } from '../permissions/workspaces'
import { useAuthStore } from '../stores/auth'

const AppLayout = () => import('../layouts/AppLayout.vue')
const LearnerLayout = () => import('../layouts/LearnerLayout.vue')
const LoginPage = () => import('../pages/LoginPage.vue')
const ExpensePage = () => import('../pages/ExpensePage.vue')
const DataExportPage = () => import('../pages/DataExportPage.vue')
const DataEntryPage = () => import('../pages/DataEntryPage.vue')
const ProfitOverviewPage = () => import('../pages/ProfitOverviewPage.vue')
const ProfitTablePage = () => import('../pages/ProfitTablePage.vue')
const StoreDetailPage = () => import('../pages/StoreDetailPage.vue')
const StoreManagementPage = () => import('../pages/StoreManagementPage.vue')
const SupervisorWorkbenchPage = () => import('../pages/SupervisorWorkbenchPage.vue')
const InspectionRectificationPage = () => import('../pages/InspectionRectificationPage.vue')
const InspectionReviewQueuePage = () => import('../pages/InspectionReviewQueuePage.vue')
const AssistantPage = () => import('../pages/AssistantPage.vue')
const EmployeeAssistantPage = () => import('../pages/EmployeeAssistantPage.vue')
const EmployeeWorkbenchPage = () => import('../pages/EmployeeWorkbenchPage.vue')
const EmployeeProfilePage = () => import('../pages/EmployeeProfilePage.vue')
const DailyLossPage = () => import('../pages/DailyLossPage.vue')
const OperationLogPage = () => import('../pages/OperationLogPage.vue')
const NoPermissionPage = () => import('../pages/NoPermissionPage.vue')
const PlaceholderPage = () => import('../pages/PlaceholderPage.vue')
const PlatformLoginPage = () => import('../pages/PlatformLoginPage.vue')
const QmaiBusinessPage = () => import('../pages/QmaiBusinessPage.vue')
const UserPermissionPage = () => import('../pages/UserPermissionPage.vue')
const BossWorkspace = () => import('../pages/workspaces/BossWorkspace.vue')
const FinanceWorkspace = () => import('../pages/workspaces/FinanceWorkspace.vue')
const WarehouseWorkspace = () => import('../pages/workspaces/WarehouseWorkspace.vue')
const StoreManagerWorkspace = () => import('../pages/workspaces/StoreManagerWorkspace.vue')
const OperationsWorkspace = () => import('../pages/workspaces/OperationsWorkspace.vue')
const CentralWarehouseWorkspace = () => import('../pages/workspaces/business/CentralWarehouseWorkspace.vue')
const StoreInventoryWorkspace = () => import('../pages/workspaces/business/StoreInventoryWorkspace.vue')
const FinanceSalaryWorkspace = () => import('../pages/workspaces/business/FinanceSalaryWorkspace.vue')
const StoreSalaryWorkspace = () => import('../pages/workspaces/business/StoreSalaryWorkspace.vue')
const ExamAdminWorkspace = () => import('../pages/workspaces/business/ExamAdminWorkspace.vue')
const ExamProgressWorkspace = () => import('../pages/workspaces/business/ExamProgressWorkspace.vue')

const permissionMeta = (permission: PermissionCode, extras: Record<string, unknown> = {}) => ({
  permission,
  ...extras,
})

const appChildren: RouteRecordRaw[] = [
  { path: '', name: 'home', component: PlaceholderPage, meta: { title: '正在进入工作台', subtitle: '系统会根据当前账号权限进入对应首页。' } },
  { path: 'boss', name: 'boss-workspace', component: BossWorkspace, meta: permissionMeta(PERMISSIONS.SYSTEM_DASHBOARD_READ, { menuKey: 'boss-workspace', title: '老板工作台', bossOnly: true }) },
  { path: 'finance', name: 'finance-workspace', component: FinanceWorkspace, meta: permissionMeta(PERMISSIONS.FINANCE_PROFIT_READ, { menuKey: 'finance-workspace', title: '财务工作台', allowedRoles: ['FINANCE'] }) },
  { path: 'warehouse', name: 'warehouse-overview', component: WarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'overview', title: '仓库中心' }) },
  { path: 'warehouse/workspace', redirect: '/warehouse' },
  { path: 'store', name: 'store-workspace', component: StoreManagerWorkspace, meta: permissionMeta(PERMISSIONS.STORE_READ, { menuKey: 'store-workspace', title: '门店工作台', allowedRoles: ['STORE_MANAGER'] }) },
  { path: 'operations', name: 'operations-workspace', component: OperationsWorkspace, meta: permissionMeta(PERMISSIONS.OPERATIONS_DASHBOARD_READ, { menuKey: 'operations-workspace', title: '运营工作台', allowedRoles: ['OPERATIONS'] }) },
  { path: 'employee', name: 'employee-workspace', component: EmployeeWorkbenchPage, meta: permissionMeta(PERMISSIONS.EXAM_LEARN, { menuKey: 'employee-workspace', title: '员工工作台', allowedRoles: ['EMPLOYEE'] }) },
  { path: 'employee/profile', name: 'employee-profile', component: EmployeeProfilePage, meta: permissionMeta(PERMISSIONS.EXAM_LEARN, { menuKey: 'employee-profile', title: '我的资料', allowedRoles: ['EMPLOYEE'] }) },
  { path: 'employee/exams', name: 'employee-exams', component: ExamProgressWorkspace, meta: permissionMeta(PERMISSIONS.EXAM_LEARN, { moduleKey: 'exam', menuKey: 'employee-exams', title: '培训考试', allowedRoles: ['EMPLOYEE'] }) },

  { path: 'assistant', name: 'assistant', component: AssistantPage, meta: permissionMeta(PERMISSIONS.ASSISTANT_USE, { menuKey: 'assistant', title: '门店经营助手' }) },
  { path: 'employee-assistant', name: 'employee-assistant', component: EmployeeAssistantPage, meta: permissionMeta(PERMISSIONS.EMPLOYEE_ASSISTANT_USE, { menuKey: 'employee-assistant', title: '员工服务助手' }) },
  // 已下线的辅助页面：保留旧链接兼容，统一回到员工服务助手。
  { path: 'employee-assistant/knowledge', redirect: '/employee-assistant' },
  { path: 'employee-assistant/handoffs', redirect: '/employee-assistant' },
  { path: 'daily-loss', name: 'daily-loss', component: DailyLossPage, meta: permissionMeta(PERMISSIONS.DAILY_LOSS_READ, { menuKey: 'daily-loss', title: '每日报损' }) },
  { path: 'profit', name: 'profit', component: ProfitOverviewPage, meta: permissionMeta(PERMISSIONS.FINANCE_PROFIT_READ, { menuKey: 'profit-overview', title: '利润概览' }) },
  { path: 'profit-table', name: 'profit-table', component: ProfitTablePage, meta: permissionMeta(PERMISSIONS.FINANCE_PROFIT_READ, { menuKey: 'profit-table', title: '利润表' }) },
  { path: 'data-entry', name: 'data-entry', component: DataEntryPage, meta: permissionMeta(PERMISSIONS.FINANCE_PROFIT_WRITE, { menuKey: 'data-entry', title: '数据录入' }) },
  {
    path: 'finance/import',
    name: 'finance-profit-import',
    redirect: (to) => ({
      path: '/data-entry',
      query: { ...to.query, import: '1' },
    }),
  },
  { path: 'expenses', name: 'expenses', component: ExpensePage, meta: permissionMeta(PERMISSIONS.EXPENSE_READ, { menuKey: 'expenses', title: '报销栏' }) },
  { path: 'export', name: 'export', component: DataExportPage, meta: permissionMeta(PERMISSIONS.FINANCE_EXPORT, { menuKey: 'data-export', title: '数据导出' }) },
  { path: 'store-detail', name: 'store-detail', component: StoreDetailPage, meta: permissionMeta(PERMISSIONS.STORE_READ, { menuKey: 'store-detail', title: '门店详情' }) },
  { path: 'stores', name: 'stores', component: StoreManagementPage, meta: permissionMeta(PERMISSIONS.STORE_MANAGE, { menuKey: 'store-management', title: '门店管理', bossOnly: true }) },
  { path: 'logs', name: 'logs', component: OperationLogPage, meta: permissionMeta(PERMISSIONS.SYSTEM_AUDIT_READ, { menuKey: 'operation-logs', title: '操作日志' }) },
  { path: 'platform-login', name: 'platform-login', component: PlatformLoginPage, meta: permissionMeta(PERMISSIONS.PLATFORM_READ, { menuKey: 'platform-settings', title: '平台配置', allowedRoles: ['OPERATIONS'] }) },
  { path: 'qmai-business', name: 'qmai-business', component: QmaiBusinessPage, meta: permissionMeta(PERMISSIONS.FINANCE_PROFIT_READ, { menuKey: 'qmai-business', title: '企迈经营数据' }) },
  { path: 'users', name: 'users', component: UserPermissionPage, meta: permissionMeta(PERMISSIONS.SYSTEM_USER_MANAGE, { menuKey: 'user-permissions', title: '账号权限' }) },

  { path: 'finance/salary', name: 'finance-salary', component: FinanceSalaryWorkspace, meta: permissionMeta(PERMISSIONS.SALARY_READ, { menuKey: 'finance-salary', title: '员工工资' }) },
  { path: 'store/salary', name: 'store-salary', component: StoreSalaryWorkspace, meta: permissionMeta(PERMISSIONS.SALARY_READ, { menuKey: 'store-salary', title: '本店工资核对' }) },
  { path: 'store/inventory', name: 'store-inventory', component: StoreInventoryWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_STORE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', storeWarehouseTab: 'inventory', title: '本店库存' }) },
  { path: 'store/inventory/requisition', name: 'store-requisition', component: StoreInventoryWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_REQUISITION_CREATE, { moduleKey: 'warehouse', menuKey: 'warehouse-center', storeWarehouseTab: 'requisition', title: '门店叫货' }) },
  { path: 'store/inventory/receipts', name: 'store-receipts', component: StoreInventoryWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_REQUISITION_RECEIVE, { moduleKey: 'warehouse', menuKey: 'warehouse-center', storeWarehouseTab: 'records', title: '本店记录' }) },
  { path: 'store/inventory/records', name: 'store-inventory-records', component: StoreInventoryWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_STORE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', storeWarehouseTab: 'records', title: '本店记录' }) },

  { path: 'warehouse/central', name: 'warehouse-central', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'warehouse', warehouseSection: 'inventory', warehouseCode: 'JZ-CENTRAL', title: '荆州总仓' }) },
  { path: 'warehouse/shandong', name: 'warehouse-shandong', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'warehouse', warehouseSection: 'inventory', warehouseCode: 'SD-REGIONAL', title: '山东分仓' }) },
  { path: 'warehouse/transfers', name: 'warehouse-transfers', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'transfers', title: '仓间调拨' }) },
  { path: 'warehouse/items', name: 'warehouse-items', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'warehouse', warehouseSection: 'catalog', warehouseCode: 'JZ-CENTRAL', title: '物料档案' }) },
  { path: 'warehouse/inventory', name: 'warehouse-inventory', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'warehouse', warehouseSection: 'inventory', warehouseCode: 'JZ-CENTRAL', title: '库存物料' }) },
  { path: 'warehouse/requests', name: 'warehouse-requests', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'requisitions', title: '门店叫货' }) },
  { path: 'warehouse/purchase', name: 'warehouse-purchase', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_PURCHASE, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'purchase', warehouseCode: 'JZ-CENTRAL', title: '外部采购' }) },
  { path: 'warehouse/movements', name: 'warehouse-movements', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'movements', title: '出入库记录' }) },
  { path: 'warehouse/returns', name: 'warehouse-returns', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'movements', title: '配送退货单' }) },
  { path: 'warehouse/alerts', name: 'warehouse-alerts', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_READ, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'warehouse', warehouseSection: 'inventory', warehouseCode: 'JZ-CENTRAL', title: '库存预警' }) },
  { path: 'warehouse/receipts', name: 'warehouse-receipts', component: CentralWarehouseWorkspace, meta: permissionMeta(PERMISSIONS.WAREHOUSE_PURCHASE, { moduleKey: 'warehouse', menuKey: 'warehouse-center', warehouseTab: 'purchase', warehouseCode: 'JZ-CENTRAL', title: '入库记录' }) },

  { path: 'operations/inspection', name: 'inspection-manage', component: SupervisorWorkbenchPage, meta: permissionMeta(PERMISSIONS.INSPECTION_READ, { menuKey: 'inspection-management', title: '督导巡店' }) },
  { path: 'operations/inspection/tasks', name: 'inspection-tasks', component: SupervisorWorkbenchPage, meta: permissionMeta(PERMISSIONS.INSPECTION_MANAGE, { menuKey: 'inspection-management', inspectionTab: 'create', title: '发起巡检' }) },
  { path: 'operations/inspection/records', name: 'inspection-records', component: SupervisorWorkbenchPage, meta: permissionMeta(PERMISSIONS.INSPECTION_READ, { menuKey: 'inspection-records', inspectionTab: 'records', title: '巡检记录' }) },
  {
    path: 'store/inspection/rectifications',
    name: 'inspection-rectifications',
    component: InspectionRectificationPage,
    meta: permissionMeta(PERMISSIONS.INSPECTION_READ, {
      menuKey: 'inspection-rectifications',
      allowedRoles: ['STORE_MANAGER'],
      title: '巡检整改',
    }),
  },
  {
    path: 'operations/inspection/reviews',
    name: 'inspection-review-queue',
    component: InspectionReviewQueuePage,
    meta: permissionMeta(PERMISSIONS.INSPECTION_MANAGE, {
      menuKey: 'inspection-review-queue',
      allowedRoles: ['SUPERVISOR', 'OPERATIONS'],
      title: '整改复核',
    }),
  },
  { path: 'operations/inspection/standards', name: 'inspection-standards', component: SupervisorWorkbenchPage, meta: permissionMeta(PERMISSIONS.INSPECTION_MANAGE, { menuKey: 'inspection-management', inspectionTab: 'standards', title: '稽核标准' }) },
  { path: 'operations/exams', name: 'exam-admin', component: ExamAdminWorkspace, meta: permissionMeta(PERMISSIONS.EXAM_MANAGE, { moduleKey: 'exam', menuKey: 'exam-center', title: '培训考试', allowedRoles: ['OPERATIONS'] }) },
  { path: 'store/exams', name: 'exam-progress', component: ExamProgressWorkspace, meta: permissionMeta(PERMISSIONS.EXAM_REPORT, { moduleKey: 'exam', menuKey: 'exam-center', title: '培训考试', allowedRoles: ['STORE_MANAGER'] }) },
]

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginPage },
  { path: '/no-permission', name: 'no-permission', component: NoPermissionPage, meta: { requiresAuth: true } },
  {
    path: '/',
    component: AppLayout,
    meta: { requiresAuth: true },
    children: appChildren,
  },
  {
    path: '/learn',
    component: LearnerLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/learn/exams' },
      { path: 'exams', name: 'learner-exams', component: ExamProgressWorkspace, meta: permissionMeta(PERMISSIONS.EXAM_LEARN, { title: '我的学习与考试' }) },
    ],
  },

  { path: '/todos', redirect: () => defaultRouteForSession() },
  { path: '/salary', redirect: () => salaryRouteForSession() },
  { path: '/exam-center', redirect: () => examRouteForSession() },
  { path: '/inspection', redirect: '/operations/inspection' },
  { path: '/inspection/tasks', redirect: '/operations/inspection/tasks' },
  { path: '/inspection/create', redirect: '/operations/inspection/tasks' },
  { path: '/inspection/records', redirect: '/operations/inspection/records' },
  { path: '/inspection/rectifications', redirect: '/store/inspection/rectifications' },
  { path: '/inspection/reviews', redirect: '/operations/inspection/reviews' },
  { path: '/inspection/rules', redirect: '/operations/inspection/standards' },
  { path: '/inspection/standards', redirect: '/operations/inspection/standards' },
  { path: '/finance-data-check', redirect: '/finance' },
  { path: '/supervisor/:pathMatch(.*)*', redirect: '/operations/inspection' },
  { path: '/operations/:pathMatch(.*)*', redirect: '/operations' },
]

function defaultRouteForSession(): RouteLocationRaw {
  const auth = useAuthStore()
  if (auth.role === 'STORE_MANAGER' && !auth.storeManagerHasStoreBinding) {
    return { name: 'no-permission', query: { reason: 'STORE_NOT_BOUND' } }
  }
  // The backend has already combined role templates, DENY overrides and data scopes.
  // Do not turn an explicit no-workspace decision back into a role-based fallback.
  if (auth.defaultWorkspace === '/no-permission') return { name: 'no-permission' }
  return resolveAvailableWorkspace(auth) || '/no-permission'
}

function salaryRouteForSession(): RouteLocationRaw {
  const auth = useAuthStore()
  const mode = auth.dataScope('SALARY')?.mode
  return mode === 'OWN_STORE' ? '/store/salary' : '/finance/salary'
}

function examRouteForSession(): RouteLocationRaw {
  const auth = useAuthStore()
  if (auth.hasPermission(PERMISSIONS.EXAM_MANAGE)) return '/operations/exams'
  if (auth.hasPermission(PERMISSIONS.EXAM_REPORT)) return '/store/exams'
  if (auth.role === 'EMPLOYEE' && auth.hasPermission(PERMISSIONS.EXAM_LEARN)) return '/employee/exams'
  if (auth.hasPermission(PERMISSIONS.EXAM_LEARN)) return '/learn/exams'
  return '/no-permission'
}

const warehouseTabRedirects: Record<string, string> = {
  catalog: '/warehouse/items',
  inventory: '/warehouse/central',
  requisitions: '/warehouse/requests',
  transfers: '/warehouse/transfers',
  purchase: '/warehouse/purchase',
  movements: '/warehouse/movements',
  returns: '/warehouse/returns',
  alerts: '/warehouse/alerts',
  receipts: '/warehouse/receipts',
}

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const requiresAuth = to.matched.some((record) => record.meta.requiresAuth)
  if (auth.token && !auth.sessionValidated) {
    await auth.validateStoredSession()
  }
  if (requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'home' && auth.isLoggedIn) return defaultRouteForSession()
  if (to.name === 'login' && auth.isLoggedIn) return defaultRouteForSession()
  if (auth.isLoggedIn
      && auth.role === 'STORE_MANAGER'
      && !auth.storeManagerHasStoreBinding
      && to.name !== 'no-permission'
      && to.name !== 'login') {
    return { name: 'no-permission', query: { reason: 'STORE_NOT_BOUND', from: to.fullPath } }
  }

  const requiresFinanceWrite = to.matched.some((record) => record.meta.permission === PERMISSIONS.FINANCE_PROFIT_WRITE)
  if (requiresFinanceWrite && auth.isLoggedIn && auth.role === 'STORE_MANAGER') {
    return {
      path: '/profit-table',
      query: { notice: 'STORE_MANAGER_DATA_ENTRY_FORBIDDEN' },
    }
  }

  const requiresFinanceImport = to.matched.some((record) => record.meta.financeImportOnly === true)
  if (requiresFinanceImport && auth.isLoggedIn) {
    const canImport = canUseFinanceProfitImport(auth.role, auth.permissions)
    if (!canImport) {
      if (auth.role === 'STORE_MANAGER') {
        return {
          path: '/profit-table',
          query: { notice: 'STORE_MANAGER_IMPORT_FORBIDDEN' },
        }
      }
      return { name: 'no-permission', query: { from: to.fullPath } }
    }
  }

  const requiresBoss = to.matched.some((record) => record.meta.bossOnly === true)
  if (requiresBoss && auth.isLoggedIn && !isBossRole(auth.role)) {
    if (auth.role === 'STORE_MANAGER') {
      return {
        path: '/store',
        query: { notice: 'STORE_MANAGEMENT_FORBIDDEN' },
      }
    }
    return { name: 'no-permission', query: { from: to.fullPath } }
  }

  const allowedRoles = [...to.matched]
    .reverse()
    .map((record) => record.meta.allowedRoles)
    .find((value): value is string[] => Array.isArray(value) && value.every((role) => typeof role === 'string'))
  if (allowedRoles?.length
      && auth.isLoggedIn
      && !isBossRole(auth.role)
      && !allowedRoles.some((role) => normalizeRoleCode(role) === normalizeRoleCode(auth.role))) {
    return { name: 'no-permission', query: { from: to.fullPath } }
  }

  if (to.name === 'finance-workspace' && to.query.tab === 'expenses') {
    return { path: '/expenses' }
  }
  if (to.name === 'inspection-manage' && auth.isLoggedIn && !auth.hasPermission(PERMISSIONS.INSPECTION_MANAGE)) {
    return { path: '/operations/inspection/records', query: to.query }
  }
  if (to.name === 'warehouse-overview' && auth.isLoggedIn) {
    if (!auth.hasPermission(PERMISSIONS.WAREHOUSE_READ)
        && !auth.hasPermission(PERMISSIONS.WAREHOUSE_CENTRAL_READ)
        && auth.hasPermission(PERMISSIONS.WAREHOUSE_STORE_READ)) {
      return { path: '/store/inventory', query: to.query }
    }
    const rawTab = Array.isArray(to.query.tab) ? to.query.tab[0] : to.query.tab
    if (rawTab && warehouseTabRedirects[rawTab]) {
      const query = { ...to.query }
      delete query.tab
      return { path: warehouseTabRedirects[rawTab], query }
    }
  }

  if (to.name === 'store-inventory' && auth.isLoggedIn) {
    const section = Array.isArray(to.query.section) ? to.query.section[0] : to.query.section
    const sectionPaths: Record<string, string> = {
      requisition: '/store/inventory/requisition',
      receipts: '/store/inventory/receipts',
    }
    if (section && sectionPaths[section]) {
      const query = { ...to.query }
      delete query.section
      return { path: sectionPaths[section], query }
    }
  }

  const requiredPermission = [...to.matched]
    .reverse()
    .map((record) => record.meta.permission)
    .find((permission): permission is string => typeof permission === 'string' && Boolean(permission))
  const hasRequiredPermission = !requiredPermission
    || auth.hasPermission(requiredPermission)
    || (
      requiredPermission === PERMISSIONS.WAREHOUSE_READ
      && auth.hasPermission(PERMISSIONS.WAREHOUSE_CENTRAL_READ)
    )
  if (!hasRequiredPermission) {
    return { name: 'no-permission', query: { from: to.fullPath } }
  }
  return true
})

export default router
