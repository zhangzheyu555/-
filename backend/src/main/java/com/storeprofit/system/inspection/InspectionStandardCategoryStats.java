package com.storeprofit.system.inspection;

import java.math.BigDecimal;

public record InspectionStandardCategoryStats(
    String categoryCode,
    String categoryName,
    int expectedCount,
    int actualCount,
    BigDecimal expectedScore,
    BigDecimal actualScore,
    boolean valid
) {
}
