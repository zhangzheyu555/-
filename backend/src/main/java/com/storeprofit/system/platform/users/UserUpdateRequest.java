package com.storeprofit.system.platform.users;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record UserUpdateRequest(
    @NotBlank String displayName,
    @NotBlank String role,
    String storeId,
    List<String> storeScope,
    boolean enabled
) {
}
