package com.storeprofit.system.warehouse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record WarehouseTransferCreateRequest(
    @NotNull Long sourceWarehouseId,
    @NotNull Long targetWarehouseId,
    @NotEmpty List<@Valid WarehouseTransferLineRequest> lines,
    String note,
    String clientRequestId
) {
}
