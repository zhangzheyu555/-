import { PERMISSIONS, type PermissionCode } from './permissions'
import { normalizeRoleCode } from './roles'

export const WORKSPACE_PERMISSIONS: Record<string, PermissionCode> = {
  '/boss': PERMISSIONS.SYSTEM_DASHBOARD_READ,
  '/finance': PERMISSIONS.FINANCE_PROFIT_READ,
  '/warehouse': PERMISSIONS.WAREHOUSE_READ,
  '/store': PERMISSIONS.STORE_READ,
  '/operations': PERMISSIONS.OPERATIONS_DASHBOARD_READ,
  '/learn/exams': PERMISSIONS.EXAM_LEARN,
}

export const RECOMMENDED_WORKSPACE_BY_ROLE: Record<string, string> = {
  BOSS: '/boss',
  FINANCE: '/finance',
  WAREHOUSE: '/warehouse',
  STORE_MANAGER: '/store',
  OPERATIONS: '/operations',
  EMPLOYEE: '/learn/exams',
}

const WORKSPACE_ORDER_BY_ROLE: Record<string, string[]> = {
  BOSS: ['/boss', '/finance', '/warehouse', '/store', '/operations'],
  FINANCE: ['/finance'],
  WAREHOUSE: ['/warehouse'],
  STORE_MANAGER: ['/store'],
  OPERATIONS: ['/operations'],
  EMPLOYEE: ['/learn/exams'],
}

interface WorkspaceAccess {
  role: string
  defaultWorkspace: string
  hasPermission: (permission: string) => boolean
}

export function resolveAvailableWorkspace(access: WorkspaceAccess): string | null {
  const role = normalizeRoleCode(access.role)
  const requested = normalizeWorkspace(access.defaultWorkspace)
  const recommended = RECOMMENDED_WORKSPACE_BY_ROLE[role] || ''
  const available = listAvailableWorkspaces(access)
  const candidates = Array.from(new Set([requested, recommended, ...available].filter((path) => available.includes(path))))
  return candidates[0] || null
}

export function listAvailableWorkspaces(access: WorkspaceAccess): string[] {
  const role = normalizeRoleCode(access.role)
  return (WORKSPACE_ORDER_BY_ROLE[role] || [])
    .filter((path) => access.hasPermission(WORKSPACE_PERMISSIONS[path]))
}

function normalizeWorkspace(value?: string) {
  const path = String(value || '').trim().replace(/\/$/, '') || '/'
  return WORKSPACE_PERMISSIONS[path] ? path : ''
}
