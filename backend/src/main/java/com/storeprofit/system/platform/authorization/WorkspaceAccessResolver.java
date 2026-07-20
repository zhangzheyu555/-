package com.storeprofit.system.platform.authorization;

import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceAccessResolver {
  private static final String NO_PERMISSION = "/no-permission";
  private static final List<WorkspaceRule> RULES = List.of(
      new WorkspaceRule("/boss", List.of(PermissionCodes.SYSTEM_DASHBOARD_READ), null),
      new WorkspaceRule("/finance", List.of(PermissionCodes.FINANCE_PROFIT_READ), DataScopeDomains.FINANCE),
      new WorkspaceRule(
          "/warehouse",
          List.of(PermissionCodes.WAREHOUSE_READ, PermissionCodes.WAREHOUSE_CENTRAL_READ),
          DataScopeDomains.WAREHOUSE),
      new WorkspaceRule("/store", List.of(PermissionCodes.STORE_READ), DataScopeDomains.STORE),
      new WorkspaceRule("/operations/inspection", List.of(PermissionCodes.INSPECTION_READ), DataScopeDomains.INSPECTION),
      new WorkspaceRule("/operations", List.of(PermissionCodes.OPERATIONS_DASHBOARD_READ), DataScopeDomains.STORE),
      new WorkspaceRule("/learn/exams", List.of(PermissionCodes.EXAM_LEARN), DataScopeDomains.EXAM)
  );
  private static final Map<String, String> RECOMMENDED_BY_ROLE = Map.of(
      "BOSS", "/boss",
      "FINANCE", "/finance",
      "WAREHOUSE", "/warehouse",
      "STORE_MANAGER", "/store",
      "SUPERVISOR", "/operations",
      "EMPLOYEE", "/employee"
  );

  public WorkspaceAccessProfile resolve(
      AuthUser user,
      Set<String> effectivePermissions,
      Map<String, DataScope> dataScopes,
      List<String> assignedStoreIds
  ) {
    if (user == null || !user.enabled()) {
      return new WorkspaceAccessProfile(
          List.of(), NO_PERMISSION, WorkspaceAccessProfile.DISABLED, "账号已停用");
    }
    Set<String> permissions = effectivePermissions == null ? Set.of() : effectivePermissions;
    Map<String, DataScope> scopes = dataScopes == null ? Map.of() : dataScopes;
    String role = AccessControlService.canonicalRole(user.role());

    if ("STORE_MANAGER".equals(role)) {
      return resolveStoreManager(user, permissions, scopes, assignedStoreIds);
    }
    if ("EMPLOYEE".equals(role)) {
      return resolveEmployee(permissions, scopes);
    }

    LinkedHashSet<String> available = new LinkedHashSet<>();
    String recommended = recommendedWorkspace(role);
    WorkspaceRule recommendedRule = rule(recommended);
    if (recommendedRule != null && canAccess(recommendedRule, permissions, scopes)) {
      available.add(recommended);
    }
    for (WorkspaceRule rule : RULES) {
      if (canAccess(rule, permissions, scopes)) {
        available.add(rule.path());
      }
    }
    if (available.isEmpty()) {
      return new WorkspaceAccessProfile(
          List.of(), NO_PERMISSION, WorkspaceAccessProfile.NO_WORKSPACE,
          "当前账号没有可用工作台，请联系老板检查权限和数据范围");
    }
    List<String> workspaces = List.copyOf(available);
    return new WorkspaceAccessProfile(
        workspaces,
        workspaces.getFirst(),
        WorkspaceAccessProfile.READY,
        "权限正常，可进入工作台"
    );
  }

  public String recommendedWorkspace(String role) {
    return RECOMMENDED_BY_ROLE.getOrDefault(AccessControlService.canonicalRole(role), NO_PERMISSION);
  }

  private WorkspaceAccessProfile resolveStoreManager(
      AuthUser user,
      Set<String> permissions,
      Map<String, DataScope> scopes,
      List<String> assignedStoreIds
  ) {
    if (!permissions.contains(PermissionCodes.STORE_READ)) {
      return new WorkspaceAccessProfile(
          List.of(), NO_PERMISSION, WorkspaceAccessProfile.NO_WORKSPACE, "店长工作台未授权");
    }
    String storeId = normalizeStoreId(user.storeId());
    List<String> normalizedAssignments = normalizeStoreIds(assignedStoreIds);
    DataScope storeScope = scopes.getOrDefault(DataScopeDomains.STORE, DataScope.none());
    boolean bindingConsistent = !storeId.isBlank()
        && normalizedAssignments.size() == 1
        && storeId.equals(normalizedAssignments.getFirst());
    boolean ownsOnlyBoundStore = DataScopeModes.OWN_STORE.equals(storeScope.mode())
        && storeScope.storeIds().size() == 1
        && storeId.equals(storeScope.storeIds().getFirst());
    if (!bindingConsistent || !ownsOnlyBoundStore) {
      return new WorkspaceAccessProfile(
          List.of(), NO_PERMISSION, WorkspaceAccessProfile.NO_WORKSPACE,
          "店长门店绑定或本店数据范围不完整");
    }
    return new WorkspaceAccessProfile(
        List.of("/store"), "/store", WorkspaceAccessProfile.READY, "店长工作台权限正常");
  }

  private WorkspaceAccessProfile resolveEmployee(
      Set<String> permissions,
      Map<String, DataScope> scopes
  ) {
    LinkedHashSet<String> available = new LinkedHashSet<>();
    if (permissions.contains(PermissionCodes.EXAM_LEARN)
        || permissions.contains(PermissionCodes.EMPLOYEE_ASSISTANT_USE)) {
      available.add("/employee");
    }
    WorkspaceRule learningRule = rule("/learn/exams");
    if (learningRule != null && canAccess(learningRule, permissions, scopes)) {
      available.add("/learn/exams");
    }
    if (available.isEmpty()) {
      return new WorkspaceAccessProfile(
          List.of(), NO_PERMISSION, WorkspaceAccessProfile.NO_WORKSPACE,
          "员工账号没有可用工作台，请联系老板检查培训考试或员工助手权限");
    }
    List<String> workspaces = List.copyOf(available);
    return new WorkspaceAccessProfile(
        workspaces, workspaces.getFirst(), WorkspaceAccessProfile.READY, "员工工作台权限正常");
  }

  private boolean canAccess(
      WorkspaceRule rule,
      Set<String> permissions,
      Map<String, DataScope> scopes
  ) {
    if (rule.permissionCodes().stream().noneMatch(permissions::contains)) {
      return false;
    }
    if (rule.domainCode() == null) {
      return true;
    }
    return usable(scopes.getOrDefault(rule.domainCode(), DataScope.none()));
  }

  private boolean usable(DataScope scope) {
    return switch (scope.mode()) {
      case DataScopeModes.ALL, DataScopeModes.CENTRAL_WAREHOUSE, DataScopeModes.SELF -> true;
      case DataScopeModes.STORE_LIST, DataScopeModes.OWN_STORE -> !scope.storeIds().isEmpty();
      case DataScopeModes.WAREHOUSE_LIST -> !scope.warehouseIds().isEmpty();
      default -> false;
    };
  }

  private WorkspaceRule rule(String path) {
    return RULES.stream().filter(candidate -> candidate.path().equals(path)).findFirst().orElse(null);
  }

  private List<String> normalizeStoreIds(List<String> storeIds) {
    if (storeIds == null || storeIds.isEmpty()) {
      return List.of();
    }
    ArrayList<String> normalized = new ArrayList<>();
    for (String storeId : storeIds) {
      String value = normalizeStoreId(storeId);
      if (!value.isBlank() && !normalized.contains(value)) {
        normalized.add(value);
      }
    }
    normalized.sort(String::compareTo);
    return List.copyOf(normalized);
  }

  private String normalizeStoreId(String storeId) {
    return storeId == null ? "" : storeId.trim();
  }

  private record WorkspaceRule(String path, List<String> permissionCodes, String domainCode) {
  }
}
