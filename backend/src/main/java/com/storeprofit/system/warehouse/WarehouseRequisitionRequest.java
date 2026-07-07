package com.storeprofit.system.warehouse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record WarehouseRequisitionRequest(
    String storeId,
    @NotEmpty List<@Valid WarehouseRequisitionLineRequest> lines,
    String note
) {
}
