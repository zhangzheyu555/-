package com.storeprofit.system.platform.users;

import jakarta.validation.constraints.NotBlank;

public record UserPermissionOverrideRequest(
    @NotBlank String permissionCode,
    @NotBlank String effect
) {
}
