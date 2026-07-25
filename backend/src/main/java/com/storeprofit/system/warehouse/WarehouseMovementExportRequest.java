package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * Request for POST /api/warehouse/movements/export.
 * Same filtering as query but without pagination — exports all matching rows up to the 50,000 limit.
 */
public record WarehouseMovementExportRequest(
    @NotNull Long warehouseId,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    List<String> storeIds,
    List<Long> itemIds,
    List<String> directions,
    List<String> sourceTypes
) {
}
