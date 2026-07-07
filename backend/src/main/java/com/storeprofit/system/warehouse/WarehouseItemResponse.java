package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseItemResponse(
    Long id,
    String code,
    String name,
    String category,
    String unit,
    String spec,
    BigDecimal unitPrice,
    Integer shelfLifeDays,
    BigDecimal cupsPerUnit,
    BigDecimal dailyUsageEstimate,
    int minStockDays,
    int maxStockDays,
    boolean active,
    BigDecimal stockQuantity,
    BigDecimal stockValue,
    BigDecimal daysAvailable,
    String nearestExpiryDate,
    String alertLevel,
    String alertText
) {
}
