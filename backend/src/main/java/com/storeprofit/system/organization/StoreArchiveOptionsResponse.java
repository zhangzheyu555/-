package com.storeprofit.system.organization;

import java.util.List;

public record StoreArchiveOptionsResponse(
    List<RegionOption> regions,
    List<EmployeeOption> employees,
    List<StatusOption> statuses
) {
  public record RegionOption(String code, String name, long supplyWarehouseId) {
  }

  public record EmployeeOption(
      String employeeId,
      String name,
      String phone,
      String position,
      String storeId,
      String storeName,
      String responsibleStoreId,
      String responsibleStoreName
  ) {
  }

  public record StatusOption(String value, String label, boolean active) {
  }
}
