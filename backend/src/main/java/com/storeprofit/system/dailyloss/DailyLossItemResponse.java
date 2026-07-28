package com.storeprofit.system.dailyloss;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Read-only material choice. unitPrice is informational only and never accepted in create input. */
public record DailyLossItemResponse(
    long id,
    String itemCode,
    String itemName,
    String category,
    String categoryCode,
    String categoryName,
    String unit,
    String pricingUnit,
    BigDecimal quantityPerPricingUnit,
    BigDecimal unitPrice,
    boolean active,
    boolean peelSelectionEnabled,
    String defaultPeelState,
    String peeledUnit,
    String peeledPricingUnit,
    BigDecimal peeledQuantityPerPricingUnit,
    BigDecimal peeledUnitPrice,
    String unpeeledUnit,
    String unpeeledPricingUnit,
    BigDecimal unpeeledQuantityPerPricingUnit,
    BigDecimal unpeeledUnitPrice,
    BigDecimal yieldRate,
    BigDecimal grossGramsPerUnit,
    String inventoryUnit
) {
  public DailyLossItemResponse(long id, String code, String name, String category, String stockUnit,
      BigDecimal unitPrice) {
    this(id, code, name, category, category, category, stockUnit, stockUnit, BigDecimal.ONE, unitPrice, true,
        false, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  public DailyLossItemResponse(long id, String code, String name, String stockUnit, BigDecimal unitPrice) {
    this(id, code, name, null, null, null, stockUnit, stockUnit, BigDecimal.ONE, unitPrice, true,
        false, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @JsonProperty("code")
  public String code() {
    return itemCode;
  }

  @JsonProperty("name")
  public String name() {
    return itemName;
  }

  @JsonProperty("stockUnit")
  public String stockUnit() {
    return unit;
  }
}
