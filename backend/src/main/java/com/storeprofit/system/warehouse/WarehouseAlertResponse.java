package com.storeprofit.system.warehouse;

public record WarehouseAlertResponse(
    String level,
    String type,
    Long itemId,
    String itemName,
    String message
) {
}
