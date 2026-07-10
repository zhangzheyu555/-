package com.storeprofit.system.migration;

import java.util.List;

public record BrowserStorageMigrationRunResponse(
    boolean executed,
    int submittedKeyCount,
    int writtenKeyCount,
    int blockedKeyCount,
    int ignoredKeyCount,
    List<BrowserStorageMigrationRunItemResponse> items
) {
  public BrowserStorageMigrationRunResponse {
    items = List.copyOf(items);
  }
}
