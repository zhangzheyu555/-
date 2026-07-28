package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record WarehouseRequisitionReviewLineRequest(
    @NotNull Long itemId,
    @NotNull @PositiveOrZero BigDecimal approvedQuantity,
    @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal unitPrice
) {
  public WarehouseRequisitionReviewLineRequest(
      Long itemId,
      BigDecimal approvedQuantity
  ) {
    this(itemId, approvedQuantity, null);
  }
}
