package com.storeprofit.system.warehouse;

public record WarehouseSupplierResponse(
    Long id,
    String name,
    String contactName,
    String phone,
    String settlementCycle,
    boolean active
) {
}
