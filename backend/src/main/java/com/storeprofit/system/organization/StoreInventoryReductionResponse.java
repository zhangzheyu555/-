package com.storeprofit.system.organization;

import java.math.BigDecimal;
import java.util.List;

public record StoreInventoryReductionResponse(
    String storeId,
    String storeName,
    String month,
    long movementCount,
    int itemCount,
    boolean truncated,
    List<ReductionRow> rows
) {
  public record ReductionRow(
      long id,
      long itemId,
      String itemCode,
      String itemName,
      String unit,
      BigDecimal quantityReduced,
      BigDecimal currentQuantity,
      String sourceType,
      String sourceLabel,
      String sourceId,
      String note,
      String operatorName,
      String createdAt
  ) {
  }
}
