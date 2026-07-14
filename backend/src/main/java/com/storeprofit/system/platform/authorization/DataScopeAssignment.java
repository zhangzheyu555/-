package com.storeprofit.system.platform.authorization;

import java.util.List;

public record DataScopeAssignment(
    String domainCode,
    String mode,
    List<String> storeIds,
    List<String> warehouseIds
) {
  public DataScopeAssignment {
    domainCode = domainCode == null ? "" : domainCode.trim().toUpperCase();
    DataScope normalized = new DataScope(mode, storeIds, warehouseIds);
    mode = normalized.mode();
    storeIds = normalized.storeIds();
    warehouseIds = normalized.warehouseIds();
  }

  public DataScopeAssignment(String domainCode, String mode, List<String> storeIds) {
    this(domainCode, mode, storeIds, List.of());
  }
}
