package com.storeprofit.system.finance;

import java.math.BigDecimal;

public record ProfitSummaryResponse(
    String month,
    int storeCount,
    int entryCount,
    BigDecimal sales,
    BigDecimal income,
    BigDecimal costSum,
    BigDecimal expenseSum,
    BigDecimal net,
    BigDecimal margin,
    int riskStoreCount
) {
}
