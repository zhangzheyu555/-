package com.storeprofit.system.inspection;

import java.util.Map;

public record InspectionDetectionDecisionResponse(
    InspectionRecordResponse record,
    Map<String, Object> detection,
    boolean changed
) {
  public InspectionDetectionDecisionResponse {
    detection = detection == null ? Map.of() : Map.copyOf(detection);
  }
}
