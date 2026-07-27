package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WarehouseRequisitionSummaryDailyRow(
    String storeId,
    String storeName,
    Long productId,
    String productName,
    LocalDate orderDate,
    String unit,
    BigDecimal orderedQuantity,
    BigDecimal orderedAmount
) {
}
