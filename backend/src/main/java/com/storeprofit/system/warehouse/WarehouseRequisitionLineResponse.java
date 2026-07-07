package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseRequisitionLineResponse(
    Long id,
    Long itemId,
    String itemName,
    String unit,
    BigDecimal requestedQuantity,
    BigDecimal approvedQuantity,
    BigDecimal shippedQuantity,
    BigDecimal unitPrice,
    BigDecimal amount,
    String warningText,
    String note
) {
}
