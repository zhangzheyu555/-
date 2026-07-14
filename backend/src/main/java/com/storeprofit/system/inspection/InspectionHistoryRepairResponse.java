package com.storeprofit.system.inspection;

import java.util.List;

public record InspectionHistoryRepairResponse(
    int scanned,
    int recalculated,
    int manualReview,
    int skipped,
    List<String> manualReviewRecordIds
) {
  public InspectionHistoryRepairResponse {
    manualReviewRecordIds = manualReviewRecordIds == null
        ? List.of() : List.copyOf(manualReviewRecordIds);
  }
}
