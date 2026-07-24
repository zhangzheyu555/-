package com.storeprofit.system.warehouse;

import java.util.List;

public record WarehouseItemRequisitionScopeContextResponse(
    int activeStoreCount,
    List<RegionOption> regions,
    List<StoreOption> stores
) {
  public record RegionOption(String code, String name) {
  }

  public record StoreOption(String id, String name, String regionCode) {
  }
}
