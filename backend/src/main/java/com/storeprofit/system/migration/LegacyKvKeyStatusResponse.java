package com.storeprofit.system.migration;

public record LegacyKvKeyStatusResponse(
    String key,
    String targetTable,
    boolean present,
    long valueBytes,
    String migrationState
) {
}
