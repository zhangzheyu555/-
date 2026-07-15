package com.storeprofit.system.dailyloss;

import java.math.BigDecimal;

/** Read-only material choice. unitPrice is informational only and never accepted in create input. */
public record DailyLossItemResponse(
    long id,
    String code,
    String name,
    String stockUnit,
    BigDecimal unitPrice
) {
}
