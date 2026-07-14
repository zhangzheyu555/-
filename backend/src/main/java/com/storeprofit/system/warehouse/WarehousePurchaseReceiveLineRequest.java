package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record WarehousePurchaseReceiveLineRequest(
    @NotNull Long itemId,
    @NotBlank String batchNo,
    @NotBlank String receivedDate,
    String expiryDate,
    @NotNull @Positive BigDecimal quantity,
    String note
) {
}
