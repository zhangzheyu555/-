package com.storeprofit.system.warehouse;

public record WarehouseRequisitionSummaryExport(
    byte[] content,
    String fileName,
    int rowCount
) {
}
