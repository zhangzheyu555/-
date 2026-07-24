package com.storeprofit.system.dailyloss;

import java.math.BigDecimal;

public record DailyLossMonthlyArchiveResponse(
    String month,
    String sourceSheet,
    String sourceTitle,
    BigDecimal declaredTotalLossAmount,
    BigDecimal detailTotalLossAmount,
    BigDecimal supplierCompensationAmount,
    BigDecimal declaredStoreBorneAmount,
    BigDecimal calculatedStoreBorneAmount,
    BigDecimal declaredBorneDifference,
    BigDecimal detailLossDifference,
    int storeCount,
    int itemCount,
    String reconciliationStatus,
    String sourceNote
) {}
