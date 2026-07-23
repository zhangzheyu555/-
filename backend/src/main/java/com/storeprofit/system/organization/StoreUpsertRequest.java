package com.storeprofit.system.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StoreUpsertRequest(
    @NotBlank String id,
    String code,
    @NotBlank String name,
    @NotNull Long brandId,
    String area,
    String manager,
    String managerPhone,
    String openDate,
    String status,
    String note,
    String regionCode,
    Long supplyWarehouseId
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
    this(id, code, name, brandId, area, manager, null, openDate, status, note, null, null);
  }
}
