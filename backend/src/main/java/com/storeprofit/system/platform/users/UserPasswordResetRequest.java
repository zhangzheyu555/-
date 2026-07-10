package com.storeprofit.system.platform.users;

import jakarta.validation.constraints.NotBlank;

public record UserPasswordResetRequest(@NotBlank String password) {
}
