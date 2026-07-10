package com.storeprofit.system.expense;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
    LocalDateTime reviewedAt
) {
}
