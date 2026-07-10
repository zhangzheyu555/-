package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

record WarehouseStockBatchRow(
    Long id,
    Long itemId,
    String batchNo,
    String expiryDate,
    BigDecimal quantity
) {
}
