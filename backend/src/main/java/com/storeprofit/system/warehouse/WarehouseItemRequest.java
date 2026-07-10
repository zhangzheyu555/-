package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

public record WarehouseItemRequest(
    Long id,
    @NotBlank String code,
    @NotBlank String name,
    Long categoryId,
    String category,
    String imageUrl,
    String unit,
    String purchaseUnit,
    String stockUnit,
    String ingredientUnit,
    String unitConversionText,
    String spec,
    String warehouseLocation,
    @PositiveOrZero BigDecimal unitPrice,
    Integer shelfLifeDays,
    @PositiveOrZero BigDecimal cupsPerUnit,
    @PositiveOrZero BigDecimal dailyUsageEstimate,
    Integer minStockDays,
    Integer maxStockDays,
    @PositiveOrZero BigDecimal minStockQuantity,
    Boolean alertEnabled,
    Integer expiryAlertDays,
    String itemDescription,
    Integer sortOrder,
    String itemAttributes,
    Boolean active,
    List<WarehouseItemDepartmentRequest> departments
) {
}
