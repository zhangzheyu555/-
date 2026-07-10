package com.storeprofit.system.inspection;

import java.util.Map;

public record InspectionServiceHealthResponse(
    String status,
    boolean configured,
    String healthUrl,
    String detectUrl,
    String exportUrl,
    String message,
    Map<String, Object> details
) {
}
