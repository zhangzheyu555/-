package com.storeprofit.system.platform.users;

import java.util.List;

public record UserResponse(
    long id,
    long tenantId,
    String tenantName,
    String username,
    String displayName,
    String role,
    String roleLabel,
    String storeId,
    boolean enabled,
    List<String> storeScope
) {
}
