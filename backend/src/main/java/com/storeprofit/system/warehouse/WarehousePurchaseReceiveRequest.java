package com.storeprofit.system.warehouse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record WarehousePurchaseReceiveRequest(
    @NotBlank String clientRequestId,
    @NotEmpty List<@Valid WarehousePurchaseReceiveLineRequest> lines,
    String note
) {
}
