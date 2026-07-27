package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record WarehouseRequisitionSummaryExportRequest(
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    List<String> storeIds,
    List<Long> productIds,
    String periodType,
    Boolean includeZeroRows,
    List<String> groupBy,
    Long warehouseId
) {
}
