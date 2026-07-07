package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record WarehouseStockBatchRequest(
    @NotNull Long itemId,
    @NotBlank String batchNo,
    @NotBlank String receivedDate,
    String expiryDate,
    @NotNull @Positive BigDecimal quantity,
    @NotNull @PositiveOrZero BigDecimal unitCost,
    String note
) {
}
