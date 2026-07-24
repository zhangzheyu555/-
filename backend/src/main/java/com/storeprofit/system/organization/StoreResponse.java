package com.storeprofit.system.organization;

public record StoreResponse(
    String id,
    String code,
    String name,
    long brandId,
    String brandName,
    String area,
    String manager,
    String managerPhone,
    String openDate,
    String status,
    String note,
    String regionCode,
    Long supplyWarehouseId,
    String supplyWarehouseName,
    String managerEmployeeId,
    String costAccountStoreId,
    String costAccountStoreName,
    long version
) {
  public StoreResponse(
      String id,
      String code,
      String name,
      long brandId,
      String brandName,
      String area,
      String manager,
      String openDate,
      String status,
      String note
  ) {
    this(id, code, name, brandId, brandName, area, manager, null, openDate, status, note,
        null, null, null, null, null, null, 0L);
  }

  public StoreResponse(
      String id,
      String code,
      String name,
      long brandId,
      String brandName,
      String area,
      String manager,
      String managerPhone,
      String openDate,
      String status,
      String note,
      String regionCode,
      Long supplyWarehouseId,
      String supplyWarehouseName
  ) {
    this(id, code, name, brandId, brandName, area, manager, managerPhone, openDate, status, note,
        regionCode, supplyWarehouseId, supplyWarehouseName, null, null, null, 0L);
  }
}
