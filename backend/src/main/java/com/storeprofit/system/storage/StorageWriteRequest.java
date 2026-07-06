package com.storeprofit.system.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StorageWriteRequest(
    @NotBlank String key,
    @NotNull String value
) {
}
