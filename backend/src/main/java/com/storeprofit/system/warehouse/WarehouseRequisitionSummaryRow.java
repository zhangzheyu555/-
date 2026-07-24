package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WarehouseRequisitionSummaryRow(
    String storeId,
    String storeName,
    Long productId,
    String productName,
    LocalDate periodStart,
    LocalDate periodEnd,
    String periodLabel,
    String unit,
    BigDecimal orderedQuantity,
    BigDecimal orderedAmount
) {
}
