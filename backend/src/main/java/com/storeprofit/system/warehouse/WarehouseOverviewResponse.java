package com.storeprofit.system.warehouse;

import java.util.List;

public record WarehouseOverviewResponse(
    WarehouseSummaryResponse summary,
    List<WarehouseAlertResponse> alerts,
    List<WarehouseItemResponse> items,
    List<WarehouseRequisitionResponse> requisitions
) {
}
