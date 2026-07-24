package com.storeprofit.system.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StoreStatusChangeRequest(
    @NotBlank String status,
    @NotNull Long version
) {
}
