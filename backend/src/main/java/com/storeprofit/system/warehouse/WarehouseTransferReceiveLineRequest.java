package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record WarehouseTransferReceiveLineRequest(
    @NotNull Long itemId,
    @NotNull @Positive BigDecimal receivedQuantity
) {
}
