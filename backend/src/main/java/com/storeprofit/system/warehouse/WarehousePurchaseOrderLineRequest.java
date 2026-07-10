package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record WarehousePurchaseOrderLineRequest(
    @NotNull Long itemId,
    @NotNull @DecimalMin(value = "0.01") BigDecimal orderedQuantity,
    @NotNull @DecimalMin(value = "0.00") BigDecimal unitCost,
    String note
) {
}
