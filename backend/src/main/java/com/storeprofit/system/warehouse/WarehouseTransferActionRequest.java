package com.storeprofit.system.warehouse;

public record WarehouseTransferActionRequest(
    String clientRequestId,
    String note
) {
  public static WarehouseTransferActionRequest empty() {
    return new WarehouseTransferActionRequest(null, null);
  }
}
