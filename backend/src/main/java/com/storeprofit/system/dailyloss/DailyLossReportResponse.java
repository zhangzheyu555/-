package com.storeprofit.system.dailyloss;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DailyLossReportResponse(
    String id,
    String storeId,
    String storeCode,
    String storeName,
    LocalDate lossDate,
    String month,
    String status,
    String statusLabel,
    boolean reported,
    BigDecimal totalAmount,
    BigDecimal supplierCompensationAmount,
    BigDecimal storeBorneAmount,
    int detailCount,
    int attachmentCount,
    Long submittedBy,
    String submittedByName,
    LocalDateTime submittedAt,
    Long reviewedBy,
    String reviewedByName,
    LocalDateTime reviewedAt,
    String reviewNote,
    List<DailyLossReportDetailResponse> details,
    List<DailyLossAttachmentResponse> attachments
) {
  public DailyLossReportResponse {
    details = details == null ? List.of() : List.copyOf(details);
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }
}
