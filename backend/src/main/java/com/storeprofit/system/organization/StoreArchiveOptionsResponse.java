package com.storeprofit.system.organization;

import java.util.List;

public record StoreArchiveOptionsResponse(
    List<RegionOption> regions,
    List<ManagerOption> managers,
    List<StatusOption> statuses,
    List<CostAccountOption> costAccounts
) {
  public record RegionOption(String code, String name, long supplyWarehouseId) {
  }

  public record ManagerOption(
      String employeeId,
      String name,
      String phone,
      String storeId,
      String storeName
  ) {
  }

  public record StatusOption(String value, String label, boolean active) {
  }

  public record CostAccountOption(
      String storeId,
      String storeCode,
      String storeName,
      String status
  ) {
  }
}
