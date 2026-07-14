package com.storeprofit.system.expense;

import java.time.LocalDateTime;
import java.util.List;

public record ExpenseSupplementResponse(
    long id,
    String note,
    Long submittedBy,
    String submittedByName,
    LocalDateTime submittedAt,
    List<ExpenseSupplementAttachmentResponse> attachments
) {
  public ExpenseSupplementResponse {
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }
}
