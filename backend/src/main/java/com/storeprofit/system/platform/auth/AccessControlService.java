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
    requirePermission(user, PermissionCodes.FINANCE_PROFIT_WRITE, "录入经营数据");
    if (!isBoss(user) && !hasAnyRole(user, "FINANCE")) {
      deny(
          user,
          "录入经营数据",
          "API",
          PermissionCodes.FINANCE_PROFIT_WRITE,
          null,
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
    requirePermission(user, PermissionCodes.FINANCE_PROFIT_IMPORT, "导入经营数据");
    if (!hasAnyRole(user, "FINANCE")) {
      deny(
          user,
          "导入经营数据",
          "API",
          PermissionCodes.FINANCE_PROFIT_IMPORT,
          null,
          "月度经营数据导入仅限财务或老板"
      );
    }
    // The eventual commit delegates to FinanceService.save(). Check the write permission here,
    // before a file is accepted or a preview job is allocated, so a finance user with an
    // explicit write DENY receives one clear, auditable refusal at the import boundary.
    requireFinanceWrite(user);
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
  }

  public void requireDailyLossCreate(AuthUser user) {
    requirePermission(user, PermissionCodes.DAILY_LOSS_CREATE, "提交每日报损");
  }

  public void requireDailyLossReview(AuthUser user) {
    requirePermission(user, PermissionCodes.DAILY_LOSS_REVIEW, "复核每日报损");
  }

  public void requireExpenseRead(AuthUser user) {
    requirePermission(user, PermissionCodes.EXPENSE_READ, "查看报销数据");
  }

  public void requireExpenseWrite(AuthUser user) {
    requirePermission(user, PermissionCodes.EXPENSE_CREATE, "录入报销数据");
  }

  public void requireExpenseReview(AuthUser user) {
    requirePermission(user, PermissionCodes.EXPENSE_REVIEW, "审核报销数据");
  }

  public void requireSalaryRead(AuthUser user) {
    requirePermission(user, PermissionCodes.SALARY_READ, "查看工资数据");
  }

  public void requireSalaryEdit(AuthUser user) {
    requirePermission(user, PermissionCodes.SALARY_EDIT, "录入工资数据");
  }

  public void requireSalaryReview(AuthUser user) {
    requirePermission(user, PermissionCodes.SALARY_REVIEW, "审核工资数据");
  }

  public void requireSalaryPay(AuthUser user) {
    requirePermission(user, PermissionCodes.SALARY_PAY, "发放工资");
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
    requirePermission(user, PermissionCodes.FINANCE_EXPORT, "导出经营数据");
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

  public void requirePlatformAccess(AuthUser user) {
    requirePlatformRead(user);
  }

  public void requirePlatformRead(AuthUser user) {
    requirePermission(user, PermissionCodes.PLATFORM_READ, "查看平台数据");
  }

  public void requirePlatformManage(AuthUser user) {
    requirePermission(user, PermissionCodes.PLATFORM_MANAGE, "维护平台配置");
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
    requirePermission(user, PermissionCodes.OPERATIONS_DASHBOARD_READ, "查看运营工作台");
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

  /** Only authorized operations/supervisor users (canonical OPERATIONS) may process handoffs. */
  public void requireEmployeeAssistantHandoffManage(AuthUser user) {
    requirePermission(user, PermissionCodes.EMPLOYEE_ASSISTANT_HANDOFF_MANAGE, "处理员工助手人工事项");
    if (hasAnyRole(user, "OPERATIONS")) {
      return;
    }
    deny(user, "处理员工助手人工事项", "API", PermissionCodes.EMPLOYEE_ASSISTANT_HANDOFF_MANAGE, null,
        "人工事项处理仅限运营或督导角色");
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
    if (isBoss(user)) {
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
    if (isBoss(user)) {
      return Set.of("all");
    }
    if (dataScopeService != null) {
      return dataScopeService.allowedWarehouseIds(user);
    }
    return Set.of();
  }

  public boolean canAccessWarehouse(AuthUser user, String warehouseId) {
    if (isBoss(user)) {
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
    if (isBoss(user)) {
      return true;
    }
    if (dataScopeService != null) {
      return dataScopeService.hasAllDataScope(user, domainCode);
    }
    return false;
  }

  public DataScope dataScope(AuthUser user, String domainCode) {
    if (isBoss(user)) {
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

  private boolean isGlobalStoreRole(AuthUser user) {
    return isBoss(user);
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
      case "ADMIN", "OWNER" -> BOSS_ROLE;
      case "OPS", "SUPERVISOR" -> "OPERATIONS";
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
}
