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
    BigDecimal unitPrice,
    boolean active
) {
  public DailyLossItemResponse(long id, String code, String name, String category, String stockUnit,
      BigDecimal unitPrice) {
    this(id, code, name, category, category, category, stockUnit, unitPrice, true);
  }

  public DailyLossItemResponse(long id, String code, String name, String stockUnit, BigDecimal unitPrice) {
    this(id, code, name, null, null, null, stockUnit, unitPrice, true);
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
