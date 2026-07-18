package com.storeprofit.system.dailyloss;

public record DailyLossPhotoExportFile(
    long id,
    String storeCode,
    String lossDate,
    String fileName,
    String contentType,
    byte[] content
) {
}
