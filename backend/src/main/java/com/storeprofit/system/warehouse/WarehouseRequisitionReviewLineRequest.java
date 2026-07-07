package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record WarehouseRequisitionReviewLineRequest(
    @NotNull Long itemId,
    @NotNull @PositiveOrZero BigDecimal approvedQuantity
) {
}
