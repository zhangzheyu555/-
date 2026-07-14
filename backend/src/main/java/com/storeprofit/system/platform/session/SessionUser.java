package com.storeprofit.system.platform.session;

import com.storeprofit.system.platform.authorization.DataScope;
import java.util.List;
import java.util.Map;

public record SessionUser(
    long id,
    long tenantId,
    String tenantName,
    String displayName,
    String role,
    String roleLabel,
    List<String> storeScope,
    List<String> permissions,
    Map<String, DataScope> dataScopes,
    String defaultWorkspace,
    long permissionVersion,
    String boundStoreId,
    String boundStoreName,
    Long brandId,
    String brandName,
    DataScope dataScope
) {
  public SessionUser {
    storeScope = storeScope == null ? List.of() : List.copyOf(storeScope);
    permissions = permissions == null ? List.of() : List.copyOf(permissions);
    dataScopes = dataScopes == null ? Map.of() : Map.copyOf(dataScopes);
    defaultWorkspace = defaultWorkspace == null || defaultWorkspace.isBlank()
        ? "/no-permission"
        : defaultWorkspace;
    boundStoreId = boundStoreId == null || boundStoreId.isBlank() ? null : boundStoreId.trim();
    boundStoreName = boundStoreName == null || boundStoreName.isBlank() ? null : boundStoreName.trim();
    brandName = brandName == null || brandName.isBlank() ? null : brandName.trim();
    dataScope = dataScope == null
        ? dataScopes.getOrDefault("STORE", DataScope.none())
        : dataScope;
  }

  /** Compatibility constructor for existing callers that use the V37 session contract. */
  public SessionUser(
      long id,
      long tenantId,
      String tenantName,
      String displayName,
      String role,
      String roleLabel,
      List<String> storeScope,
      List<String> permissions,
      Map<String, DataScope> dataScopes,
      String defaultWorkspace,
      long permissionVersion
  ) {
    this(id, tenantId, tenantName, displayName, role, roleLabel, storeScope, permissions, dataScopes,
        defaultWorkspace, permissionVersion, null, null, null, null,
        dataScopes == null ? DataScope.none() : dataScopes.getOrDefault("STORE", DataScope.none()));
  }

  public SessionUser(
      long id,
      long tenantId,
      String tenantName,
      String displayName,
      String role,
      String roleLabel,
      List<String> storeScope
  ) {
    this(
        id,
        tenantId,
        tenantName,
        displayName,
        role,
        roleLabel,
        storeScope,
        List.of(),
        Map.of(),
        "/no-permission",
        1L,
        null,
        null,
        null,
        null,
        DataScope.none()
    );
  }
}
