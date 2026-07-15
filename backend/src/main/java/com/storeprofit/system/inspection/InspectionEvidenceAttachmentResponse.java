package com.storeprofit.system.inspection;

import java.util.List;

/** Safe metadata for a historic-inspection evidence candidate; never exposes image bytes. */
public record InspectionEvidenceAttachmentResponse(
    Integer photoIndex,
    Long attachmentId,
    String fileName,
    String contentType,
    String status,
    String message,
    List<Long> linkedClauseIds
) {
  public InspectionEvidenceAttachmentResponse {
    linkedClauseIds = linkedClauseIds == null ? List.of() : List.copyOf(linkedClauseIds);
  }
}
