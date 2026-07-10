package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.util.List;

public record WarehouseRequisitionResponse(
    String id,
    String storeId,
    String storeName,
    String status,
    String statusLabel,
    BigDecimal totalAmount,
    String note,
    String submittedBy,
    String reviewedBy,
    String shippedBy,
    String receivedBy,
    String submittedAt,
    String reviewedAt,
    String shippedAt,
    String receivedAt,
    List<WarehouseRequisitionLineResponse> lines
) {
}
