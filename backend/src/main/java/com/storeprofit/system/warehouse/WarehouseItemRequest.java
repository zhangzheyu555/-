package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record WarehouseItemRequest(
    @NotBlank String code,
    @NotBlank String name,
    String category,
    String unit,
    String spec,
    @NotNull @PositiveOrZero BigDecimal unitPrice,
    Integer shelfLifeDays,
    @NotNull @PositiveOrZero BigDecimal cupsPerUnit,
    @NotNull @PositiveOrZero BigDecimal dailyUsageEstimate,
    Integer minStockDays,
    Integer maxStockDays,
    Boolean active
) {
}
