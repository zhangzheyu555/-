package com.storeprofit.system.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StoreUpsertRequest(
    @NotBlank String id,
    String code,
    @NotBlank String name,
    @NotNull Long brandId,
    String area,
    String manager,
    String openDate,
    String status,
    String note
) {
}
