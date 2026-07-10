package com.storeprofit.system.platform.session;

import java.util.List;

public record SessionUser(
    long id,
    long tenantId,
    String tenantName,
    String displayName,
    String role,
    String roleLabel,
    List<String> storeScope
) {
}
