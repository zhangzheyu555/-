package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.util.List;

public record WarehouseItemResponse(
    Long id,
    String code,
    String name,
    Long categoryId,
    String categoryName,
    String category,
    String imageUrl,
    String unit,
    String purchaseUnit,
    String stockUnit,
    String ingredientUnit,
    String unitConversionText,
    String spec,
    String warehouseLocation,
    BigDecimal unitPrice,
    Integer shelfLifeDays,
    BigDecimal cupsPerUnit,
    BigDecimal dailyUsageEstimate,
    int minStockDays,
    int maxStockDays,
    BigDecimal minStockQuantity,
    boolean alertEnabled,
    Integer expiryAlertDays,
    boolean active,
    BigDecimal stockQuantity,
    BigDecimal storeStockQuantity,
    BigDecimal warehouseAvailableQuantity,
    BigDecimal stockValue,
    BigDecimal daysAvailable,
    String nearestExpiryDate,
    String stockStatus,
    String alertLevel,
    String alertText,
    String itemDescription,
    int sortOrder,
    String itemAttributes,
    List<WarehouseItemDepartmentResponse> departments
) {
}
