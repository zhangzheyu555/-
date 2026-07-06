package com.storeprofit.system.platform.session;

import java.util.List;

public record SessionUser(
    long id,
    String displayName,
    String role,
    String roleLabel,
    List<String> storeScope
) {
}
