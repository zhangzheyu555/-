package com.storeprofit.system.warehouse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record WarehouseReturnRequest(
    String returnStoreId,
    @NotNull String sourceRequisitionId,
    String receiveDepartment,
    String reason,
    String note,
    String returnDate,
    @NotEmpty List<@Valid WarehouseReturnLineRequest> lines,
    List<@Valid WarehouseReturnAttachmentRequest> attachments
) {
}
