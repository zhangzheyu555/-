package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record WarehouseAlertSettingsRequest(
    @PositiveOrZero BigDecimal minStockQuantity,
    Boolean alertEnabled,
    Integer expiryAlertDays
) {
}
