package com.storeprofit.system.migration;

public record LegacyKvMigrationRunItemResponse(
    String key,
    String targetTable,
    String result,
    int migratedRecordCount,
    String message
) {
}
