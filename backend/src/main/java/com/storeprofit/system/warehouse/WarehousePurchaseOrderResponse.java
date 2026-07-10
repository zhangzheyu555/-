package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.util.List;

public record WarehousePurchaseOrderResponse(
    String id,
    Long supplierId,
    String supplierName,
    String status,
    String statusLabel,
    BigDecimal totalAmount,
    String note,
    String createdBy,
    String receivedBy,
    String createdAt,
    String receivedAt,
    List<WarehousePurchaseOrderLineResponse> lines
) {
}
