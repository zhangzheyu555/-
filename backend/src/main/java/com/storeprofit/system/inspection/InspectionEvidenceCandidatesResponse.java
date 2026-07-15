package com.storeprofit.system.inspection;

import java.util.List;

public record InspectionEvidenceCandidatesResponse(
    String recordId,
    String storeId,
    List<InspectionEvidenceAttachmentResponse> candidates
) {
  public InspectionEvidenceCandidatesResponse {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }
}
