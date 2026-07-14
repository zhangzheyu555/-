package com.storeprofit.system.platform.authorization;

public record PermissionCatalogEntry(
    String permissionCode,
    String moduleCode,
    String permissionName,
    String description,
    String riskLevel,
    boolean enabled,
    int sortOrder
) {
}
