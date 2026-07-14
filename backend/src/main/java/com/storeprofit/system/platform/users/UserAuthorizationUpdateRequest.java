package com.storeprofit.system.platform.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UserAuthorizationUpdateRequest(
    @NotNull List<@Valid UserPermissionOverrideRequest> overrides,
    @NotNull List<@Valid UserDataScopeRequest> dataScopes
) {
}
