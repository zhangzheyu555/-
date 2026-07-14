package com.storeprofit.system.platform.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UserAccessProfileUpdateRequest(
    @NotBlank String displayName,
    @NotBlank String role,
    String storeId,
    List<String> storeScope,
    boolean enabled,
    @NotNull List<@Valid UserPermissionOverrideRequest> overrides,
    @NotNull List<@Valid UserDataScopeRequest> dataScopes
) {
}
