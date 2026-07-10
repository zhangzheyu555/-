package com.storeprofit.system.platform.auth;

public record AuthUser(
    long id,
    long tenantId,
    String tenantName,
    String username,
    String passwordHash,
    String displayName,
    String role,
    String storeId,
    boolean enabled
) {
}
