package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseStockBatchResponse(
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
}
