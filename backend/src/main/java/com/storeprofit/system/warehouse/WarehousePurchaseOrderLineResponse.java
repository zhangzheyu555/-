package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehousePurchaseOrderLineResponse(
    Long id,
    Long itemId,
    String itemName,
    String unit,
    BigDecimal orderedQuantity,
    BigDecimal receivedQuantity,
    BigDecimal unitCost,
    BigDecimal amount,
    String note
) {
}
