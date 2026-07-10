package com.storeprofit.system.migration;

import java.util.List;

public record LegacyKvMigrationRunRequest(List<String> keys) {
  public LegacyKvMigrationRunRequest {
    keys = keys == null ? List.of() : List.copyOf(keys);
  }
}
