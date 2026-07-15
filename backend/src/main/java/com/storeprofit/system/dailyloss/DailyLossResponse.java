package com.storeprofit.system.dailyloss;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DailyLossResponse(
    String id,
    String storeId,
    String storeCode,
    String storeName,
    LocalDate lossDate,
    long itemId,
    String itemCode,
    String itemName,
    String stockUnit,
    BigDecimal lossQuantity,
    BigDecimal unitPriceSnapshot,
    BigDecimal amountSnapshot,
    String lossReason,
    String status,
    Long submittedBy,
    String submittedByName,
    LocalDateTime submittedAt,
    Long reviewedBy,
    String reviewedByName,
    LocalDateTime reviewedAt,
    String reviewNote,
    boolean inventoryDeducted,
    List<DailyLossAttachmentResponse> attachments
) {
}
