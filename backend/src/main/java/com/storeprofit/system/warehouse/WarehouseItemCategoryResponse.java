package com.storeprofit.system.warehouse;

import java.util.List;

public record WarehouseItemCategoryResponse(
    Long id,
    String name,
    Long parentId,
    int sortOrder,
    boolean enabled,
    List<WarehouseItemCategoryResponse> children
) {
}
