import type { SessionUser } from '@/types/auth'

export type MobileCapabilityKey =
  | 'inventory'
  | 'requisition'
  | 'warehouse'
  | 'inspection'
  | 'rectification'
  | 'learning'
  | 'exam'
  | 'assistant'
  | 'summary'

interface MobileCapabilityRule {
  roles: string[]
  anyPermission: string[]
  scopeDomain?: string
  allowedScopeModes?: string[]
}

const CAPABILITY_RULES: Record<MobileCapabilityKey, MobileCapabilityRule> = {
  inventory: {
    roles: ['STORE_MANAGER'],
    anyPermission: ['warehouse.store.read', 'warehouse.read'],
    scopeDomain: 'WAREHOUSE',
    allowedScopeModes: ['ALL', 'WAREHOUSE_LIST', 'STORE_LIST', 'OWN_STORE'],
  },
  requisition: {
    roles: ['STORE_MANAGER'],
    anyPermission: ['warehouse.requisition.create', 'warehouse.requisition.receive'],
    scopeDomain: 'WAREHOUSE',
    allowedScopeModes: ['ALL', 'WAREHOUSE_LIST', 'STORE_LIST', 'OWN_STORE'],
  },
  warehouse: {
    roles: ['WAREHOUSE'],
    anyPermission: ['warehouse.requisition.process', 'warehouse.central.read'],
    scopeDomain: 'WAREHOUSE',
    allowedScopeModes: ['ALL', 'WAREHOUSE_LIST', 'CENTRAL_WAREHOUSE'],
  },
  inspection: {
    roles: ['OPERATIONS', 'SUPERVISOR'],
    anyPermission: ['inspection.manage', 'inspection.read'],
    scopeDomain: 'INSPECTION',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE'],
  },
  rectification: {
    roles: ['STORE_MANAGER', 'OPERATIONS', 'SUPERVISOR'],
    anyPermission: ['todo.transition', 'inspection.manage'],
    scopeDomain: 'INSPECTION',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE'],
  },
  learning: {
    roles: ['EMPLOYEE'],
    anyPermission: ['exam.learn'],
    scopeDomain: 'EXAM',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE', 'SELF'],
  },
  exam: {
    roles: ['EMPLOYEE'],
    anyPermission: ['exam.learn'],
    scopeDomain: 'EXAM',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE', 'SELF'],
  },
  assistant: {
    roles: ['EMPLOYEE'],
    anyPermission: ['employee_assistant.use'],
  },
  summary: {
    roles: ['BOSS', 'FINANCE'],
    anyPermission: ['finance.profit.read', 'system.dashboard.read'],
    scopeDomain: 'FINANCE',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE'],
  },
}

/**
 * 一期产品入口约束。角色只缩小移动端能力，不授予任何权限；真正的数据范围和动作仍由后端校验。
 */
export function canUseMobileCapability(user: SessionUser | null, key: MobileCapabilityKey): boolean {
  if (!user) return false
  const rule = CAPABILITY_RULES[key]
  const role = canonicalRole(user.role)
  if (!rule.roles.some((allowedRole) => canonicalRole(allowedRole) === role)) return false
  const permissions = new Set(user.permissions.map((permission) => permission.trim().toLowerCase()))
  if (!rule.anyPermission.some((permission) => permissions.has(permission))) return false
  if (!rule.scopeDomain) return true
  const mode = String(user.dataScopes[rule.scopeDomain]?.mode || 'NONE').trim().toUpperCase()
  return Boolean(rule.allowedScopeModes?.includes(mode))
}

function canonicalRole(value: string): string {
  const role = String(value || '').trim().toUpperCase()
  if (role === 'ADMIN' || role === 'OWNER') return 'BOSS'
  if (role === 'SUPERVISOR' || role === 'OPS') return 'OPERATIONS'
  return role
}
