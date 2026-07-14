package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InspectionStandardSnapshot(
    Long standardId,
    String standardVersion,
    String dimension,
    String standardTitle,
    String standardDescription,
    BigDecimal suggestedScore,
    BigDecimal actualDeductionScore,
    boolean redLine,
    String problemDescription,
    int sortOrder,
    String standardCode,
    String checkMethod,
    BigDecimal actualScore,
    String riskLevel,
    List<Long> photoAttachmentIds,
    String responsiblePerson,
    LocalDate rectificationDeadline,
    String rectificationStatus,
    String reviewResult,
    List<Long> beforePhotoAttachmentIds,
    List<Long> afterPhotoAttachmentIds
) {
  public InspectionStandardSnapshot {
    photoAttachmentIds = photoAttachmentIds == null ? List.of() : List.copyOf(photoAttachmentIds);
    beforePhotoAttachmentIds = beforePhotoAttachmentIds == null ? List.of() : List.copyOf(beforePhotoAttachmentIds);
    afterPhotoAttachmentIds = afterPhotoAttachmentIds == null ? List.of() : List.copyOf(afterPhotoAttachmentIds);
  }

  public InspectionStandardSnapshot(
      Long standardId,
      String standardVersion,
      String dimension,
      String standardTitle,
      String standardDescription,
      BigDecimal suggestedScore,
      BigDecimal actualDeductionScore,
      boolean redLine,
      String problemDescription,
      int sortOrder
  ) {
    this(standardId, standardVersion, dimension, standardTitle, standardDescription,
        suggestedScore, actualDeductionScore, redLine, problemDescription, sortOrder,
        null, null,
        suggestedScore == null || actualDeductionScore == null
            ? BigDecimal.ZERO
            : suggestedScore.subtract(actualDeductionScore).max(BigDecimal.ZERO),
        redLine ? "RED" : "NORMAL", List.of(), null, null, null, null, List.of(), List.of());
  }
}
