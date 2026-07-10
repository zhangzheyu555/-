package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseReturnLineResponse(
    Long id,
    Long itemId,
    String itemName,
    String spec,
    Long batchId,
    String batchNo,
    Long sourceRequisitionLineId,
    BigDecimal quantity,
    String unit,
    BigDecimal unitPrice,
    BigDecimal returnPrice,
    BigDecimal amount,
    String reason,
    String note
) {
}
