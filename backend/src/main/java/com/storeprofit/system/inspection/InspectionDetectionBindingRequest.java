package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record InspectionDetectionBindingRequest(
    String inspector,
    String brand,
    BigDecimal fullScore,
    List<Map<String, Object>> results,
    String note
) {
}
