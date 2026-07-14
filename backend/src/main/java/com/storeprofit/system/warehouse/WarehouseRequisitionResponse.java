package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.util.List;

public record WarehouseRequisitionResponse(
    String id,
    String storeId,
    String storeName,
    Long warehouseId,
    String warehouseName,
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
  public WarehouseRequisitionResponse(
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
    this(id, storeId, storeName, null, null, status, statusLabel, totalAmount, note,
        submittedBy, reviewedBy, shippedBy, receivedBy, submittedAt, reviewedAt,
        shippedAt, receivedAt, lines);
  }
}
