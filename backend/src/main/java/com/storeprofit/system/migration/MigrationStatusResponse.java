package com.storeprofit.system.migration;

import java.util.List;

public record MigrationStatusResponse(
    boolean migrationRequired,
    int businessKeyCount,
    int presentBusinessKeyCount,
    List<LegacyKvKeyStatusResponse> legacyBusinessKeys
) {
  public MigrationStatusResponse {
    legacyBusinessKeys = List.copyOf(legacyBusinessKeys);
  }
}
