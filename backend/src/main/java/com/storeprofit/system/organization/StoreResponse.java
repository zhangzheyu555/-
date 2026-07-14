package com.storeprofit.system.organization;

public record StoreResponse(
    String id,
    String code,
    String name,
    long brandId,
    String brandName,
    String area,
    String manager,
    String openDate,
    String status,
    String note,
    String regionCode,
    Long supplyWarehouseId,
    String supplyWarehouseName
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
    this(id, code, name, brandId, brandName, area, manager, openDate, status, note,
        null, null, null);
  }
}
