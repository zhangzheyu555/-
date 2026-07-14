package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseTransferLineResponse(
    long id,
    long itemId,
    String itemName,
    String unit,
    BigDecimal requestedQuantity,
    BigDecimal approvedQuantity,
    BigDecimal reservedQuantity,
    BigDecimal shippedQuantity,
    BigDecimal receivedQuantity,
    BigDecimal inTransitQuantity,
    BigDecimal unitCost,
    BigDecimal amount,
    String note
) {
}
