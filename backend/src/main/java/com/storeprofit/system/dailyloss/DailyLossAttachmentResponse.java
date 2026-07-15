package com.storeprofit.system.dailyloss;

public record DailyLossAttachmentResponse(
    long id,
    String fileName,
    String contentType,
    long fileSize,
    String downloadUrl
) {
}
