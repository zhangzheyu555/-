export type MobileCoverage = 'M1' | 'M2' | 'M3' | 'SERVER'

/** Every PC permission must appear exactly once. CI compares this list with frontend-vue. */
export const MOBILE_PERMISSION_COVERAGE: Readonly<Record<string, MobileCoverage>> = {
  'system.user.manage':'M3','system.audit.read':'M2','system.audit.write':'SERVER','system.dashboard.read':'M1','system.migration.manage':'M3',
  'operations.dashboard.read':'M1','store.read':'M1','store.manage':'M3','employee.read':'M2','employee.manage':'M3',
  'finance.profit.read':'M1','finance.profit.write':'M3','finance.profit.import':'M3','finance.profit.delete':'M3','finance.export':'M3',
  'expense.create':'M1','expense.read':'M1','expense.review':'M1','salary.read':'M1','salary.edit':'M3','salary.review':'M1','salary.pay':'M1',
  'warehouse.central.read':'M1','warehouse.central.manage':'M1','warehouse.store.read':'M1','warehouse.requisition.create':'M1',
  'warehouse.requisition.review':'M1','warehouse.requisition.receive':'M1','warehouse.read':'M1','warehouse.purchase':'M1',
  'warehouse.transfer.request':'M1','warehouse.transfer.approve':'M1','warehouse.transfer.ship':'M1','warehouse.transfer.receive':'M1',
  'warehouse.requisition.process':'M1','warehouse.configure':'M3','inventory.read':'M1','inventory.manage':'M1','inventory.review':'M1',
  'daily_loss.read':'M1','daily_loss.create':'M1','daily_loss.review':'M1','inspection.read':'M1','inspection.manage':'M1',
  'exam.learn':'M1','exam.manage':'M1','exam.report':'M1','platform.read':'M2','platform.manage':'M3','assistant.use':'M1',
  'employee_assistant.use':'M1','employee_assistant.knowledge_manage':'M3','employee_assistant.handoff_manage':'M2',
  'attachment.read':'M1','attachment.write':'M1','todo.read':'M1','todo.transition':'M1',
}
