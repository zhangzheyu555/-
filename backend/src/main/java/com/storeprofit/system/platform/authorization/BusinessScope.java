package com.storeprofit.system.platform.authorization;

/**
 * A resolved business scope. Store managers always receive a concrete store and brand; broader
 * roles may receive null values when they intentionally query all authorized stores.
 */
public record BusinessScope(
    String storeId,
    String storeName,
    Long brandId,
    String brandName,
    DataScope dataScope
) {
  public BusinessScope {
    storeId = blankToNull(storeId);
    storeName = blankToNull(storeName);
    brandName = blankToNull(brandName);
    dataScope = dataScope == null ? DataScope.none() : dataScope;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
