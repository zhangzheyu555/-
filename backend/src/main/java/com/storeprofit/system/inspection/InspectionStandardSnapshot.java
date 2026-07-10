package com.storeprofit.system.inspection;

import java.math.BigDecimal;

public record InspectionStandardSnapshot(
    Long standardId,
    String standardVersion,
    String dimension,
    String standardTitle,
    String standardDescription,
    BigDecimal suggestedScore,
    BigDecimal actualDeductionScore,
    boolean redLine,
    String problemDescription,
    int sortOrder
) {
}
