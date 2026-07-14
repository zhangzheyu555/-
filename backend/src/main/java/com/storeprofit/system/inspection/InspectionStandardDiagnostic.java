package com.storeprofit.system.inspection;

import java.math.BigDecimal;

public record InspectionStandardDiagnostic(
    String categoryCode,
    String categoryName,
    Integer expectedCount,
    Integer actualCount,
    BigDecimal expectedScore,
    BigDecimal actualScore,
    String message
) {
}
