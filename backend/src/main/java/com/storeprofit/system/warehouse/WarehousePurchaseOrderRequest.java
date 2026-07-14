package com.storeprofit.system.warehouse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record WarehousePurchaseOrderRequest(
    Long supplierId,
    String note,
    @NotEmpty List<@Valid WarehousePurchaseOrderLineRequest> lines,
    Long warehouseId,
    String clientRequestId
) {
  public WarehousePurchaseOrderRequest(
      Long supplierId, String note, List<WarehousePurchaseOrderLineRequest> lines
  ) {
    this(supplierId, note, lines, null, null);
  }
}
