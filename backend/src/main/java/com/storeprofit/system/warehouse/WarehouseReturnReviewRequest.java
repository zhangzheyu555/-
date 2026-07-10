package com.storeprofit.system.warehouse;

public record WarehouseReturnReviewRequest(
    boolean approved,
    String note
) {
}
