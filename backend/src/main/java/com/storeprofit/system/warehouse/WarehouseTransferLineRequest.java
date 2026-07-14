package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record WarehouseTransferLineRequest(
    @NotNull Long itemId,
    @NotNull @Positive BigDecimal quantity,
    String note
) {
}
