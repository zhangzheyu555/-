package com.storeprofit.system.expense;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ExpenseClaimResponse(
    String id,
    String storeId,
    String storeCode,
    String storeName,
    Long brandId,
    String brandName,
    String month,
    BigDecimal amount,
    String category,
    String reason,
    String status,
    String imageUrl,
    Long submittedBy,
    Long reviewedBy,
    LocalDateTime reviewedAt,
    List<ExpenseSupplementResponse> supplements,
    int supplementAttachmentCount,
    String latestSupplementNote
) {
  public ExpenseClaimResponse {
    supplements = supplements == null ? List.of() : List.copyOf(supplements);
  }

  public ExpenseClaimResponse(
      String id,
      String storeId,
      String storeCode,
      String storeName,
      Long brandId,
      String brandName,
      String month,
      BigDecimal amount,
      String category,
      String reason,
      String status,
      String imageUrl,
      Long submittedBy,
      Long reviewedBy,
      LocalDateTime reviewedAt
  ) {
    this(
        id, storeId, storeCode, storeName, brandId, brandName, month, amount, category, reason,
        status, imageUrl, submittedBy, reviewedBy, reviewedAt, List.of(), 0, null
    );
  }

  public ExpenseClaimResponse withSupplements(List<ExpenseSupplementResponse> values) {
    List<ExpenseSupplementResponse> normalized = values == null ? List.of() : List.copyOf(values);
    int attachmentCount = normalized.stream().mapToInt(item -> item.attachments().size()).sum();
    String latestNote = normalized.stream()
        .map(ExpenseSupplementResponse::note)
        .filter(note -> note != null && !note.isBlank())
        .findFirst()
        .orElse(null);
    return new ExpenseClaimResponse(
        id, storeId, storeCode, storeName, brandId, brandName, month, amount, category, reason,
        status, imageUrl, submittedBy, reviewedBy, reviewedAt, normalized, attachmentCount, latestNote
    );
  }
}
