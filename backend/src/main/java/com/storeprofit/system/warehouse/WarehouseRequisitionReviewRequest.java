package com.storeprofit.system.warehouse;

import jakarta.validation.Valid;
import java.util.List;

public record WarehouseRequisitionReviewRequest(
    boolean approved,
    List<@Valid WarehouseRequisitionReviewLineRequest> lines,
    String note
) {
}
