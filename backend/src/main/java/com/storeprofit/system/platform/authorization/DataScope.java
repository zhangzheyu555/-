package com.storeprofit.system.platform.authorization;

import java.util.List;

public record DataScope(String mode, List<String> storeIds, List<String> warehouseIds) {
  public DataScope {
    mode = mode == null || mode.isBlank() ? DataScopeModes.NONE : mode.trim().toUpperCase();
    storeIds = storeIds == null ? List.of() : storeIds.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .sorted()
        .toList();
    warehouseIds = warehouseIds == null ? List.of() : warehouseIds.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .sorted()
        .toList();
    if (DataScopeModes.WAREHOUSE_LIST.equals(mode)) {
      if (warehouseIds.isEmpty()) {
        warehouseIds = storeIds;
      }
      // Warehouse identifiers must never leak into the store scope. The response contract exposes
      // warehouseIds explicitly, so STORE_LIST and WAREHOUSE_LIST remain disjoint end to end.
      storeIds = List.of();
    } else {
      warehouseIds = List.of();
    }
  }

  public DataScope(String mode, List<String> storeIds) {
    this(mode, storeIds, List.of());
  }

  public static DataScope none() {
    return new DataScope(DataScopeModes.NONE, List.of(), List.of());
  }

  public static DataScope all() {
    return new DataScope(DataScopeModes.ALL, List.of(), List.of());
  }

  public boolean allowsAllStores() {
    return DataScopeModes.ALL.equals(mode);
  }

  public boolean deniesStoreAccess() {
    return DataScopeModes.NONE.equals(mode)
        || DataScopeModes.SELF.equals(mode)
        || DataScopeModes.CENTRAL_WAREHOUSE.equals(mode)
        || DataScopeModes.WAREHOUSE_LIST.equals(mode)
        || (!DataScopeModes.ALL.equals(mode) && storeIds.isEmpty());
  }

  public boolean allowsStore(String storeId) {
    return allowsAllStores()
        || (!DataScopeModes.WAREHOUSE_LIST.equals(mode)
        && storeId != null
        && !storeId.isBlank()
        && storeIds.contains(storeId.trim()));
  }

  public boolean allowsWarehouse(String warehouseId) {
    return DataScopeModes.ALL.equals(mode)
        || (DataScopeModes.WAREHOUSE_LIST.equals(mode)
        && warehouseId != null
        && !warehouseId.isBlank()
        && warehouseIds.contains(warehouseId.trim()));
  }
}
