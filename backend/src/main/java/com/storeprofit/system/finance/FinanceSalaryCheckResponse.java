package com.storeprofit.system.finance;

import java.math.BigDecimal;

public record FinanceSalaryCheckResponse(
    String id,
    String employeeName,
    String storeId,
    String storeName,
    String month,
    BigDecimal gross,
    String anomaly,
    String status
) {
}
