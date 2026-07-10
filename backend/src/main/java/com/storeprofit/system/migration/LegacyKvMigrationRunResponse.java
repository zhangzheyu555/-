package com.storeprofit.system.migration;

import java.util.List;

public record LegacyKvMigrationRunResponse(
    boolean executed,
    int requestedKeyCount,
    int migratedKeyCount,
    int skippedKeyCount,
    int failedKeyCount,
    List<LegacyKvMigrationRunItemResponse> items
) {
  public LegacyKvMigrationRunResponse {
    items = List.copyOf(items);
  }
}
