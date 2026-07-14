package com.storeprofit.system.inspection;

import java.util.Map;

/** Raw recognition evidence only; final clause and deduction are recomputed by the server. */
public record InspectionDraftDetectionConfirmRequest(Map<String, Object> evidence) {
  public InspectionDraftDetectionConfirmRequest {
    evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
  }
}
