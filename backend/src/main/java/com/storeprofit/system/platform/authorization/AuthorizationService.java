package com.storeprofit.system.platform.authorization;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {
  private static final Set<String> EMPLOYEE_PERMISSION_CEILING = Set.of(
      PermissionCodes.EXAM_LEARN,
      PermissionCodes.EMPLOYEE_ASSISTANT_USE
  );
  private static final Set<String> SUPERVISOR_PERMISSION_DENYLIST = Set.of(
      PermissionCodes.OPERATIONS_DASHBOARD_READ,
      PermissionCodes.PLATFORM_READ,
      PermissionCodes.PLATFORM_MANAGE,
      PermissionCodes.INVENTORY_MANAGE,
      PermissionCodes.INVENTORY_REVIEW,
      PermissionCodes.EXAM_MANAGE,
      PermissionCodes.EXAM_REPORT
  );

  private final AuthorizationRepository repository;

  public AuthorizationService(AuthorizationRepository repository) {
    this.repository = repository;
  }

  public List<PermissionCatalogEntry> catalog() {
    return repository.catalog();
  }

  public Set<String> roleTemplatePermissions(long tenantId, String role) {
    String canonicalRole = AccessControlService.canonicalRole(role);
    if (AccessControlService.isBossRole(canonicalRole)) {
      return bossPermissions();
    }
    LinkedHashSet<String> permissions = new LinkedHashSet<>(
        repository.roleTemplatePermissions(tenantId, canonicalRole));
    // V37 previously granted store.manage to store managers and operations users. Keep that
    // historical migration immutable, but never surface the obsolete grant to a non-BOSS user.
    permissions.remove(PermissionCodes.STORE_MANAGE);
    // Importing a workbook can overwrite an accounting period. Its catalog entry is deliberately
    // only attached to FINANCE; preserve that boundary even if a legacy role template was edited.
    if (!"FINANCE".equals(canonicalRole)) {
      permissions.remove(PermissionCodes.FINANCE_PROFIT_IMPORT);
    }
    if ("SUPERVISOR".equals(canonicalRole)) {
      permissions.removeAll(SUPERVISOR_PERMISSION_DENYLIST);
    }
    return Set.copyOf(permissions);
  }

  public List<UserPermissionOverride> userOverrides(long tenantId, long userId) {
    return repository.userOverrides(tenantId, userId);
  }

  public void replaceUserOverrides(
      long tenantId,
      long userId,
      List<UserPermissionOverride> overrides,
      Long actorId
  ) {
    List<UserPermissionOverride> normalized = normalizeOverrides(overrides);
    repository.replaceUserOverrides(tenantId, userId, normalized, actorId);
  }

  public long incrementPermissionVersionAndDeleteTokens(long tenantId, long userId) {
    return repository.incrementPermissionVersionAndDeleteTokens(tenantId, userId);
  }

  public Set<String> effectivePermissions(AuthUser user) {
    if (user == null) {
      return Set.of();
    }
    if (AccessControlService.isBoss(user)) {
      return bossPermissions();
    }

    String role = AccessControlService.canonicalRole(user.role());
    Set<String> enabledCodes = repository.enabledPermissionCodes();
    LinkedHashSet<String> effective = new LinkedHashSet<>(
        roleTemplatePermissions(user.tenantId(), role));
    LinkedHashSet<String> denied = new LinkedHashSet<>();
    for (UserPermissionOverride override : repository.userOverrides(user.tenantId(), user.id())) {
      if (!enabledCodes.contains(override.permissionCode())) {
        continue;
      }
      if (override.effect() == PermissionEffect.DENY) {
        denied.add(override.permissionCode());
      } else {
        effective.add(override.permissionCode());
      }
    }
    effective.removeAll(denied);
    if ("EMPLOYEE".equals(role)) {
      effective.retainAll(EMPLOYEE_PERMISSION_CEILING);
    }
    if ("SUPERVISOR".equals(role)) {
      effective.removeAll(SUPERVISOR_PERMISSION_DENYLIST);
    }
    // Personal ALLOW overrides are evaluated above, then this hard BOSS-only boundary is
    // applied. DENY still wins for every ordinary permission, and cannot be bypassed here.
    effective.remove(PermissionCodes.STORE_MANAGE);
    // The import privilege is role-bound in addition to being catalog-bound. A hand-edited
    // personal ALLOW for a store manager, operations user, warehouse user, or employee must not
    // turn the data-entry screen into a bulk-import backdoor.
    if (!"FINANCE".equals(role)) {
      effective.remove(PermissionCodes.FINANCE_PROFIT_IMPORT);
    }
    return Set.copyOf(effective);
  }

  public boolean hasPermission(AuthUser user, String permissionCode) {
    if (AccessControlService.isBoss(user)) {
      return true;
    }
    String normalized = normalizePermissionCode(permissionCode);
    return !normalized.isBlank() && effectivePermissions(user).contains(normalized);
  }

  /**
   * Compatibility defaults used only by legacy constructors in isolated unit tests. Production
   * requests always read role_permission and personal overrides from MySQL.
   */
  public static Set<String> legacyTemplatePermissions(String rawRole) {
    String role = AccessControlService.canonicalRole(rawRole);
    return switch (role) {
      case "FINANCE" -> Set.of(
          PermissionCodes.STORE_READ,
          PermissionCodes.EMPLOYEE_READ,
          PermissionCodes.FINANCE_PROFIT_READ,
          PermissionCodes.FINANCE_PROFIT_WRITE,
          PermissionCodes.FINANCE_PROFIT_IMPORT,
          PermissionCodes.FINANCE_EXPORT,
          PermissionCodes.DAILY_LOSS_READ,
          PermissionCodes.DAILY_LOSS_REVIEW,
          PermissionCodes.EXPENSE_CREATE,
          PermissionCodes.EXPENSE_READ,
          PermissionCodes.EXPENSE_REVIEW,
          PermissionCodes.SALARY_READ,
          PermissionCodes.SALARY_EDIT,
          PermissionCodes.SALARY_REVIEW,
          PermissionCodes.SALARY_PAY,
          PermissionCodes.INVENTORY_READ,
          PermissionCodes.ATTACHMENT_READ,
          PermissionCodes.ATTACHMENT_WRITE,
          PermissionCodes.TODO_READ,
          PermissionCodes.TODO_TRANSITION,
          PermissionCodes.ASSISTANT_USE,
          PermissionCodes.EMPLOYEE_ASSISTANT_USE
      );
      case "WAREHOUSE" -> Set.of(
          PermissionCodes.STORE_READ,
          PermissionCodes.WAREHOUSE_READ,
          PermissionCodes.WAREHOUSE_PURCHASE,
          PermissionCodes.WAREHOUSE_TRANSFER_REQUEST,
          PermissionCodes.WAREHOUSE_TRANSFER_APPROVE,
          PermissionCodes.WAREHOUSE_TRANSFER_SHIP,
          PermissionCodes.WAREHOUSE_TRANSFER_RECEIVE,
          PermissionCodes.WAREHOUSE_REQUISITION_PROCESS,
          PermissionCodes.WAREHOUSE_CENTRAL_READ,
          PermissionCodes.WAREHOUSE_CENTRAL_MANAGE,
          PermissionCodes.WAREHOUSE_STORE_READ,
          PermissionCodes.WAREHOUSE_REQUISITION_REVIEW,
          PermissionCodes.DAILY_LOSS_READ,
          PermissionCodes.DAILY_LOSS_REVIEW,
          PermissionCodes.EXAM_LEARN,
          PermissionCodes.ATTACHMENT_READ,
          PermissionCodes.ATTACHMENT_WRITE,
          PermissionCodes.TODO_READ,
          PermissionCodes.TODO_TRANSITION,
          PermissionCodes.ASSISTANT_USE,
          PermissionCodes.EMPLOYEE_ASSISTANT_USE
      );
      case "STORE_MANAGER" -> Set.of(
          PermissionCodes.STORE_READ,
          PermissionCodes.EMPLOYEE_READ,
          PermissionCodes.EMPLOYEE_MANAGE,
          PermissionCodes.FINANCE_PROFIT_READ,
          PermissionCodes.FINANCE_PROFIT_WRITE,
          PermissionCodes.DAILY_LOSS_READ,
          PermissionCodes.DAILY_LOSS_CREATE,
          PermissionCodes.EXPENSE_CREATE,
          PermissionCodes.EXPENSE_READ,
          PermissionCodes.SALARY_READ,
          PermissionCodes.WAREHOUSE_STORE_READ,
          PermissionCodes.WAREHOUSE_REQUISITION_CREATE,
          PermissionCodes.WAREHOUSE_REQUISITION_RECEIVE,
          PermissionCodes.INVENTORY_READ,
          PermissionCodes.INVENTORY_MANAGE,
          PermissionCodes.INSPECTION_READ,
          PermissionCodes.EXAM_LEARN,
          PermissionCodes.EXAM_REPORT,
          PermissionCodes.ATTACHMENT_READ,
          PermissionCodes.ATTACHMENT_WRITE,
          PermissionCodes.TODO_READ,
          PermissionCodes.TODO_TRANSITION,
          PermissionCodes.ASSISTANT_USE,
          PermissionCodes.EMPLOYEE_ASSISTANT_USE
      );
      case "OPERATIONS" -> Set.of(
          PermissionCodes.OPERATIONS_DASHBOARD_READ,
          PermissionCodes.STORE_READ,
          PermissionCodes.EMPLOYEE_READ,
          PermissionCodes.WAREHOUSE_STORE_READ,
          PermissionCodes.INVENTORY_READ,
          PermissionCodes.INVENTORY_MANAGE,
          PermissionCodes.INVENTORY_REVIEW,
          PermissionCodes.DAILY_LOSS_READ,
          PermissionCodes.DAILY_LOSS_REVIEW,
          PermissionCodes.INSPECTION_READ,
          PermissionCodes.INSPECTION_MANAGE,
          PermissionCodes.EXAM_LEARN,
          PermissionCodes.EXAM_MANAGE,
          PermissionCodes.EXAM_REPORT,
          PermissionCodes.PLATFORM_READ,
          PermissionCodes.PLATFORM_MANAGE,
          PermissionCodes.ATTACHMENT_READ,
          PermissionCodes.ATTACHMENT_WRITE,
          PermissionCodes.TODO_READ,
          PermissionCodes.TODO_TRANSITION,
          PermissionCodes.ASSISTANT_USE,
          PermissionCodes.EMPLOYEE_ASSISTANT_USE,
          PermissionCodes.EMPLOYEE_ASSISTANT_HANDOFF_MANAGE
      );
      case "SUPERVISOR" -> Set.of(
          PermissionCodes.STORE_READ,
          PermissionCodes.INSPECTION_READ,
          PermissionCodes.INSPECTION_MANAGE,
          PermissionCodes.ATTACHMENT_READ,
          PermissionCodes.ATTACHMENT_WRITE,
          PermissionCodes.TODO_READ,
          PermissionCodes.TODO_TRANSITION,
          PermissionCodes.ASSISTANT_USE,
          PermissionCodes.EMPLOYEE_ASSISTANT_USE,
          PermissionCodes.EMPLOYEE_ASSISTANT_HANDOFF_MANAGE
      );
      case "EMPLOYEE" -> EMPLOYEE_PERMISSION_CEILING;
      default -> Set.of();
    };
  }

  private List<UserPermissionOverride> normalizeOverrides(List<UserPermissionOverride> overrides) {
    if (overrides == null || overrides.isEmpty()) {
      return List.of();
    }
    Set<String> catalogCodes = repository.enabledPermissionCodes();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    List<UserPermissionOverride> normalized = new ArrayList<>();
    for (UserPermissionOverride override : overrides) {
      if (override == null) {
        continue;
      }
      String code = normalizePermissionCode(override.permissionCode());
      if (!catalogCodes.contains(code)) {
        throw new BusinessException("PERMISSION_INVALID", "权限代码不存在或已停用", HttpStatus.BAD_REQUEST);
      }
      if (!seen.add(code)) {
        throw new BusinessException("PERMISSION_DUPLICATE", "同一权限不能重复配置", HttpStatus.BAD_REQUEST);
      }
      normalized.add(new UserPermissionOverride(code, override.effect()));
    }
    return List.copyOf(normalized);
  }

  private Set<String> bossPermissions() {
    LinkedHashSet<String> permissions = new LinkedHashSet<>(PermissionCodes.ALL);
    permissions.addAll(repository.enabledPermissionCodes());
    return Set.copyOf(permissions);
  }

  private String normalizePermissionCode(String permissionCode) {
    return permissionCode == null ? "" : permissionCode.trim();
  }
}
