package com.storeprofit.system.migration;

import java.util.List;

public record LegacyKvMigrationPreviewResponse(
    boolean automaticRunAvailable,
    int businessKeyCount,
    int actionableKeyCount,
    long totalValueBytes,
    List<LegacyKvMigrationPreviewItemResponse> items
) {
  public LegacyKvMigrationPreviewResponse {
    items = List.copyOf(items);
  }
}
