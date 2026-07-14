package com.storeprofit.system.warehouse;

import jakarta.validation.Valid;
import java.util.List;

public record WarehouseTransferReceiveRequest(
    String clientRequestId,
    String note,
    List<@Valid WarehouseTransferReceiveLineRequest> lines
) {
  public static WarehouseTransferReceiveRequest empty() {
    return new WarehouseTransferReceiveRequest(null, null, List.of());
  }
}
