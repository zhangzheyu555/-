package com.storeprofit.system.warehouse;

public record WarehouseMovementExport(
    byte[] content,
    String fileName,
    int rowCount
) {
}
