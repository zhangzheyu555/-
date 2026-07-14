package com.storeprofit.system.platform.users;

import com.storeprofit.system.platform.authorization.DataScopeAssignment;
import com.storeprofit.system.platform.authorization.UserPermissionOverride;
import java.util.List;

public record UserAuthorizationResponse(
    long userId,
    String role,
    String storeId,
    long permissionVersion,
    List<String> roleTemplatePermissions,
    List<DataScopeAssignment> dataScopes,
    List<UserPermissionOverride> overrides,
    List<String> effectivePermissions,
    List<String> availableWorkspaces,
    String defaultWorkspace,
    String effectivePermissionStatus,
    String effectivePermissionMessage
) {
  public UserAuthorizationResponse {
    roleTemplatePermissions = roleTemplatePermissions == null ? List.of() : List.copyOf(roleTemplatePermissions);
    dataScopes = dataScopes == null ? List.of() : List.copyOf(dataScopes);
    overrides = overrides == null ? List.of() : List.copyOf(overrides);
    effectivePermissions = effectivePermissions == null ? List.of() : List.copyOf(effectivePermissions);
    availableWorkspaces = availableWorkspaces == null ? List.of() : List.copyOf(availableWorkspaces);
  }

  public UserAuthorizationResponse(
      long userId,
      String role,
      String storeId,
      long permissionVersion,
      List<String> roleTemplatePermissions,
      List<DataScopeAssignment> dataScopes,
      List<UserPermissionOverride> overrides,
      List<String> effectivePermissions
  ) {
    this(
        userId,
        role,
        storeId,
        permissionVersion,
        roleTemplatePermissions,
        dataScopes,
        overrides,
        effectivePermissions,
        List.of(),
        "/no-permission",
        "NO_WORKSPACE",
        "当前账号没有可用工作台"
    );
  }
}
