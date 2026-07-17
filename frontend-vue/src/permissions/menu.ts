import { canUseFinanceProfitImport, hasPermission, PERMISSIONS, type PermissionCode } from './permissions'
import { isBossRole, normalizeRoleCode } from './roles'

export type MenuIconKey =
  | 'assistant'
  | 'dashboard'
  | 'exam'
  | 'expense'
  | 'export'
  | 'inspection'
  | 'inventory'
  | 'log'
  | 'platform'
  | 'profit'
  | 'salary'
  | 'store'
  | 'users'
  | 'warehouse'

export interface PermissionMenuItem {
  key: string
  label: string
  to: string
  icon: MenuIconKey
  requiredPermission: PermissionCode
  alternativePermissions?: PermissionCode[]
  bossOnly?: boolean
  financeOrBossOnly?: boolean
  /** 仅用于菜单收敛；路由和后端仍会分别做角色、权限和数据范围校验。 */
  allowedRoles?: readonly string[]
  workspacePath?: string
  requiredDataScope?: {
    domain: string
    modes: string[]
  }
}

export interface PermissionMenuGroup {
  title?: string
  items: PermissionMenuItem[]
}

interface MenuSubject {
  role?: string
  permissions?: readonly string[]
  defaultWorkspace?: string
  dataScopes?: Record<string, { mode?: string } | undefined>
}

const WORKSPACE_ITEMS: PermissionMenuItem[] = [
  { key: 'boss-workspace', label: '老板工作台', to: '/boss', icon: 'dashboard', requiredPermission: PERMISSIONS.SYSTEM_DASHBOARD_READ, bossOnly: true, workspacePath: '/boss' },
  { key: 'finance-workspace', label: '财务工作台', to: '/finance', icon: 'dashboard', requiredPermission: PERMISSIONS.FINANCE_PROFIT_READ, allowedRoles: ['FINANCE'], workspacePath: '/finance' },
  { key: 'warehouse-workspace', label: '仓库工作台', to: '/warehouse', icon: 'warehouse', requiredPermission: PERMISSIONS.WAREHOUSE_READ, alternativePermissions: [PERMISSIONS.WAREHOUSE_CENTRAL_READ], workspacePath: '/warehouse', requiredDataScope: { domain: 'WAREHOUSE', modes: ['ALL', 'WAREHOUSE_LIST', 'CENTRAL_WAREHOUSE'] } },
  { key: 'store-workspace', label: '门店工作台', to: '/store', icon: 'store', requiredPermission: PERMISSIONS.STORE_READ, allowedRoles: ['STORE_MANAGER'], workspacePath: '/store' },
  { key: 'supervisor-workspace', label: '督导巡店', to: '/operations/inspection', icon: 'inspection', requiredPermission: PERMISSIONS.INSPECTION_READ, workspacePath: '/operations/inspection', requiredDataScope: { domain: 'INSPECTION', modes: ['STORE_LIST'] } },
  { key: 'operations-workspace', label: '运营工作台', to: '/operations', icon: 'dashboard', requiredPermission: PERMISSIONS.OPERATIONS_DASHBOARD_READ, allowedRoles: ['OPERATIONS'], workspacePath: '/operations' },
  { key: 'employee-workspace', label: '员工工作台', to: '/employee', icon: 'dashboard', requiredPermission: PERMISSIONS.EXAM_LEARN, allowedRoles: ['EMPLOYEE'], workspacePath: '/employee' },
]

export const MENU_GROUP_CONFIG: PermissionMenuGroup[] = [
  {
    title: '员工服务',
    items: [
      { key: 'employee-exams', label: '培训考试', to: '/employee/exams', icon: 'exam', requiredPermission: PERMISSIONS.EXAM_LEARN, allowedRoles: ['EMPLOYEE'] },
      { key: 'employee-profile', label: '我的资料/工资', to: '/employee/profile', icon: 'users', requiredPermission: PERMISSIONS.EXAM_LEARN, allowedRoles: ['EMPLOYEE'] },
    ],
  },
  {
    title: '经营财务',
    items: [
      { key: 'profit-overview', label: '利润概览', to: '/profit', icon: 'profit', requiredPermission: PERMISSIONS.FINANCE_PROFIT_READ },
      { key: 'profit-table', label: '利润表', to: '/profit-table', icon: 'profit', requiredPermission: PERMISSIONS.FINANCE_PROFIT_READ },
      { key: 'data-entry', label: '数据录入', to: '/data-entry', icon: 'profit', requiredPermission: PERMISSIONS.FINANCE_PROFIT_WRITE },
      { key: 'expenses', label: '报销栏', to: '/expenses', icon: 'expense', requiredPermission: PERMISSIONS.EXPENSE_READ },
      {
        label: '员工工资',
        key: 'finance-salary',
        to: '/finance/salary',
        icon: 'salary',
        requiredPermission: PERMISSIONS.SALARY_READ,
        requiredDataScope: { domain: 'SALARY', modes: ['ALL', 'STORE_LIST'] },
      },
      {
        label: '本店工资核对',
        key: 'store-salary',
        to: '/store/salary',
        icon: 'salary',
        requiredPermission: PERMISSIONS.SALARY_READ,
        requiredDataScope: { domain: 'SALARY', modes: ['OWN_STORE'] },
      },
      { key: 'data-export', label: '数据导出', to: '/export', icon: 'export', requiredPermission: PERMISSIONS.FINANCE_EXPORT },
    ],
  },
  {
    title: '库存与门店',
    items: [
      {
        key: 'warehouse-center',
        label: '仓库中心',
        to: '/warehouse',
        icon: 'warehouse',
        requiredPermission: PERMISSIONS.WAREHOUSE_READ,
        alternativePermissions: [
          PERMISSIONS.WAREHOUSE_CENTRAL_READ,
          PERMISSIONS.WAREHOUSE_STORE_READ,
          PERMISSIONS.WAREHOUSE_REQUISITION_CREATE,
          PERMISSIONS.WAREHOUSE_REQUISITION_RECEIVE,
        ],
        requiredDataScope: { domain: 'WAREHOUSE', modes: ['ALL', 'WAREHOUSE_LIST', 'CENTRAL_WAREHOUSE', 'STORE_LIST', 'OWN_STORE'] },
      },
      {
        key: 'daily-loss',
        label: '每日报损',
        to: '/daily-loss',
        icon: 'inventory',
        requiredPermission: PERMISSIONS.DAILY_LOSS_READ,
        requiredDataScope: { domain: 'WAREHOUSE', modes: ['ALL', 'WAREHOUSE_LIST', 'STORE_LIST', 'OWN_STORE'] },
      },
      {
        label: '门店详情',
        key: 'store-detail',
        to: '/store-detail',
        icon: 'store',
        requiredPermission: PERMISSIONS.STORE_READ,
        requiredDataScope: { domain: 'STORE', modes: ['ALL', 'STORE_LIST', 'OWN_STORE'] },
      },
    ],
  },
  {
    title: '运营管理',
    items: [
      { key: 'inspection-management', label: '督导巡店', to: '/operations/inspection', icon: 'inspection', requiredPermission: PERMISSIONS.INSPECTION_MANAGE },
      {
        label: '巡检记录',
        key: 'inspection-records',
        to: '/operations/inspection/records',
        icon: 'inspection',
        requiredPermission: PERMISSIONS.INSPECTION_READ,
        requiredDataScope: { domain: 'INSPECTION', modes: ['OWN_STORE'] },
      },
      {
        label: '巡检整改',
        key: 'inspection-rectifications',
        to: '/store/inspection/rectifications',
        icon: 'inspection',
        requiredPermission: PERMISSIONS.INSPECTION_READ,
        allowedRoles: ['STORE_MANAGER'],
        requiredDataScope: { domain: 'INSPECTION', modes: ['OWN_STORE'] },
      },
      {
        label: '整改复核',
        key: 'inspection-review-queue',
        to: '/operations/inspection/reviews',
        icon: 'inspection',
        requiredPermission: PERMISSIONS.INSPECTION_MANAGE,
        allowedRoles: ['SUPERVISOR', 'OPERATIONS'],
        requiredDataScope: { domain: 'INSPECTION', modes: ['ALL', 'STORE_LIST'] },
      },
      {
        label: '培训考试',
        key: 'exam-center',
        to: '/exam-center',
        icon: 'exam',
        requiredPermission: PERMISSIONS.EXAM_MANAGE,
        alternativePermissions: [PERMISSIONS.EXAM_REPORT],
        allowedRoles: ['OPERATIONS', 'STORE_MANAGER'],
        requiredDataScope: { domain: 'EXAM', modes: ['ALL', 'STORE_LIST', 'OWN_STORE'] },
      },
      { key: 'platform-settings', label: '平台配置', to: '/platform-login', icon: 'platform', requiredPermission: PERMISSIONS.PLATFORM_READ, allowedRoles: ['OPERATIONS'] },
    ],
  },
  {
    title: '系统管理',
    items: [
      { key: 'store-management', label: '门店管理', to: '/stores', icon: 'store', requiredPermission: PERMISSIONS.STORE_MANAGE, bossOnly: true },
      { key: 'staff-profiles', label: '员工档案', to: '/staff', icon: 'users', requiredPermission: PERMISSIONS.EMPLOYEE_READ },
      { key: 'user-permissions', label: '账号权限', to: '/users', icon: 'users', requiredPermission: PERMISSIONS.SYSTEM_USER_MANAGE },
      { key: 'operation-logs', label: '操作日志', to: '/logs', icon: 'log', requiredPermission: PERMISSIONS.SYSTEM_AUDIT_READ },
    ],
  },
]

export const UTILITY_MENU_CONFIG: PermissionMenuItem[] = [
  { key: 'assistant', label: '门店经营助手', to: '/assistant', icon: 'assistant', requiredPermission: PERMISSIONS.ASSISTANT_USE },
  { key: 'employee-assistant', label: '员工服务助手', to: '/employee-assistant', icon: 'assistant', requiredPermission: PERMISSIONS.EMPLOYEE_ASSISTANT_USE },
]

export function resolveMenuGroups(subject: MenuSubject): PermissionMenuGroup[] {
  const workspaceItems = WORKSPACE_ITEMS.filter((item) => (
    item.workspacePath === subject.defaultWorkspace && canShow(item, subject)
  ))
  const configured = MENU_GROUP_CONFIG
    .map((group) => ({ ...group, items: group.items.filter((item) => canShow(item, subject)) }))
    .filter((group) => group.items.length > 0)
  return workspaceItems.length ? [{ items: workspaceItems }, ...configured] : configured
}

export function resolveUtilityMenuItems(subject: MenuSubject) {
  return UTILITY_MENU_CONFIG.filter((item) => canShow(item, subject))
}

function canShow(item: PermissionMenuItem, subject: MenuSubject) {
  if (item.bossOnly && !isBossRole(subject.role)) return false
  if (item.financeOrBossOnly && !canUseFinanceProfitImport(subject.role, subject.permissions)) return false
  if (item.allowedRoles?.length
    && !isBossRole(subject.role)
    && !item.allowedRoles.some((role) => normalizeRoleCode(role) === normalizeRoleCode(subject.role))) return false
  const canAccess = hasPermission(subject.role, subject.permissions, item.requiredPermission)
    || item.alternativePermissions?.some((permission) => hasPermission(subject.role, subject.permissions, permission))
  if (!canAccess) return false
  if (!item.requiredDataScope) return true
  const scope = subject.dataScopes?.[item.requiredDataScope.domain]
  const mode = String(scope?.mode || 'NONE').trim().toUpperCase()
  return item.requiredDataScope.modes.includes(mode)
}
