package com.storeprofit.system.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    LocalDate expenseDate,
    BigDecimal amount,
    String category,
    String reason,
    String status,
    String imageUrl,
    Long submittedBy,
    Long reviewedBy,
    LocalDateTime reviewedAt,
    List<ExpenseAttachmentResponse> attachments,
    List<ExpenseSupplementResponse> supplements,
    int supplementAttachmentCount,
    String latestSupplementNote,
    String reviewNote
) {
  public ExpenseClaimResponse {
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
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
        status, imageUrl, submittedBy, reviewedBy, reviewedAt, null
    );
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
      LocalDateTime reviewedAt,
      LocalDate expenseDate
  ) {
    this(
        id, storeId, storeCode, storeName, brandId, brandName, month, amount, category, reason,
        status, imageUrl, submittedBy, reviewedBy, reviewedAt, expenseDate, null
    );
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
      LocalDateTime reviewedAt,
      LocalDate expenseDate,
      String reviewNote
  ) {
    this(
        id, storeId, storeCode, storeName, brandId, brandName, month, expenseDate, amount, category, reason,
        status, imageUrl, submittedBy, reviewedBy, reviewedAt, List.of(), List.of(), 0, null, reviewNote
    );
  }

  public ExpenseClaimResponse withAttachments(List<ExpenseAttachmentResponse> values) {
    return new ExpenseClaimResponse(
        id, storeId, storeCode, storeName, brandId, brandName, month, expenseDate, amount, category, reason,
        status, imageUrl, submittedBy, reviewedBy, reviewedAt, values, supplements, supplementAttachmentCount,
        latestSupplementNote, reviewNote
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
        id, storeId, storeCode, storeName, brandId, brandName, month, expenseDate, amount, category, reason,
        status, imageUrl, submittedBy, reviewedBy, reviewedAt, attachments, normalized, attachmentCount,
        latestNote, reviewNote
    );
  }
}
