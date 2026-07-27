package com.storeprofit.system.warehouse;

import java.util.List;

public record WarehouseMovementFilterOptionsResponse(
    List<StoreOption> stores,
    List<ItemOption> items
) {
  public record StoreOption(
      String id,
      String name,
      String code,
      String area,
      String status
  ) {
  }

  public record ItemOption(
      long id,
      String name,
      String code,
      String category,
      String unit,
      boolean active
  ) {
  }
}
