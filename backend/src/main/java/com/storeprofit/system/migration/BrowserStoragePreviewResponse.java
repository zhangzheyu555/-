package com.storeprofit.system.migration;

import java.util.List;

public record BrowserStoragePreviewResponse(
    boolean migrationRequired,
    int submittedKeyCount,
    int businessKeyCount,
    int blockedKeyCount,
    int ignoredKeyCount,
    long totalBusinessValueBytes,
    List<BrowserStoragePreviewItemResponse> items
) {
  public BrowserStoragePreviewResponse {
    items = List.copyOf(items);
  }
}
