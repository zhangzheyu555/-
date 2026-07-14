package com.storeprofit.system.finance;

import java.math.BigDecimal;

public record ProfitTrendPoint(
    String month,
    BigDecimal sales,
    BigDecimal income,
    BigDecimal net,
    BigDecimal margin
) {
  /** Compatibility constructor retained for callers using the previous trend shape. */
  public ProfitTrendPoint(String month, BigDecimal income, BigDecimal net, BigDecimal margin) {
    this(month, income, income, net, margin);
  }
}
