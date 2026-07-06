package com.storeprofit.system.platform.auth;

public record AuthUser(
    long id,
    String username,
    String passwordHash,
    String displayName,
    String role,
    String storeId,
    boolean enabled
) {
}
