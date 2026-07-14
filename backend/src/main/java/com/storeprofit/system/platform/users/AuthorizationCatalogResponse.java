package com.storeprofit.system.platform.users;

import com.storeprofit.system.platform.authorization.PermissionCatalogEntry;
import java.util.List;

public record AuthorizationCatalogResponse(
    List<PermissionCatalogEntry> permissions,
    List<String> dataScopeDomains,
    List<String> dataScopeModes
) {
}
