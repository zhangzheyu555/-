package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InspectionItemResultResponse(
    Long snapshotId,
    Long standardItemId,
    String dimension,
    String code,
    String title,
    String description,
    String checkMethod,
    BigDecimal standardScore,
    BigDecimal actualScore,
    BigDecimal deductionScore,
    boolean issueFound,
    String riskLevel,
    boolean redLine,
    String deductionReason,
    List<Long> photoAttachmentIds,
    String responsiblePerson,
    LocalDate rectificationDeadline,
    String rectificationStatus,
    String reviewResult,
    List<Long> beforePhotoAttachmentIds,
    List<Long> afterPhotoAttachmentIds,
    int sortOrder
) {
  public InspectionItemResultResponse {
    photoAttachmentIds = photoAttachmentIds == null ? List.of() : List.copyOf(photoAttachmentIds);
    beforePhotoAttachmentIds = beforePhotoAttachmentIds == null ? List.of() : List.copyOf(beforePhotoAttachmentIds);
    afterPhotoAttachmentIds = afterPhotoAttachmentIds == null ? List.of() : List.copyOf(afterPhotoAttachmentIds);
  }
}
