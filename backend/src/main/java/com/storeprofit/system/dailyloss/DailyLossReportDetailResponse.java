package com.storeprofit.system.dailyloss;

import java.math.BigDecimal;

public record DailyLossReportDetailResponse(
    String id,
    Long itemConfigId,
    String itemCode,
    String itemName,
    String category,
    String unit,
    String pricingUnit,
    BigDecimal quantityPerPricingUnit,
    BigDecimal lossQuantity,
    BigDecimal pricedQuantity,
    BigDecimal unitPriceSnapshot,
    BigDecimal amountSnapshot,
    String lossReason
) {
}
