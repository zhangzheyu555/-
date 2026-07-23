import type { SessionUser } from '@/types/auth'
import { hasAllowedRole, hasAnyPermission, hasScopeMode } from '@/permissions'

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
  | 'expenses'
  | 'salary'
  | 'dailyLoss'
  | 'operations'
  | 'operationsMonitor'
  | 'audit'
  | 'businessAssistant'
  | 'business'
  | 'trainingProgress'

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
    anyPermission: ['warehouse.requisition.process', 'warehouse.requisition.review', 'warehouse.central.read', 'warehouse.purchase', 'warehouse.configure', 'warehouse.transfer.request', 'warehouse.transfer.approve', 'warehouse.transfer.ship', 'warehouse.transfer.receive'],
    scopeDomain: 'WAREHOUSE',
    allowedScopeModes: ['ALL', 'WAREHOUSE_LIST', 'CENTRAL_WAREHOUSE'],
  },
  inspection: {
    roles: ['SUPERVISOR'],
    anyPermission: ['inspection.manage', 'inspection.read'],
    scopeDomain: 'INSPECTION',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE'],
  },
  rectification: {
    roles: ['STORE_MANAGER', 'SUPERVISOR'],
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
  expenses: {
    roles: ['STORE_MANAGER', 'FINANCE'],
    anyPermission: ['expense.read', 'expense.create', 'expense.review'],
    scopeDomain: 'FINANCE',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE'],
  },
  salary: {
    roles: ['STORE_MANAGER', 'FINANCE'],
    anyPermission: ['salary.read', 'salary.review', 'salary.pay'],
    scopeDomain: 'SALARY',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE', 'SELF'],
  },
  dailyLoss: {
    roles: ['STORE_MANAGER', 'SUPERVISOR', 'FINANCE'],
    anyPermission: ['daily_loss.read', 'daily_loss.create', 'daily_loss.review'],
    scopeDomain: 'WAREHOUSE',
    allowedScopeModes: ['ALL', 'WAREHOUSE_LIST', 'STORE_LIST', 'OWN_STORE'],
  },
  operations: {
    roles: ['SUPERVISOR'],
    anyPermission: ['operations.dashboard.read', 'inventory.read', 'inventory.manage', 'inventory.review', 'exam.report', 'platform.read', 'employee_assistant.handoff_manage'],
  },
  operationsMonitor: {
    roles: ['SUPERVISOR'],
    anyPermission: ['exam.report', 'platform.read', 'employee_assistant.handoff_manage'],
  },
  audit: {
    roles: ['BOSS'],
    anyPermission: ['system.audit.read'],
  },
  businessAssistant: {
    roles: ['BOSS', 'FINANCE', 'STORE_MANAGER', 'SUPERVISOR'],
    anyPermission: ['assistant.use'],
  },
  business: {
    roles: ['STORE_MANAGER'],
    anyPermission: ['finance.profit.read'],
    scopeDomain: 'FINANCE',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE'],
  },
  trainingProgress: {
    roles: ['STORE_MANAGER'],
    anyPermission: ['exam.report'],
    scopeDomain: 'EXAM',
    allowedScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE'],
  },
}

/**
 * 一期产品入口约束。角色只缩小移动端能力，不授予任何权限；真正的数据范围和动作仍由后端校验。
 */
export function canUseMobileCapability(user: SessionUser | null, key: MobileCapabilityKey): boolean {
  if (!user) return false
  const rule = CAPABILITY_RULES[key]
  return hasAllowedRole(user, rule.roles)
    && hasAnyPermission(user, rule.anyPermission)
    && hasScopeMode(user, rule.scopeDomain, rule.allowedScopeModes)
}
