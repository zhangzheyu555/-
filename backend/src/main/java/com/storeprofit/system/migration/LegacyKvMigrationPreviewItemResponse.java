package com.storeprofit.system.migration;

public record LegacyKvMigrationPreviewItemResponse(
    String key,
    String targetTable,
    boolean present,
    long valueBytes,
    String plannedAction,
    boolean automaticMigrationReady
) {
}
