package com.storeprofit.system.organization;

import jakarta.validation.constraints.NotNull;

public record StoreUpsertRequest(
    String id,
    String code,
    String name,
    @NotNull Long brandId,
    String area,
    String manager,
    String managerPhone,
    String openDate,
    String status,
    String note,
    String regionCode,
    Long supplyWarehouseId,
    String managerEmployeeId,
    String costAccountStoreId,
    Long version
) {
  public StoreUpsertRequest(
      String id,
      String code,
      String name,
      Long brandId,
      String area,
      String manager,
      String openDate,
      String status,
      String note
  ) {
    this(id, code, name, brandId, area, manager, null, openDate, status, note, null, null,
        null, null, null);
  }

  public StoreUpsertRequest(
      String id,
      String code,
      String name,
      Long brandId,
      String area,
      String manager,
      String managerPhone,
      String openDate,
      String status,
      String note,
      String regionCode,
      Long supplyWarehouseId
  ) {
    this(id, code, name, brandId, area, manager, managerPhone, openDate, status, note,
        regionCode, supplyWarehouseId, null, null, null);
  }
}
