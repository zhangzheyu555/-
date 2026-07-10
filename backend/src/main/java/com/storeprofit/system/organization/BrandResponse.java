package com.storeprofit.system.organization;

public record BrandResponse(
    long id,
    String code,
    String name,
    String color,
    int sortOrder
) {
}
