package com.storeprofit.system.expense;

import java.time.LocalDateTime;

public record ExpenseAttachmentResponse(
    long id,
    String fileName,
    String contentType,
    long sizeBytes,
    LocalDateTime uploadedAt,
    String previewUrl,
    String downloadUrl
) {}
