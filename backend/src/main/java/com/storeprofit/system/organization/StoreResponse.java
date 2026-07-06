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
    String note
) {
}
