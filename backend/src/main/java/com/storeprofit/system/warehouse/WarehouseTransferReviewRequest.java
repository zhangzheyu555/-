package com.storeprofit.system.warehouse;

import jakarta.validation.constraints.NotNull;

public record WarehouseTransferReviewRequest(
    @NotNull Boolean approved,
    String note
) {
}
