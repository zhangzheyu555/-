package com.storeprofit.system.warehouse;

public record WarehouseFacilityResponse(
    long id,
    String code,
    String name,
    String type,
    String regionCode,
    Long parentWarehouseId,
    String parentWarehouseName,
    boolean externalPurchaseAllowed,
    boolean storeSupplyAllowed,
    boolean enabled,
    boolean canRead,
    boolean canPurchase,
    boolean canRequestTransfer,
    boolean canApproveTransfer,
    boolean canShipTransfer,
    boolean canReceiveTransfer
) {
}
