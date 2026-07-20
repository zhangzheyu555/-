package com.storeprofit.system.platform.authorization;

import java.util.Set;

/** Stable permission codes shared by backend authorization wrappers and the frontend contract. */
public final class PermissionCodes {
  public static final String SYSTEM_USER_MANAGE = "system.user.manage";
  public static final String SYSTEM_AUDIT_READ = "system.audit.read";
  public static final String SYSTEM_AUDIT_WRITE = "system.audit.write";
  public static final String SYSTEM_DASHBOARD_READ = "system.dashboard.read";
  public static final String SYSTEM_MIGRATION_MANAGE = "system.migration.manage";
  public static final String OPERATIONS_DASHBOARD_READ = "operations.dashboard.read";

  public static final String STORE_READ = "store.read";
  public static final String STORE_MANAGE = "store.manage";
  public static final String EMPLOYEE_READ = "employee.read";
  public static final String EMPLOYEE_MANAGE = "employee.manage";

  public static final String FINANCE_PROFIT_READ = "finance.profit.read";
  public static final String FINANCE_PROFIT_WRITE = "finance.profit.write";
  public static final String FINANCE_PROFIT_IMPORT = "finance.profit.import";
  public static final String FINANCE_PROFIT_DELETE = "finance.profit.delete";
  public static final String FINANCE_EXPORT = "finance.export";

  public static final String DAILY_LOSS_READ = "daily_loss.read";
  public static final String DAILY_LOSS_CREATE = "daily_loss.create";
  public static final String DAILY_LOSS_REVIEW = "daily_loss.review";
  public static final String DAILY_LOSS_EXPORT = "daily_loss.export";

  public static final String EXPENSE_CREATE = "expense.create";
  public static final String EXPENSE_READ = "expense.read";
  public static final String EXPENSE_REVIEW = "expense.review";

  public static final String SALARY_READ = "salary.read";
  public static final String SALARY_EDIT = "salary.edit";
  public static final String SALARY_REVIEW = "salary.review";
  public static final String SALARY_PAY = "salary.pay";

  public static final String WAREHOUSE_CENTRAL_READ = "warehouse.central.read";
  public static final String WAREHOUSE_CENTRAL_MANAGE = "warehouse.central.manage";
  public static final String WAREHOUSE_STORE_READ = "warehouse.store.read";
  public static final String WAREHOUSE_REQUISITION_CREATE = "warehouse.requisition.create";
  public static final String WAREHOUSE_REQUISITION_REVIEW = "warehouse.requisition.review";
  public static final String WAREHOUSE_REQUISITION_RECEIVE = "warehouse.requisition.receive";
  public static final String WAREHOUSE_READ = "warehouse.read";
  public static final String WAREHOUSE_PURCHASE = "warehouse.purchase";
  public static final String WAREHOUSE_TRANSFER_REQUEST = "warehouse.transfer.request";
  public static final String WAREHOUSE_TRANSFER_APPROVE = "warehouse.transfer.approve";
  public static final String WAREHOUSE_TRANSFER_SHIP = "warehouse.transfer.ship";
  public static final String WAREHOUSE_TRANSFER_RECEIVE = "warehouse.transfer.receive";
  public static final String WAREHOUSE_REQUISITION_PROCESS = "warehouse.requisition.process";
  public static final String WAREHOUSE_CONFIGURE = "warehouse.configure";

  public static final String INVENTORY_READ = "inventory.read";
  public static final String INVENTORY_MANAGE = "inventory.manage";
  public static final String INVENTORY_REVIEW = "inventory.review";

  public static final String INSPECTION_READ = "inspection.read";
  public static final String INSPECTION_MANAGE = "inspection.manage";

  public static final String EXAM_LEARN = "exam.learn";
  public static final String EXAM_MANAGE = "exam.manage";
  public static final String EXAM_REPORT = "exam.report";

  public static final String PLATFORM_READ = "platform.read";
  public static final String PLATFORM_MANAGE = "platform.manage";
  public static final String ASSISTANT_USE = "assistant.use";
  public static final String EMPLOYEE_ASSISTANT_USE = "employee_assistant.use";
  public static final String EMPLOYEE_ASSISTANT_KNOWLEDGE_MANAGE = "employee_assistant.knowledge_manage";
  public static final String EMPLOYEE_ASSISTANT_HANDOFF_MANAGE = "employee_assistant.handoff_manage";
  public static final String ATTACHMENT_READ = "attachment.read";
  public static final String ATTACHMENT_WRITE = "attachment.write";
  public static final String TODO_READ = "todo.read";
  public static final String TODO_TRANSITION = "todo.transition";

  /**
   * Backend-owned baseline for the BOSS session contract. Database catalog rows can extend this
   * set, but an empty or partially seeded catalog must never collapse the highest role's menus.
   */
  public static final Set<String> ALL = Set.of(
      SYSTEM_USER_MANAGE,
      SYSTEM_AUDIT_READ,
      SYSTEM_AUDIT_WRITE,
      SYSTEM_DASHBOARD_READ,
      SYSTEM_MIGRATION_MANAGE,
      OPERATIONS_DASHBOARD_READ,
      STORE_READ,
      STORE_MANAGE,
      EMPLOYEE_READ,
      EMPLOYEE_MANAGE,
      FINANCE_PROFIT_READ,
      FINANCE_PROFIT_WRITE,
      FINANCE_PROFIT_IMPORT,
      FINANCE_PROFIT_DELETE,
      FINANCE_EXPORT,
      DAILY_LOSS_READ,
      DAILY_LOSS_CREATE,
      DAILY_LOSS_REVIEW,
      DAILY_LOSS_EXPORT,
      EXPENSE_CREATE,
      EXPENSE_READ,
      EXPENSE_REVIEW,
      SALARY_READ,
      SALARY_EDIT,
      SALARY_REVIEW,
      SALARY_PAY,
      WAREHOUSE_CENTRAL_READ,
      WAREHOUSE_CENTRAL_MANAGE,
      WAREHOUSE_STORE_READ,
      WAREHOUSE_REQUISITION_CREATE,
      WAREHOUSE_REQUISITION_REVIEW,
      WAREHOUSE_REQUISITION_RECEIVE,
      WAREHOUSE_READ,
      WAREHOUSE_PURCHASE,
      WAREHOUSE_TRANSFER_REQUEST,
      WAREHOUSE_TRANSFER_APPROVE,
      WAREHOUSE_TRANSFER_SHIP,
      WAREHOUSE_TRANSFER_RECEIVE,
      WAREHOUSE_REQUISITION_PROCESS,
      WAREHOUSE_CONFIGURE,
      INVENTORY_READ,
      INVENTORY_MANAGE,
      INVENTORY_REVIEW,
      INSPECTION_READ,
      INSPECTION_MANAGE,
      EXAM_LEARN,
      EXAM_MANAGE,
      EXAM_REPORT,
      PLATFORM_READ,
      PLATFORM_MANAGE,
      ASSISTANT_USE,
      EMPLOYEE_ASSISTANT_USE,
      EMPLOYEE_ASSISTANT_KNOWLEDGE_MANAGE,
      EMPLOYEE_ASSISTANT_HANDOFF_MANAGE,
      ATTACHMENT_READ,
      ATTACHMENT_WRITE,
      TODO_READ,
      TODO_TRANSITION
  );

  private PermissionCodes() {
  }
}
