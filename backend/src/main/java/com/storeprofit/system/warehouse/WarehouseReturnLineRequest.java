package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record WarehouseReturnLineRequest(
    @NotNull Long itemId,
    @NotNull @Positive BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal returnPrice,
    String reason,
    String note
) {
  public WarehouseReturnLineRequest(
      Long itemId,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal returnPrice,
      String note
  ) {
    this(itemId, quantity, unitPrice, returnPrice, null, note);
  }
}
