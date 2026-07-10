package com.storeprofit.system.salary;

import java.math.BigDecimal;

public record SalarySummaryResponse(
    String month,
    int storeCount,
    int recordCount,
    BigDecimal grossTotal,
    BigDecimal baseTotal,
    BigDecimal commissionTotal,
    BigDecimal overtimeTotal
) {
}
