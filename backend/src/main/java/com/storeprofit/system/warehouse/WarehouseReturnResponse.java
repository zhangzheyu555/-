package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.util.List;

public record WarehouseReturnResponse(
    String id,
    String returnNo,
    String sourceRequisitionId,
    String sourceDeliveryId,
    String returnStoreId,
    String returnStoreName,
    Long receiveWarehouseId,
    String receiveWarehouseName,
    String receiveDepartment,
    String status,
    String statusLabel,
    BigDecimal totalAmount,
    String handledBy,
    String createdBy,
    String updatedBy,
    String reviewedBy,
    String checkedBy,
    String reason,
    String note,
    String reviewNote,
    String receivedNote,
    String returnDate,
    String reviewedAt,
    String receivedAt,
    String createdAt,
    String updatedAt,
    int lineCount,
    int attachmentCount,
    List<WarehouseReturnLineResponse> lines
) {
}
