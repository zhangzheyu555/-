package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record WarehouseAlertSettingsRequest(
    @PositiveOrZero BigDecimal minStockQuantity,
    Boolean alertEnabled,
    Integer expiryAlertDays,
    Long warehouseId
) {
  public WarehouseAlertSettingsRequest(
      BigDecimal minStockQuantity,
      Boolean alertEnabled,
      Integer expiryAlertDays
  ) {
    this(minStockQuantity, alertEnabled, expiryAlertDays, null);
  }
}
