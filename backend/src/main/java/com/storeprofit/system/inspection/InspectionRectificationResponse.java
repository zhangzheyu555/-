package com.storeprofit.system.inspection;

import java.util.List;

public record InspectionRectificationResponse(
    String recordId,
    String storeId,
    String storeName,
    String inspectionDate,
    String status,
    String statusLabel,
    String requirement,
    List<Long> evidenceAttachmentIds,
    String managerNote,
    String reviewNote,
    String updatedAt
) {
  public InspectionRectificationResponse {
    evidenceAttachmentIds = evidenceAttachmentIds == null ? List.of() : List.copyOf(evidenceAttachmentIds);
  }
}
