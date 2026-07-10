package com.storeprofit.system.inspection;

import java.math.BigDecimal;

public record InspectionStandardItemResponse(
    long id,
    String dimension,
    String code,
    String title,
    String description,
    BigDecimal suggestedScore,
    boolean redLine,
    boolean enabled,
    int sortOrder
) {
}
