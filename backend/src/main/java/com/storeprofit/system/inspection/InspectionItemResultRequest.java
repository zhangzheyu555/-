package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InspectionItemResultRequest(
    Long standardItemId,
    BigDecimal actualScore,
    Boolean issueFound,
    String deductionReason,
    List<Long> photoAttachmentIds,
    String responsiblePerson,
    LocalDate rectificationDeadline,
    String rectificationStatus,
    String reviewResult,
    List<Long> beforePhotoAttachmentIds,
    List<Long> afterPhotoAttachmentIds
) {
  public InspectionItemResultRequest {
    photoAttachmentIds = photoAttachmentIds == null ? List.of() : List.copyOf(photoAttachmentIds);
    beforePhotoAttachmentIds = beforePhotoAttachmentIds == null ? List.of() : List.copyOf(beforePhotoAttachmentIds);
    afterPhotoAttachmentIds = afterPhotoAttachmentIds == null ? List.of() : List.copyOf(afterPhotoAttachmentIds);
  }
}
