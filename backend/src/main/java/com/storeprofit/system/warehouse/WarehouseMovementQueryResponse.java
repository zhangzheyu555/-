package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.util.List;

public record WarehouseMovementQueryResponse(
    List<WarehouseStockMovementResponse> rows,
    long total,
    int page,
    int pageSize,
    BigDecimal totalIn,
    BigDecimal totalOut,
    BigDecimal netChange
) {
}
