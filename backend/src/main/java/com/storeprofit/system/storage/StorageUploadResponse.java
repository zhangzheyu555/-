package com.storeprofit.system.storage;

public record StorageUploadResponse(
    Long id,
    String fileName,
    String contentType,
    long fileSize,
    String url,
    String storagePath
) {
}
