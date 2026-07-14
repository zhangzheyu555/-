import { isBossRole } from './roles'

export const PERMISSIONS = {
  SYSTEM_USER_MANAGE: 'system.user.manage',
  SYSTEM_AUDIT_READ: 'system.audit.read',
  SYSTEM_AUDIT_WRITE: 'system.audit.write',
  SYSTEM_DASHBOARD_READ: 'system.dashboard.read',
  SYSTEM_MIGRATION_MANAGE: 'system.migration.manage',
  OPERATIONS_DASHBOARD_READ: 'operations.dashboard.read',
  STORE_READ: 'store.read',
  STORE_MANAGE: 'store.manage',
  EMPLOYEE_READ: 'employee.read',
  EMPLOYEE_MANAGE: 'employee.manage',
  FINANCE_PROFIT_READ: 'finance.profit.read',
  FINANCE_PROFIT_WRITE: 'finance.profit.write',
  FINANCE_PROFIT_DELETE: 'finance.profit.delete',
  FINANCE_EXPORT: 'finance.export',
  EXPENSE_CREATE: 'expense.create',
  EXPENSE_READ: 'expense.read',
  EXPENSE_REVIEW: 'expense.review',
  SALARY_READ: 'salary.read',
  SALARY_EDIT: 'salary.edit',
  SALARY_REVIEW: 'salary.review',
  SALARY_PAY: 'salary.pay',
  WAREHOUSE_CENTRAL_READ: 'warehouse.central.read',
  WAREHOUSE_CENTRAL_MANAGE: 'warehouse.central.manage',
  WAREHOUSE_STORE_READ: 'warehouse.store.read',
  WAREHOUSE_REQUISITION_CREATE: 'warehouse.requisition.create',
  WAREHOUSE_REQUISITION_REVIEW: 'warehouse.requisition.review',
  WAREHOUSE_REQUISITION_RECEIVE: 'warehouse.requisition.receive',
  WAREHOUSE_READ: 'warehouse.read',
  WAREHOUSE_PURCHASE: 'warehouse.purchase',
  WAREHOUSE_TRANSFER_REQUEST: 'warehouse.transfer.request',
  WAREHOUSE_TRANSFER_APPROVE: 'warehouse.transfer.approve',
  WAREHOUSE_TRANSFER_SHIP: 'warehouse.transfer.ship',
  WAREHOUSE_TRANSFER_RECEIVE: 'warehouse.transfer.receive',
  WAREHOUSE_REQUISITION_PROCESS: 'warehouse.requisition.process',
  WAREHOUSE_CONFIGURE: 'warehouse.configure',
  INVENTORY_READ: 'inventory.read',
  INVENTORY_MANAGE: 'inventory.manage',
  INVENTORY_REVIEW: 'inventory.review',
  INSPECTION_READ: 'inspection.read',
  INSPECTION_MANAGE: 'inspection.manage',
  EXAM_LEARN: 'exam.learn',
  EXAM_MANAGE: 'exam.manage',
  EXAM_REPORT: 'exam.report',
  PLATFORM_READ: 'platform.read',
  PLATFORM_MANAGE: 'platform.manage',
  ASSISTANT_USE: 'assistant.use',
  ATTACHMENT_READ: 'attachment.read',
  ATTACHMENT_WRITE: 'attachment.write',
  TODO_READ: 'todo.read',
  TODO_TRANSITION: 'todo.transition',
} as const

export type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS]

export function normalizePermissionCode(permission?: string) {
  return String(permission || '').trim().toLowerCase()
}

export function hasPermission(
  role: string | undefined,
  permissions: readonly string[] | undefined,
  permission: string | undefined,
) {
  if (!permission) return true
  if (isBossRole(role)) return true
  const expected = normalizePermissionCode(permission)
  return Boolean(expected && permissions?.some((value) => normalizePermissionCode(value) === expected))
}

export function hasEveryPermission(
  role: string | undefined,
  permissions: readonly string[] | undefined,
  requiredPermissions: readonly string[],
) {
  return requiredPermissions.every((permission) => hasPermission(role, permissions, permission))
}
