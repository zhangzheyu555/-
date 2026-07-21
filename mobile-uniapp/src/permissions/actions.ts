import { hasAllowedRole, hasPermission, hasScopeMode, isBoss } from './access'
import type { SessionUser } from '@/types/auth'

export type MobileAction =
  | 'todo.resolve' | 'todo.escalate' | 'todo.close'
  | 'expense.create' | 'expense.review' | 'expense.supplement'
  | 'salary.review' | 'salary.pay'
  | 'dailyLoss.create' | 'dailyLoss.review'
  | 'warehouse.requisition.review' | 'warehouse.requisition.ship' | 'warehouse.return.review' | 'warehouse.return.receive'
  | 'warehouse.purchase' | 'warehouse.transfer.request' | 'warehouse.transfer.approve' | 'warehouse.transfer.ship' | 'warehouse.transfer.receive'
  | 'warehouse.alert.configure'
  | 'inventory.manage' | 'inventory.review'
  | 'employeeAssistant.handoff.claim' | 'employeeAssistant.handoff.reply' | 'employeeAssistant.handoff.close'

interface ActionRule {
  roles: string[]
  permission: string
  scopeDomain?: string
  scopeModes?: string[]
  bossOnly?: boolean
  denyBoss?: boolean
}

const STORE_SCOPE = ['ALL', 'STORE_LIST', 'OWN_STORE']
const WAREHOUSE_SCOPE = ['ALL', 'WAREHOUSE_LIST', 'CENTRAL_WAREHOUSE', 'STORE_LIST', 'OWN_STORE']

export const MOBILE_ACTION_RULES: Record<MobileAction, ActionRule> = {
  'todo.resolve': { roles: ['FINANCE', 'STORE_MANAGER', 'WAREHOUSE', 'OPERATIONS', 'SUPERVISOR'], permission: 'todo.transition' },
  'todo.escalate': { roles: ['FINANCE', 'STORE_MANAGER', 'WAREHOUSE', 'OPERATIONS', 'SUPERVISOR'], permission: 'todo.transition', denyBoss: true },
  'todo.close': { roles: ['BOSS'], permission: 'todo.transition', bossOnly: true },
  'expense.create': { roles: ['STORE_MANAGER', 'FINANCE'], permission: 'expense.create', scopeDomain: 'FINANCE', scopeModes: STORE_SCOPE },
  'expense.review': { roles: ['FINANCE'], permission: 'expense.review', scopeDomain: 'FINANCE', scopeModes: STORE_SCOPE },
  'expense.supplement': { roles: ['STORE_MANAGER', 'FINANCE'], permission: 'expense.create', scopeDomain: 'FINANCE', scopeModes: STORE_SCOPE },
  'salary.review': { roles: ['FINANCE'], permission: 'salary.review', scopeDomain: 'SALARY', scopeModes: STORE_SCOPE },
  'salary.pay': { roles: ['FINANCE'], permission: 'salary.pay', scopeDomain: 'SALARY', scopeModes: STORE_SCOPE },
  'dailyLoss.create': { roles: ['STORE_MANAGER'], permission: 'daily_loss.create', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'dailyLoss.review': { roles: ['WAREHOUSE', 'OPERATIONS'], permission: 'daily_loss.review', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.requisition.review': { roles: ['WAREHOUSE'], permission: 'warehouse.requisition.review', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.requisition.ship': { roles: ['WAREHOUSE'], permission: 'warehouse.requisition.process', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.return.review': { roles: ['WAREHOUSE'], permission: 'warehouse.requisition.review', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.return.receive': { roles: ['WAREHOUSE'], permission: 'warehouse.requisition.process', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.purchase': { roles: ['WAREHOUSE'], permission: 'warehouse.purchase', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.transfer.request': { roles: ['WAREHOUSE'], permission: 'warehouse.transfer.request', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.transfer.approve': { roles: ['WAREHOUSE'], permission: 'warehouse.transfer.approve', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.transfer.ship': { roles: ['WAREHOUSE'], permission: 'warehouse.transfer.ship', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.transfer.receive': { roles: ['WAREHOUSE'], permission: 'warehouse.transfer.receive', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'warehouse.alert.configure': { roles: ['WAREHOUSE'], permission: 'warehouse.configure', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'inventory.manage': { roles: ['OPERATIONS'], permission: 'inventory.manage', scopeDomain: 'WAREHOUSE', scopeModes: WAREHOUSE_SCOPE },
  'inventory.review': { roles: ['OPERATIONS'], permission: 'inventory.review' },
  'employeeAssistant.handoff.claim': { roles: ['OPERATIONS'], permission: 'employee_assistant.handoff_manage' },
  'employeeAssistant.handoff.reply': { roles: ['OPERATIONS'], permission: 'employee_assistant.handoff_manage' },
  'employeeAssistant.handoff.close': { roles: ['OPERATIONS'], permission: 'employee_assistant.handoff_manage' },
}

export function canPerformMobileAction(user: SessionUser | null | undefined, action: MobileAction): boolean {
  if (!user) return false
  const rule = MOBILE_ACTION_RULES[action]
  if (rule.bossOnly && !isBoss(user)) return false
  if (rule.denyBoss && isBoss(user)) return false
  return hasAllowedRole(user, rule.roles)
    && hasPermission(user, rule.permission)
    && hasScopeMode(user, rule.scopeDomain, rule.scopeModes)
}
