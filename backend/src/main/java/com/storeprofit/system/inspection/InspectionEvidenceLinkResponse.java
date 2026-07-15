package com.storeprofit.system.inspection;

import java.util.List;

public record InspectionEvidenceLinkResponse(
    String recordId,
    String action,
    List<Long> attachmentIds,
    List<Long> clauseIds,
    InspectionRecordResponse record
) {
  public InspectionEvidenceLinkResponse {
    attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    clauseIds = clauseIds == null ? List.of() : List.copyOf(clauseIds);
  }
}
