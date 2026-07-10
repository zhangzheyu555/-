package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseDeliveryLineResponse(
    Long id,
    Long itemId,
    String itemName,
    String unit,
    BigDecimal shippedQuantity,
    BigDecimal receivedQuantity,
    BigDecimal unitPrice,
    BigDecimal amount
) {
}
