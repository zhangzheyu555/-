package com.storeprofit.system.expense;

import java.time.LocalDateTime;

public record ExpenseSupplementAttachmentResponse(
    long id,
    String fileName,
    String contentType,
    long fileSize,
    Long uploadedBy,
    LocalDateTime uploadedAt,
    String previewUrl,
    String downloadUrl
) {
}
