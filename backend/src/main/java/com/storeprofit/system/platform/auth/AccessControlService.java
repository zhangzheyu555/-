package com.storeprofit.system.platform.auth;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Central authorization boundary for business APIs. Controllers obtain the authenticated user
 * here and services use the same policy for role and store-scope checks.
 */
@Service
public class AccessControlService {
  private static final Logger log = LoggerFactory.getLogger(AccessControlService.class);
  public static final String BOSS_ROLE = "BOSS";
  /** Roles that always have full store scope regardless of database assignments. */
  private static final Set<String> GLOBAL_STORE_ROLES = Set.of("BOSS", "FINANCE", "SUPERVISOR", "WAREHOUSE");

  private final AuthService authService;
  private final AuthRepository authRepository;
  private final AuditRepository auditRepository;
  private final AuthorizationService authorizationService;
  private final DataScopeService dataScopeService;

  @Autowired
  public AccessControlService(
      AuthService authService,
      AuthRepository authRepository,
      AuditRepository auditRepository,
      AuthorizationService authorizationService,
      DataScopeService dataScopeService
  ) {
    this.authService = authService;
    this.authRepository = authRepository;
    this.auditRepository = auditRepository;
    this.authorizationService = authorizationService;
    this.dataScopeService = dataScopeService;
  }

  /** Compatibility constructor retained for isolated service tests. */
  public AccessControlService(
      AuthService authService,
      AuthRepository authRepository,
      AuditRepository auditRepository
  ) {
    this(authService, authRepository, auditRepository, null, null);
  }

  public AuthUser requireUser(String authorization) {
    return authService.requireUser(authorization);
  }

  public void requireFinanceRead(AuthUser user) {
    requirePermission(user, PermissionCodes.FINANCE_PROFIT_READ, "查看经营数据");
  }

  /**
   * Profit data entry (manual save, monthly import commit, modify, delete) is limited to
   * BOSS and FINANCE.  Store managers keep read-only access to their own store but can
   * never write profit data — even if a stale personal ALLOW or legacy role template still
   * carries {@link PermissionCodes#FINANCE_PROFIT_WRITE}.
   */
  public void requireFinanceWrite(AuthUser user) {
    requireFinanceWrite(user, null, null);
  }

  /**
   * Retains the requested business scope in a rejected-write audit. The permission check must
   * happen before a write, but that must not make an attempted cross-store write unauditable.
   */
  public void requireFinanceWrite(AuthUser user, String requestedStoreId, String requestedMonth) {
    if (!hasPermission(user, PermissionCodes.FINANCE_PROFIT_WRITE)) {
      deny(
          user,
          "录入经营数据",
          "API",
          PermissionCodes.FINANCE_PROFIT_WRITE,
          requestedStoreId,
          requestedMonth,
          "账号不具备权限 " + PermissionCodes.FINANCE_PROFIT_WRITE
      );
    }
    if (!isBoss(user) && !hasAnyRole(user, "FINANCE")) {
      deny(
          user,
          "录入经营数据",
          "API",
          PermissionCodes.FINANCE_PROFIT_WRITE,
          requestedStoreId,
          requestedMonth,
          "经营数据录入仅限财务或老板；店长可查看本店经营结果"
      );
    }
  }

  /**
   * Monthly imports can overwrite a complete accounting period, so they remain a finance-office
   * responsibility. Store managers retain {@link #requireFinanceWrite(AuthUser)} for manual
   * entry of their own store, but cannot use an import endpoint even if a personal override
   * attempts to grant the dedicated import permission.
   */
  public void requireFinanceImport(AuthUser user) {
    requireFinanceImport(user, null, null);
  }

  public void requireFinanceImport(AuthUser user, String requestedStoreId, String requestedMonth) {
    if (!hasPermission(user, PermissionCodes.FINANCE_PROFIT_IMPORT)) {
      deny(
          user,
          "导入经营数据",
          "API",
          PermissionCodes.FINANCE_PROFIT_IMPORT,
          requestedStoreId,
          requestedMonth,
          "账号不具备权限 " + PermissionCodes.FINANCE_PROFIT_IMPORT
      );
    }
    if (!isBoss(user) && !hasAnyRole(user, "FINANCE")) {
      deny(
          user,
          "导入经营数据",
          "API",
          PermissionCodes.FINANCE_PROFIT_IMPORT,
          requestedStoreId,
          requestedMonth,
          "月度经营数据导入仅限财务或老板"
      );
    }
    // The eventual commit delegates to FinanceService.save(). Check the write permission here,
    // before a file is accepted or a preview job is allocated, so a finance user with an
    // explicit write DENY receives one clear, auditable refusal at the import boundary.
    requireFinanceWrite(user, requestedStoreId, requestedMonth);
  }

  /** Deletion of profit entries follows the same BOSS-or-FINANCE boundary as writes. */
  public void requireFinanceDelete(AuthUser user) {
    requirePermission(user, PermissionCodes.FINANCE_PROFIT_DELETE, "删除经营数据");
    if (!isBoss(user) && !hasAnyRole(user, "FINANCE")) {
      deny(
          user,
          "删除经营数据",
          "API",
          PermissionCodes.FINANCE_PROFIT_DELETE,
          null,
          "经营数据删除仅限财务或老板"
      );
    }
  }

  public void requireDailyLossRead(AuthUser user) {
    requirePermission(user, PermissionCodes.DAILY_LOSS_READ, "查看每日报损");
    if (isBoss(user) || hasAnyRole(user, "STORE_MANAGER", "SUPERVISOR")) {
      return;
    }
    deny(user, "查看每日报损", "API", PermissionCodes.DAILY_LOSS_READ, null,
        "每日报损查询仅限店长、督导或老板");
  }

  public void requireDailyLossCreate(AuthUser user) {
    requirePermission(user, PermissionCodes.DAILY_LOSS_CREATE, "提交每日报损");
    if (isBoss(user) || hasAnyRole(user, "STORE_MANAGER")) {
      return;
    }
    deny(user, "提交每日报损", "API", PermissionCodes.DAILY_LOSS_CREATE, null,
        "每日报损提交仅限店长或老板");
  }

  public void requireDailyLossReview(AuthUser user) {
    requirePermission(user, PermissionCodes.DAILY_LOSS_REVIEW, "复核每日报损");
    if (isBoss(user) || hasAnyRole(user, "SUPERVISOR")) {
      return;
    }
    deny(user, "复核每日报损", "API", PermissionCodes.DAILY_LOSS_REVIEW, null,
        "每日报损复核仅限督导或老板");
  }

  public void requireDailyLossExport(AuthUser user) {
    requirePermission(user, PermissionCodes.DAILY_LOSS_EXPORT, "导出每日报损");
    if (isBoss(user) || hasAnyRole(user, "SUPERVISOR")) {
      return;
    }
    deny(user, "导出每日报损", "API", PermissionCodes.DAILY_LOSS_EXPORT, null,
        "每日报损 Excel 导出仅限督导或老板");
  }

  public void requireExpenseRead(AuthUser user) {
    requireExpenseRead(user, null, null, null);
  }

  /**
   * Keeps document scope in the denial audit without weakening the role boundary.  Expense data
   * is financial data: a stale role template or personal override cannot make a supervisor or
   * employee a reader.
   */
  public void requireExpenseRead(AuthUser user, String expenseId, String storeId, String month) {
    if (!hasPermission(user, PermissionCodes.EXPENSE_READ)) {
      deny(
          user,
          "查看报销数据",
          "expense_claim",
          expenseId,
          storeId,
          month,
          "账号不具备权限 " + PermissionCodes.EXPENSE_READ
      );
    }
    if (isBoss(user) || hasAnyRole(user, "FINANCE", "STORE_MANAGER")) {
      return;
    }
    deny(
        user,
        "查看报销数据",
        "expense_claim",
        expenseId,
        storeId,
        month,
        "报销数据仅限财务、店长或老板查看"
    );
  }

  public void requireExpenseWrite(AuthUser user) {
    requireExpenseWrite(user, null, null, null);
  }

  /** Store manager writes remain further constrained by {@link #requireExpenseStoreAccess}. */
  public void requireExpenseWrite(AuthUser user, String expenseId, String storeId, String month) {
    if (!hasPermission(user, PermissionCodes.EXPENSE_CREATE)) {
      deny(
          user,
          "录入报销数据",
          "expense_claim",
          expenseId,
          storeId,
          month,
          "账号不具备权限 " + PermissionCodes.EXPENSE_CREATE
      );
    }
    if (isBoss(user) || hasAnyRole(user, "FINANCE", "STORE_MANAGER")) {
      return;
    }
    deny(
        user,
        "录入报销数据",
        "expense_claim",
        expenseId,
        storeId,
        month,
        "报销录入仅限财务、店长或老板"
    );
  }

  /**
   * Reimbursement receipts need both the generic attachment capability and the reimbursement
   * write boundary.  Keep every refusal tied to the actual expense document so a forged
   * cross-store upload remains auditable after its enclosing request rolls back.
   */
  public void requireExpenseAttachmentWrite(AuthUser user, String expenseId, String storeId, String month) {
    if (!hasPermission(user, PermissionCodes.ATTACHMENT_WRITE)) {
      deny(
          user,
          "上传报销凭证",
          "expense_claim",
          expenseId,
          storeId,
          month,
          "账号不具备权限 " + PermissionCodes.ATTACHMENT_WRITE
      );
    }
    requireExpenseWrite(user, expenseId, storeId, month);
  }

  public void requireExpenseReview(AuthUser user) {
    requireExpenseReview(user, null, null, null);
  }

  public void requireExpenseReview(AuthUser user, String expenseId, String storeId, String month) {
    if (!hasPermission(user, PermissionCodes.EXPENSE_REVIEW)) {
      deny(
          user,
          "审核报销数据",
          "expense_claim",
          expenseId,
          storeId,
          month,
          "账号不具备权限 " + PermissionCodes.EXPENSE_REVIEW
      );
    }
    if (isBoss(user) || hasAnyRole(user, "FINANCE")) {
      return;
    }
    deny(
        user,
        "审核报销数据",
        "expense_claim",
        expenseId,
        storeId,
        month,
        "报销审批仅限财务或老板"
    );
  }

  /**
   * Row-level finance scope rejection is audited against the actual expense document.  Generic
   * store checks only know a store id, which is insufficient for reimbursement approval traces.
   */
  public void requireExpenseStoreAccess(
      AuthUser user,
      String expenseId,
      String storeId,
      String month,
      String action
  ) {
    String normalizedStoreId = storeId == null ? "" : storeId.trim();
    if (normalizedStoreId.isBlank()) {
      deny(user, action, "expense_claim", expenseId, null, month, "未指定门店");
    }
    if (hasAllDataScope(user, DataScopeDomains.FINANCE)
        || allowedStoreIds(user, DataScopeDomains.FINANCE).contains(normalizedStoreId)) {
      return;
    }
    deny(
        user,
        action,
        "expense_claim",
        expenseId,
        normalizedStoreId,
        month,
        "门店不在当前账号的财务数据范围内"
    );
  }

  /** Records a document/store mismatch without degrading the denial to a generic attachment log. */
  public void requireExpenseDocumentStore(
      AuthUser user,
      String expenseId,
      String documentStoreId,
      String requestedStoreId,
      String month,
      String action
  ) {
    String expected = documentStoreId == null ? "" : documentStoreId.trim();
    String requested = requestedStoreId == null ? "" : requestedStoreId.trim();
    if (!expected.isBlank() && expected.equals(requested)) {
      return;
    }
    deny(
        user,
        action,
        "expense_claim",
        expenseId,
        expected.isBlank() ? requested : expected,
        month,
        "报销单所属门店与请求门店不一致"
    );
  }

  public void requireSalaryRead(AuthUser user) {
    requireSalaryRead(user, null, null, null);
  }

  /**
   * Salary requests are often rejected before a record can be read or changed.  Retain the
   * requested record, store and pay period in that denial so a payroll access attempt remains
   * auditable even when its business transaction rolls back.
   */
  public void requireSalaryRead(AuthUser user, String salaryId, String storeId, String month) {
    requireSalaryPermission(
        user, PermissionCodes.SALARY_READ, "查看工资数据", salaryId, storeId, month);
  }

  public void requireSalaryEdit(AuthUser user) {
    requireSalaryEdit(user, null, null, null);
  }

  public void requireSalaryEdit(AuthUser user, String salaryId, String storeId, String month) {
    requireSalaryPermission(
        user, PermissionCodes.SALARY_EDIT, "录入工资数据", salaryId, storeId, month);
  }

  public void requireSalaryReview(AuthUser user) {
    requireSalaryReview(user, null, null, null);
  }

  public void requireSalaryReview(AuthUser user, String salaryId, String storeId, String month) {
    requireSalaryPermission(
        user, PermissionCodes.SALARY_REVIEW, "审核工资数据", salaryId, storeId, month);
  }

  public void requireSalaryPay(AuthUser user) {
    requireSalaryPay(user, null, null, null);
  }

  public void requireSalaryPay(AuthUser user, String salaryId, String storeId, String month) {
    requireSalaryPermission(
        user, PermissionCodes.SALARY_PAY, "发放工资", salaryId, storeId, month);
  }

  private void requireSalaryPermission(
      AuthUser user,
      String permissionCode,
      String action,
      String salaryId,
      String storeId,
      String month
  ) {
    if (hasPermission(user, permissionCode)) {
      return;
    }
    deny(
        user,
        action,
        "salary_record",
        salaryId == null || salaryId.isBlank() ? permissionCode : salaryId,
        storeId,
        month,
        "账号不具备权限 " + permissionCode
    );
  }

  public void requireAuditRead(AuthUser user) {
    requirePermission(user, PermissionCodes.SYSTEM_AUDIT_READ, "查看操作日志");
  }

  public void requireAuditWrite(AuthUser user) {
    requirePermission(user, PermissionCodes.SYSTEM_AUDIT_WRITE, "补写操作日志");
  }

  public void requireUserManagementRead(AuthUser user) {
    requireBoss(user, "查看账号权限");
  }

  public void requireUserManagementWrite(AuthUser user) {
    requireBoss(user, "维护账号权限");
  }

  public void requireDataExport(AuthUser user) {
    requireDataExport(user, null, null);
  }

  /**
   * Exported operating data includes salary information, so it remains a finance-office
   * responsibility even when a stale personal permission override exists on another role.
   * Keep the requested scope in the denial audit for direct API calls.
   */
  public void requireDataExport(AuthUser user, String requestedStoreId, String requestedMonth) {
    if (!hasPermission(user, PermissionCodes.FINANCE_EXPORT)) {
      deny(
          user,
          "导出经营数据",
          "API",
          PermissionCodes.FINANCE_EXPORT,
          requestedStoreId,
          requestedMonth,
          "账号不具备权限 " + PermissionCodes.FINANCE_EXPORT
      );
    }
    if (!isBoss(user) && !hasAnyRole(user, "FINANCE")) {
      deny(
          user,
          "导出经营数据",
          "API",
          PermissionCodes.FINANCE_EXPORT,
          requestedStoreId,
          requestedMonth,
          "经营数据导出仅限财务或老板"
      );
    }
  }

  public void requireLegacyStorageAccess(AuthUser user) {
    // The legacy KV blob cannot enforce row-level store scope. Keep it behind the migration
    // permission until each key has moved to a structured tenant-scoped repository.
    requirePermission(user, PermissionCodes.SYSTEM_MIGRATION_MANAGE, "访问历史兼容数据");
  }

  public void requireAttachmentWrite(AuthUser user, String storeId) {
    requirePermission(user, PermissionCodes.ATTACHMENT_WRITE, "上传附件");
    requireStoreAccess(user, storeId, "上传附件");
  }

  public void requireAttachmentRead(AuthUser user, String storeId, Long uploadedBy) {
    requirePermission(user, PermissionCodes.ATTACHMENT_READ, "查看附件");
    if (storeId == null || storeId.isBlank()) {
      if (isGlobalStoreRole(user) || (uploadedBy != null && uploadedBy == user.id())) {
        return;
      }
      deny(user, "查看历史附件", "ATTACHMENT", null, null, "历史附件缺少门店归属，仅上传人或管理角色可访问");
    }
    requireStoreAccess(user, storeId, "查看附件");
  }

  public void requireInspectionRead(AuthUser user) {
    requirePermission(user, PermissionCodes.INSPECTION_READ, "查看巡检数据");
  }

  public void requireInspectionManage(AuthUser user) {
    requirePermission(user, PermissionCodes.INSPECTION_MANAGE, "处理巡检数据");
  }

  /**
   * A rectification is submitted by the store manager responsible for the bound store.  Keep the
   * rule role-bound even if a stale personal ALLOW grants TODO_TRANSITION to another role.
   */
  public void requireInspectionRectificationSubmit(AuthUser user) {
    requirePermission(user, PermissionCodes.TODO_TRANSITION, "提交巡检整改");
    if (isBoss(user) || hasAnyRole(user, "STORE_MANAGER")) {
      return;
    }
    deny(
        user,
        "提交巡检整改",
        "API",
        PermissionCodes.TODO_TRANSITION,
        null,
        "巡检整改只能由店长提交"
    );
  }

  /** Review remains separate from a store manager's submit permission. */
  public void requireInspectionRectificationReview(AuthUser user) {
    requireInspectionManage(user);
    if (isBoss(user) || hasAnyRole(user, "SUPERVISOR")) {
      return;
    }
    deny(
        user,
        "复核巡检整改",
        "API",
        PermissionCodes.INSPECTION_MANAGE,
        null,
        "巡检整改复核仅限督导或老板"
    );
  }

  public void requirePlatformAccess(AuthUser user) {
    requirePlatformRead(user);
  }

  public void requirePlatformRead(AuthUser user) {
    requirePermission(user, PermissionCodes.PLATFORM_READ, "查看平台数据");
  }

  public void requirePlatformManage(AuthUser user) {
    requirePermission(user, PermissionCodes.PLATFORM_MANAGE, "维护平台配置");
    if (isBoss(user) || hasAnyRole(user, "SUPERVISOR")) {
      return;
    }
    deny(user, "维护平台配置", "API", PermissionCodes.PLATFORM_MANAGE, null,
        "企迈平台配置仅限老板或督导维护");
  }

  /** QMAI data is a platform integration: finance can only read an authorized data scope. */
  public void requireQmaiRead(AuthUser user) {
    if (isBoss(user) || hasAnyRole(user, "SUPERVISOR")) {
      requirePlatformRead(user);
      return;
    }
    if (hasAnyRole(user, "FINANCE")) {
      requireFinanceRead(user);
      return;
    }
    requirePlatformRead(user);
    deny(user, "查看企迈经营数据", "API", PermissionCodes.PLATFORM_READ, null,
        "企迈经营数据仅限老板、督导或已授权财务查看");
  }

  /** Warehouse may read only the already-calculated material requirement snapshot. */
  public void requireQmaiRecipeRead(AuthUser user) {
    if (isBoss(user) || hasAnyRole(user, "SUPERVISOR")) {
      requirePlatformRead(user);
      return;
    }
    if (hasAnyRole(user, "WAREHOUSE")) {
      requirePermission(user, PermissionCodes.INVENTORY_READ, "查看配方用量快照");
      return;
    }
    requirePlatformRead(user);
    deny(user, "查看企迈配方用量", "API", PermissionCodes.PLATFORM_READ, null,
        "企迈配方用量仅限老板、督导或仓库查看");
  }

  public void requireExamRead(AuthUser user) {
    requirePermission(user, PermissionCodes.EXAM_LEARN, "查看培训考试");
  }

  public void requireExamManage(AuthUser user) {
    requirePermission(user, PermissionCodes.EXAM_MANAGE, "管理培训考试");
  }

  public void requireExamCompanyRead(AuthUser user) {
    requirePermission(user, PermissionCodes.EXAM_REPORT, "查看考试数据报表");
  }

  public void requireBossExamRead(AuthUser user) {
    requirePermission(user, PermissionCodes.SYSTEM_DASHBOARD_READ, "查看老板考试概览");
  }

  public void requireSystemDashboardRead(AuthUser user) {
    requirePermission(user, PermissionCodes.SYSTEM_DASHBOARD_READ, "查看老板工作台");
  }

  public void requireOperationsDashboardRead(AuthUser user) {
    requirePermission(user, PermissionCodes.OPERATIONS_DASHBOARD_READ, "查看督导工作台");
  }

  public void requireStoreRead(AuthUser user) {
    requirePermission(user, PermissionCodes.STORE_READ, "查看门店档案");
  }

  public void requireStoreManage(AuthUser user) {
    // 门店档案会影响全租户的业务边界、供货仓关系与历史业务归属。即使旧角色模板
    // 或个人 ALLOW 覆盖中仍有 store.manage，也不能把该管理能力下放给非老板账号。
    if (isBoss(user)) {
      return;
    }
    deny(
        user,
        "维护门店档案",
        "API",
        PermissionCodes.STORE_MANAGE,
        null,
        "门店档案管理仅限老板（系统管理员）"
    );
  }

  public void requireEmployeeRead(AuthUser user) {
    requirePermission(user, PermissionCodes.EMPLOYEE_READ, "查看员工档案");
  }

  public void requireEmployeeManage(AuthUser user) {
    requirePermission(user, PermissionCodes.EMPLOYEE_MANAGE, "维护员工档案");
  }

  public void requireEmployeeWorkbench(AuthUser user) {
    requirePermission(user, PermissionCodes.EXAM_LEARN, "查看员工工作台");
    if ("EMPLOYEE".equals(canonicalRole(user == null ? null : user.role()))) {
      return;
    }
    deny(user, "查看员工工作台", "API", PermissionCodes.EXAM_LEARN, null, "员工工作台仅限员工账号查看");
  }

  public void requireWarehouseCentralRead(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_CENTRAL_READ, "查看总仓库存");
  }

  public void requireWarehouseCentralManage(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_CENTRAL_MANAGE, "维护总仓库存");
  }

  public void requireWarehouseStoreRead(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_STORE_READ, "查看门店库存");
  }

  public void requireWarehouseRequisitionCreate(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_REQUISITION_CREATE, "创建门店叫货单");
  }

  public void requireWarehouseRequisitionReview(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_REQUISITION_REVIEW, "审核门店叫货单");
  }

  public void requireWarehouseRequisitionReceive(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_REQUISITION_RECEIVE, "确认门店收货");
  }

  public void requireWarehouseRead(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_READ, "查看仓库");
  }

  public void requireWarehousePurchase(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_PURCHASE, "办理外部采购");
  }

  public void requireWarehouseTransferRequest(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_TRANSFER_REQUEST, "申请仓间调拨");
  }

  public void requireWarehouseTransferApprove(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_TRANSFER_APPROVE, "审批仓间调拨");
  }

  public void requireWarehouseTransferShip(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_TRANSFER_SHIP, "执行仓间调拨发货");
  }

  public void requireWarehouseTransferReceive(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_TRANSFER_RECEIVE, "确认仓间调拨收货");
  }

  public void requireWarehouseRequisitionProcess(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_REQUISITION_PROCESS, "处理门店叫货");
  }

  public void requireWarehouseConfigure(AuthUser user) {
    requirePermission(user, PermissionCodes.WAREHOUSE_CONFIGURE, "维护仓库配置");
  }

  public void requireAssistantUse(AuthUser user) {
    requirePermission(user, PermissionCodes.ASSISTANT_USE, "使用门店经营助手");
  }

  public void requireEmployeeAssistantUse(AuthUser user) {
    requirePermission(user, PermissionCodes.EMPLOYEE_ASSISTANT_USE, "使用员工服务助手");
  }

  /** Knowledge approval is BOSS-only even if another role receives a mistaken ALLOW override. */
  public void requireEmployeeAssistantKnowledgeManage(AuthUser user) {
    requireBoss(user, "管理员工助手知识库");
  }

  /** Only supervisors may process employee-assistant handoffs. */
  public void requireEmployeeAssistantHandoffManage(AuthUser user) {
    requirePermission(user, PermissionCodes.EMPLOYEE_ASSISTANT_HANDOFF_MANAGE, "处理员工助手人工事项");
    if (hasAnyRole(user, "SUPERVISOR")) {
      return;
    }
    deny(user, "处理员工助手人工事项", "API", PermissionCodes.EMPLOYEE_ASSISTANT_HANDOFF_MANAGE, null,
        "人工事项处理仅限督导角色");
  }

  /** Search is available only when the role template explicitly includes knowledge-base access. */
  public void requireKnowledgeBaseSearch(AuthUser user) {
    requirePermission(user, PermissionCodes.KNOWLEDGE_BASE_SEARCH, "检索知识库");
  }

  /** Uploading and publishing source documents is restricted to the boss and supervisors. */
  public void requireKnowledgeBaseManage(AuthUser user) {
    requirePermission(user, PermissionCodes.KNOWLEDGE_BASE_MANAGE, "管理知识库");
    if (isBoss(user) || hasAnyRole(user, "SUPERVISOR")) {
      return;
    }
    deny(user, "管理知识库", "API", PermissionCodes.KNOWLEDGE_BASE_MANAGE, null,
        "知识库管理仅限老板或督导");
  }

  /** Tenant-wide or role-wide documents may only be published by the boss. */
  public void requireKnowledgeBaseTenantWideManage(AuthUser user) {
    requireKnowledgeBaseManage(user);
    if (isBoss(user)) {
      return;
    }
    deny(user, "发布全企业知识库资料", "KNOWLEDGE_BASE", PermissionCodes.KNOWLEDGE_BASE_MANAGE, null,
        "督导只能管理本人数据范围内的门店资料");
  }

  /** Records a single auditable refusal when a document fails row-level visibility checks. */
  public void requireKnowledgeBaseDocumentRead(AuthUser user, boolean allowed, long documentId) {
    requireKnowledgeBaseSearch(user);
    if (allowed) {
      return;
    }
    deny(user, "查看知识库资料", "knowledge_base_document", Long.toString(documentId), null,
        "资料不在当前账号的角色或门店范围内");
  }

  public void requireMigrationManage(AuthUser user) {
    requirePermission(user, PermissionCodes.SYSTEM_MIGRATION_MANAGE, "管理历史数据迁移");
  }

  public void requireTodoRead(AuthUser user) {
    requirePermission(user, PermissionCodes.TODO_READ, "查看业务待办");
  }

  public void requireTodoTransition(AuthUser user) {
    requirePermission(user, PermissionCodes.TODO_TRANSITION, "处理业务待办");
  }

  public void requireOwnExamAssignment(AuthUser user, long assignedUserId, long assignmentId) {
    requireExamRead(user);
    if (user != null && user.id() == assignedUserId) {
      return;
    }
    deny(
        user,
        "参加他人的考试",
        "training_exam_assignment",
        Long.toString(assignmentId),
        user == null ? null : user.storeId(),
        "考试任务不属于当前账号"
    );
  }

  public void requireExamCampaignScope(AuthUser user, boolean allowed, long campaignId) {
    requireExamRead(user);
    if (allowed) {
      return;
    }
    deny(
        user,
        "查看超出数据范围的考试",
        "training_exam_campaign",
        Long.toString(campaignId),
        user == null ? null : user.storeId(),
        "考试不属于当前账号的公司、门店或本人范围"
    );
  }

  public void requireStoreAccess(AuthUser user, String storeId, String action) {
    requireStoreAccess(user, DataScopeDomains.STORE, storeId, action);
  }

  public void requireStoreAccess(AuthUser user, String domainCode, String storeId, String action) {
    String normalizedStoreId = storeId == null ? "" : storeId.trim();
    if (normalizedStoreId.isBlank()) {
      deny(user, action, "STORE", null, null, "未指定门店");
    }
    if (hasAllDataScope(user, domainCode)) {
      return;
    }
    if (allowedStoreIds(user, domainCode).contains(normalizedStoreId)) {
      return;
    }
    deny(user, action, "STORE", normalizedStoreId, normalizedStoreId, "门店不在当前账号的数据范围内");
  }

  public Set<String> allowedStoreIds(AuthUser user) {
    return allowedStoreIds(user, DataScopeDomains.STORE);
  }

  public Set<String> allowedStoreIds(AuthUser user, String domainCode) {
    if (dataScopeService != null) {
      return dataScopeService.allowedStoreIds(user, domainCode);
    }
    LinkedHashSet<String> values = new LinkedHashSet<>();
    if (hasAllStoreScope(user)) {
      values.add("all");
      return values;
    }
    if (user == null) {
      return values;
    }
    if (user.storeId() != null && !user.storeId().isBlank()) {
      values.add(user.storeId().trim());
    }
    values.addAll(authRepository.assignedStoreScope(user.tenantId(), user.id()));
    return values;
  }

  public boolean canAccessStore(AuthUser user, String storeId) {
    return canAccessStore(user, DataScopeDomains.STORE, storeId);
  }

  public Set<String> allowedWarehouseIds(AuthUser user) {
    if (hasAllStoreScope(user)) {
      return Set.of("all");
    }
    if (dataScopeService != null) {
      return dataScopeService.allowedWarehouseIds(user);
    }
    return Set.of();
  }

  public boolean canAccessWarehouse(AuthUser user, String warehouseId) {
    if (hasAllStoreScope(user)) {
      return true;
    }
    return dataScopeService != null && dataScopeService.canAccessWarehouse(user, warehouseId);
  }

  public boolean canAccessStore(AuthUser user, String domainCode, String storeId) {
    if (dataScopeService != null) {
      return dataScopeService.canAccessStore(user, domainCode, storeId);
    }
    Set<String> allowedStoreIds = allowedStoreIds(user, domainCode);
    return allowedStoreIds.contains("all")
        || (storeId != null && allowedStoreIds.contains(storeId.trim()));
  }

  public boolean hasAllDataScope(AuthUser user, String domainCode) {
    if (hasAllStoreScope(user)) {
      return true;
    }
    if (dataScopeService != null) {
      return dataScopeService.hasAllDataScope(user, domainCode);
    }
    return false;
  }

  public DataScope dataScope(AuthUser user, String domainCode) {
    if (hasAllStoreScope(user)) {
      return DataScope.all();
    }
    if (dataScopeService != null) {
      return dataScopeService.scope(user, domainCode);
    }
    String role = user == null ? "" : canonicalRole(user.role());
    if ("EMPLOYEE".equals(role) && DataScopeDomains.EXAM.equals(domainCode)) {
      return new DataScope(DataScopeModes.SELF, java.util.List.of());
    }
    if ("WAREHOUSE".equals(role) && DataScopeDomains.WAREHOUSE.equals(domainCode)) {
      return new DataScope(DataScopeModes.CENTRAL_WAREHOUSE, java.util.List.of());
    }
    if ("STORE_MANAGER".equals(role)
        && user.storeId() != null
        && !user.storeId().isBlank()) {
      return new DataScope(DataScopeModes.OWN_STORE, java.util.List.of(user.storeId().trim()));
    }
    Set<String> allowedStoreIds = allowedStoreIds(user, domainCode);
    if (allowedStoreIds.contains("all")) {
      return DataScope.all();
    }
    return allowedStoreIds.isEmpty()
        ? DataScope.none()
        : new DataScope(DataScopeModes.STORE_LIST, allowedStoreIds.stream().sorted().toList());
  }

  public boolean hasPermission(AuthUser user, String permissionCode) {
    if (isBoss(user)) {
      return true;
    }
    if (authorizationService != null) {
      return authorizationService.hasPermission(user, permissionCode);
    }
    return user != null && AuthorizationService.legacyTemplatePermissions(user.role()).contains(permissionCode);
  }

  public void requirePermission(AuthUser user, String permissionCode, String action) {
    if (hasPermission(user, permissionCode)) {
      return;
    }
    deny(user, action, "API", permissionCode, null, "账号不具备权限 " + permissionCode);
  }

  public void requirePermission(AuthUser user, String permissionCode) {
    requirePermission(user, permissionCode, "访问受保护业务");
  }

  public void requireBoss(AuthUser user, String action) {
    if (isBoss(user)) {
      return;
    }
    deny(user, action, "API", PermissionCodes.SYSTEM_USER_MANAGE, null, "账号权限管理仅限老板");
  }

  /**
   * Returns true when the user's role always grants access to every store in the tenant.
   * Unlike a static store-ids list, this scope is computed dynamically and automatically
   * includes stores created after the account was configured.
   */
  public static boolean hasAllStoreScope(AuthUser user) {
    return user != null && hasAllStoreScope(user.role());
  }

  /** Role-only variant for checks where an AuthUser instance is not yet available. */
  public static boolean hasAllStoreScope(String role) {
    return GLOBAL_STORE_ROLES.contains(canonicalRole(role));
  }

  private boolean isGlobalStoreRole(AuthUser user) {
    return hasAllStoreScope(user);
  }

  public static boolean isBoss(AuthUser user) {
    return user != null && isBossRole(user.role());
  }

  public static boolean isBossRole(String role) {
    return BOSS_ROLE.equals(canonicalRole(role));
  }

  public static boolean hasAnyRole(AuthUser user, String... allowedRoles) {
    return hasAnyRole(user, allowedRoles == null ? Set.of() : Set.of(allowedRoles));
  }

  public static boolean hasAnyRole(AuthUser user, Set<String> allowedRoles) {
    if (user == null) {
      return false;
    }
    String role = canonicalRole(user.role());
    if (BOSS_ROLE.equals(role)) {
      return true;
    }
    return allowedRoles != null && allowedRoles.stream()
        .map(AccessControlService::canonicalRole)
        .anyMatch(role::equals);
  }

  public static String canonicalRole(String role) {
    String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      // Legacy rows and pre-migration sessions remain readable, but these aliases are never
      // accepted as current account roles by UserManagementService.
      case "ADMIN", "OWNER" -> BOSS_ROLE;
      case "OPS", "OPERATIONS" -> "SUPERVISOR";
      default -> normalized;
    };
  }

  private void deny(
      AuthUser user,
      String action,
      String targetType,
      String targetId,
      String storeId,
      String reason
  ) {
    if (user != null) {
      try {
        auditRepository.writePermissionDenied(user, action, targetType, targetId, storeId, reason);
      } catch (RuntimeException ex) {
        log.warn("Failed to write permission denial audit log for user {}: {}", user.id(), ex.getMessage());
      }
    }
    throw new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN);
  }

  private void deny(
      AuthUser user,
      String action,
      String targetType,
      String targetId,
      String storeId,
      String month,
      String reason
  ) {
    if (user != null) {
      try {
        auditRepository.writePermissionDenied(user, action, targetType, targetId, storeId, month, reason);
      } catch (RuntimeException ex) {
        log.warn("Failed to write permission denial audit log for user {}: {}", user.id(), ex.getMessage());
      }
    }
    throw new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN);
  }
}
