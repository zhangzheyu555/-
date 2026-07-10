package com.storeprofit.system.warehouse;

import java.util.List;

public record WarehouseDeliveryResponse(
    String id,
    String requisitionId,
    String storeId,
    String storeName,
    String status,
    String statusLabel,
    String shippedBy,
    String receivedBy,
    String shippedAt,
    String receivedAt,
    List<WarehouseDeliveryLineResponse> lines
) {
}
