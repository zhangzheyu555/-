package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotBlank;

public record WarehouseItemCategoryRequest(
    Long id,
    @NotBlank String name,
    Long parentId,
    Integer sortOrder,
    Boolean enabled
) {
}
