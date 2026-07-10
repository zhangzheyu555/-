package com.storeprofit.system.platform.auth;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Central authorization boundary for business APIs. Controllers obtain the authenticated user
 * here and services use the same policy for role and store-scope checks.
 */
@Service
public class AccessControlService {
  private static final Logger log = LoggerFactory.getLogger(AccessControlService.class);
  private static final Set<String> GLOBAL_STORE_ROLES = Set.of("ADMIN", "BOSS", "FINANCE", "OWNER");
  private static final Set<String> FINANCE_READ_ROLES = Set.of("ADMIN", "BOSS", "FINANCE", "STORE_MANAGER", "OWNER");
  private static final Set<String> FINANCE_WRITE_ROLES = Set.of("ADMIN", "BOSS", "FINANCE", "STORE_MANAGER", "OWNER");
  private static final Set<String> EXPENSE_READ_ROLES = Set.of("ADMIN", "BOSS", "FINANCE", "STORE_MANAGER", "OWNER");
  private static final Set<String> EXPENSE_WRITE_ROLES = Set.of("ADMIN", "BOSS", "FINANCE", "STORE_MANAGER", "OWNER");
  private static final Set<String> EXPENSE_REVIEW_ROLES = Set.of("ADMIN", "BOSS", "FINANCE", "OWNER");
  private static final Set<String> SALARY_READ_ROLES = Set.of("ADMIN", "BOSS", "FINANCE", "STORE_MANAGER", "OWNER");
  private static final Set<String> SALARY_EDIT_ROLES = Set.of("ADMIN", "BOSS", "FINANCE", "OWNER");
  private static final Set<String> SALARY_REVIEW_ROLES = Set.of("ADMIN", "BOSS", "OWNER");
  private static final Set<String> ATTACHMENT_ROLES = Set.of(
      "ADMIN", "BOSS", "FINANCE", "STORE_MANAGER", "SUPERVISOR", "WAREHOUSE", "OWNER");
  private static final Set<String> INSPECTION_READ_ROLES = Set.of(
      "ADMIN", "BOSS", "SUPERVISOR", "STORE_MANAGER", "OWNER");
  private static final Set<String> INSPECTION_MANAGE_ROLES = Set.of("ADMIN", "BOSS", "SUPERVISOR", "OWNER");
  private static final Set<String> PLATFORM_ROLES = Set.of("ADMIN", "BOSS", "OPERATIONS", "OWNER");
  private static final Set<String> EXAM_READ_ROLES = Set.of(
      "ADMIN", "BOSS", "OWNER", "OPERATIONS", "OPS", "STORE_MANAGER", "EMPLOYEE");
  private static final Set<String> EXAM_MANAGE_ROLES = Set.of("ADMIN", "OPERATIONS", "OPS");
  private static final Set<String> EXAM_COMPANY_READ_ROLES = Set.of(
      "ADMIN", "BOSS", "OWNER", "OPERATIONS", "OPS");
  private static final Set<String> BOSS_EXAM_READ_ROLES = Set.of("ADMIN", "BOSS", "OWNER");

  private final AuthService authService;
  private final AuthRepository authRepository;
  private final AuditRepository auditRepository;

  public AccessControlService(
      AuthService authService,
      AuthRepository authRepository,
      AuditRepository auditRepository
  ) {
    this.authService = authService;
    this.authRepository = authRepository;
    this.auditRepository = auditRepository;
  }

  public AuthUser requireUser(String authorization) {
    return authService.requireUser(authorization);
  }

  public void requireFinanceRead(AuthUser user) {
    requireRole(user, "查看经营数据", FINANCE_READ_ROLES);
  }

  public void requireFinanceWrite(AuthUser user) {
    requireRole(user, "录入经营数据", FINANCE_WRITE_ROLES);
  }

  public void requireFinanceDelete(AuthUser user) {
    requireRole(user, "删除经营数据", Set.of("ADMIN", "BOSS", "OWNER"));
  }

  public void requireExpenseRead(AuthUser user) {
    requireRole(user, "查看报销数据", EXPENSE_READ_ROLES);
  }

  public void requireExpenseWrite(AuthUser user) {
    requireRole(user, "录入报销数据", EXPENSE_WRITE_ROLES);
  }

  public void requireExpenseReview(AuthUser user) {
    requireRole(user, "审核报销数据", EXPENSE_REVIEW_ROLES);
  }

  public void requireSalaryRead(AuthUser user) {
    requireRole(user, "查看工资数据", SALARY_READ_ROLES);
  }

  public void requireSalaryEdit(AuthUser user) {
    requireRole(user, "录入工资数据", SALARY_EDIT_ROLES);
  }

  public void requireSalaryReview(AuthUser user) {
    requireRole(user, "审核工资数据", SALARY_REVIEW_ROLES);
  }

  public void requireAuditRead(AuthUser user) {
    requireRole(user, "查看操作日志", Set.of("ADMIN", "BOSS", "OWNER"));
  }

  public void requireAuditWrite(AuthUser user) {
    requireRole(user, "补写操作日志", Set.of("ADMIN", "BOSS", "OWNER"));
  }

  public void requireUserManagementRead(AuthUser user) {
    requireRole(user, "查看账号权限", Set.of("ADMIN", "BOSS", "OWNER"));
  }

  public void requireUserManagementWrite(AuthUser user) {
    requireRole(user, "维护账号权限", Set.of("ADMIN"));
  }

  public void requireDataExport(AuthUser user) {
    requireRole(user, "导出经营数据", Set.of("ADMIN", "BOSS", "FINANCE", "OWNER"));
  }

  public void requireLegacyStorageAccess(AuthUser user) {
    requireRole(user, "访问历史兼容数据", Set.of("ADMIN", "BOSS", "OWNER"));
  }

  public void requireAttachmentWrite(AuthUser user, String storeId) {
    requireRole(user, "上传附件", ATTACHMENT_ROLES);
    requireStoreAccess(user, storeId, "上传附件");
  }

  public void requireAttachmentRead(AuthUser user, String storeId, Long uploadedBy) {
    requireRole(user, "查看附件", ATTACHMENT_ROLES);
    if (storeId == null || storeId.isBlank()) {
      if (isGlobalStoreRole(user) || (uploadedBy != null && uploadedBy == user.id())) {
        return;
      }
      deny(user, "查看历史附件", "ATTACHMENT", null, null, "历史附件缺少门店归属，仅上传人或管理角色可访问");
    }
    requireStoreAccess(user, storeId, "查看附件");
  }

  public void requireInspectionRead(AuthUser user) {
    requireRole(user, "查看巡检数据", INSPECTION_READ_ROLES);
  }

  public void requireInspectionManage(AuthUser user) {
    requireRole(user, "处理巡检数据", INSPECTION_MANAGE_ROLES);
  }

  public void requirePlatformAccess(AuthUser user) {
    requireRole(user, "查看平台数据", PLATFORM_ROLES);
  }

  public void requireExamRead(AuthUser user) {
    requireRole(user, "查看培训考试", EXAM_READ_ROLES);
  }

  public void requireExamManage(AuthUser user) {
    requireRole(user, "管理培训考试", EXAM_MANAGE_ROLES);
  }

  public void requireExamCompanyRead(AuthUser user) {
    requireRole(user, "查看全部门店考试数据", EXAM_COMPANY_READ_ROLES);
  }

  public void requireBossExamRead(AuthUser user) {
    requireRole(user, "查看老板考试概览", BOSS_EXAM_READ_ROLES);
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
    String normalizedStoreId = storeId == null ? "" : storeId.trim();
    if (normalizedStoreId.isBlank()) {
      deny(user, action, "STORE", null, null, "未指定门店");
    }
    if (isGlobalStoreRole(user)) {
      return;
    }
    if (allowedStoreIds(user).contains(normalizedStoreId)) {
      return;
    }
    deny(user, action, "STORE", normalizedStoreId, normalizedStoreId, "门店不在当前账号的数据范围内");
  }

  public Set<String> allowedStoreIds(AuthUser user) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    if (isGlobalStoreRole(user)) {
      values.add("all");
      return values;
    }
    if (user.storeId() != null && !user.storeId().isBlank()) {
      values.add(user.storeId().trim());
    }
    values.addAll(authRepository.assignedStoreScope(user.tenantId(), user.id()));
    return values;
  }

  public boolean canAccessStore(AuthUser user, String storeId) {
    if (isGlobalStoreRole(user)) {
      return true;
    }
    return storeId != null && allowedStoreIds(user).contains(storeId.trim());
  }

  private void requireRole(AuthUser user, String action, Set<String> allowedRoles) {
    if (user != null && allowedRoles.contains(normalizeRole(user.role()))) {
      return;
    }
    deny(user, action, "API", null, null, "角色不具备该业务权限");
  }

  private boolean isGlobalStoreRole(AuthUser user) {
    return user != null && GLOBAL_STORE_ROLES.contains(normalizeRole(user.role()));
  }

  private String normalizeRole(String role) {
    return role == null ? "" : role.trim().toUpperCase();
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
