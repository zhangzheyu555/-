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
    String sourceType,
    String sourceId,
    String storeId,
    String storeName,
    String note,
    String operatorName,
    String createdAt,
    String batchNo
) {
}
