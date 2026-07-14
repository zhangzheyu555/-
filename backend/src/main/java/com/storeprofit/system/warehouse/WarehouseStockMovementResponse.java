package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseStockMovementResponse(
    Long id,
    Long itemId,
    Long batchId,
    String itemName,
    String movementType,
    String movementTypeLabel,
    BigDecimal quantityDelta,
    Long warehouseId,
    String warehouseName,
    Long sourceWarehouseId,
    String sourceWarehouseName,
    Long targetWarehouseId,
    String targetWarehouseName,
    String sourceType,
    String sourceId,
    String storeId,
    String storeName,
    String note,
    String operatorName,
    String createdAt,
    String batchNo
) {
  public WarehouseStockMovementResponse(
      Long id,
      Long itemId,
      Long batchId,
      String itemName,
      String movementType,
      String movementTypeLabel,
      BigDecimal quantityDelta,
      String sourceType,
      String sourceId,
      String storeId,
      String storeName,
      String note,
      String operatorName,
      String createdAt,
      String batchNo
  ) {
    this(id, itemId, batchId, itemName, movementType, movementTypeLabel, quantityDelta,
        null, null, null, null, null, null, sourceType, sourceId, storeId,
        storeName, note, operatorName, createdAt, batchNo);
  }
}
