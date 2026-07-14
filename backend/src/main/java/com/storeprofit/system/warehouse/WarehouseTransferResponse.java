package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.util.List;

public record WarehouseTransferResponse(
    String id,
    String transferNo,
    String status,
    String statusLabel,
    long sourceWarehouseId,
    String sourceWarehouseName,
    long targetWarehouseId,
    String targetWarehouseName,
    BigDecimal totalAmount,
    String requestedBy,
    String approvedBy,
    String shippedBy,
    String receivedBy,
    String cancelledBy,
    String createdAt,
    String submittedAt,
    String reviewedAt,
    String shippedAt,
    String receivedAt,
    String cancelledAt,
    String note,
    String reviewNote,
    long version,
    List<WarehouseTransferLineResponse> lines
) {
}
