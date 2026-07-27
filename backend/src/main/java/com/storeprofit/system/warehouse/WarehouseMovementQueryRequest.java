package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record WarehouseMovementQueryRequest(
    @NotNull Long warehouseId,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    List<String> storeIds,
    List<Long> itemIds,
    List<String> directions,
    List<String> sourceTypes,
    Integer page,
    Integer pageSize
) {
  public int effectivePage() {
    return page == null || page < 1 ? 1 : page;
  }

  public int effectivePageSize() {
    if (pageSize == null || pageSize < 1) return 50;
    return Math.min(pageSize, 200);
  }

  public int offset() {
    return (effectivePage() - 1) * effectivePageSize();
  }
}
