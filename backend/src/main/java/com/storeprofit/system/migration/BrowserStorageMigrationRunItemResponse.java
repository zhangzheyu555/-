package com.storeprofit.system.migration;

public record BrowserStorageMigrationRunItemResponse(
    String key,
    String category,
    String targetTable,
    String result,
    boolean accepted
) {
}
