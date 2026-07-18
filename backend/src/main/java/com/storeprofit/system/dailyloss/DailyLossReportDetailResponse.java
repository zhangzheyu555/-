package com.storeprofit.system.dailyloss;

import java.math.BigDecimal;

public record DailyLossReportDetailResponse(
    String id,
    Long itemConfigId,
    String itemCode,
    String itemName,
    String category,
    String unit,
    BigDecimal lossQuantity,
    BigDecimal unitPriceSnapshot,
    BigDecimal amountSnapshot,
    String lossReason
) {
}
