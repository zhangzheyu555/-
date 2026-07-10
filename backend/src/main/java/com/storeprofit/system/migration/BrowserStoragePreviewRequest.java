package com.storeprofit.system.migration;

import java.util.Map;

public record BrowserStoragePreviewRequest(Map<String, String> entries) {
  public BrowserStoragePreviewRequest {
    entries = entries == null ? Map.of() : Map.copyOf(entries);
  }
}
