package com.storeprofit.system.warehouse;

import java.math.BigDecimal;

public record WarehouseRequisitionLineResponse(
    Long id,
    Long itemId,
    String itemName,
    String unit,
    BigDecimal requestedQuantity,
    BigDecimal approvedQuantity,
    BigDecimal shippedQuantity,
    BigDecimal unitPrice,
    BigDecimal amount,
    String warningText,
    String note,
    BigDecimal receivedQuantity,
    BigDecimal returnedQuantity,
    BigDecimal sourceAvailableReturnQuantity,
    BigDecimal storeInventoryQuantity,
    BigDecimal availableReturnQuantity
) {
  public WarehouseRequisitionLineResponse(
      Long id,
      Long itemId,
      String itemName,
      String unit,
      BigDecimal requestedQuantity,
      BigDecimal approvedQuantity,
      BigDecimal shippedQuantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      String warningText,
      String note
  ) {
    this(
        id,
        itemId,
        itemName,
        unit,
        requestedQuantity,
        approvedQuantity,
        shippedQuantity,
        unitPrice,
        amount,
        warningText,
        note,
        null,
        null,
        null,
        null,
        null
    );
  }
}
