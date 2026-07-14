package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseStockBatchResponse(
    Long id,
    Long itemId,
    String itemName,
    Long warehouseId,
    String warehouseName,
    String unit,
    String batchNo,
    String receivedDate,
    String expiryDate,
    BigDecimal quantity,
    BigDecimal unitCost,
    String note,
    String createdAt,
    String status
) {
  public WarehouseStockBatchResponse(
      Long id,
      Long itemId,
      String itemName,
      String unit,
      String batchNo,
      String receivedDate,
      String expiryDate,
      BigDecimal quantity,
      BigDecimal unitCost,
      String note,
      String createdAt,
      String status
  ) {
    this(id, itemId, itemName, null, null, unit, batchNo, receivedDate, expiryDate,
        quantity, unitCost, note, createdAt, status);
  }
}
