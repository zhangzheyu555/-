import { PERMISSIONS, type PermissionCode } from './permissions'
import { normalizeRoleCode } from './roles'

export const WORKSPACE_PERMISSIONS: Record<string, PermissionCode> = {
  '/boss': PERMISSIONS.SYSTEM_DASHBOARD_READ,
  '/finance': PERMISSIONS.FINANCE_PROFIT_READ,
  '/warehouse': PERMISSIONS.WAREHOUSE_READ,
  '/store': PERMISSIONS.STORE_READ,
  '/operations/inspection': PERMISSIONS.INSPECTION_READ,
  '/operations': PERMISSIONS.OPERATIONS_DASHBOARD_READ,
  '/employee': PERMISSIONS.EXAM_LEARN,
  '/learn/exams': PERMISSIONS.EXAM_LEARN,
}

const WORKSPACE_ALTERNATIVE_PERMISSIONS: Partial<Record<string, PermissionCode[]>> = {
  '/warehouse': [PERMISSIONS.WAREHOUSE_CENTRAL_READ],
}

export const RECOMMENDED_WORKSPACE_BY_ROLE: Record<string, string> = {
  BOSS: '/boss',
  FINANCE: '/finance',
  WAREHOUSE: '/warehouse',
  STORE_MANAGER: '/store',
  SUPERVISOR: '/operations/inspection',
  OPERATIONS: '/operations',
  EMPLOYEE: '/employee',
}

const WORKSPACE_ORDER_BY_ROLE: Record<string, string[]> = {
  BOSS: ['/boss', '/finance', '/warehouse', '/store', '/operations'],
  FINANCE: ['/finance'],
  WAREHOUSE: ['/warehouse'],
  STORE_MANAGER: ['/store'],
  SUPERVISOR: ['/operations/inspection'],
  OPERATIONS: ['/operations'],
  EMPLOYEE: ['/employee'],
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
    .filter((path) => canAccessWorkspace(access, path))
}

function normalizeWorkspace(value?: string) {
  const path = String(value || '').trim().replace(/\/$/, '') || '/'
  return WORKSPACE_PERMISSIONS[path] ? path : ''
}

function canAccessWorkspace(access: WorkspaceAccess, path: string) {
  const primary = WORKSPACE_PERMISSIONS[path]
  if (primary && access.hasPermission(primary)) return true
  return (WORKSPACE_ALTERNATIVE_PERMISSIONS[path] || [])
    .some((permission) => access.hasPermission(permission))
}
