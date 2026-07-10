package com.storeprofit.system.finance;

import java.math.BigDecimal;

public record ProfitTrendPoint(
    String month,
    BigDecimal income,
    BigDecimal net,
    BigDecimal margin
) {
}
