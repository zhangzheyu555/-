package com.storeprofit.system.platform.auth;

import jakarta.validation.constraints.NotBlank;

public record InitialPasswordChangeRequest(
    @NotBlank String credential,
    @NotBlank String newPassword,
    @NotBlank String confirmPassword
) {
}
