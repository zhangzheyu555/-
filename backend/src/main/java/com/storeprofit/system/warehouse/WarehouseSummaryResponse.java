package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseSummaryResponse(
    int itemCount,
    int lowStockCount,
    int expiringCount,
    int overstockCount,
    int pendingRequisitionCount,
    BigDecimal stockValue
) {
}
