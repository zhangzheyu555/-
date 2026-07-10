package com.storeprofit.system.migration;

public record BrowserStoragePreviewItemResponse(
    String key,
    String category,
    String targetTable,
    long valueBytes,
    String plannedAction,
    boolean accepted
) {
}
