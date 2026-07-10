package com.storeprofit.system.platform.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password,
    Long tenantId
) {
}
