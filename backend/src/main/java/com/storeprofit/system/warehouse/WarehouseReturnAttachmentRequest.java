package com.storeprofit.system.warehouse;

public record WarehouseReturnAttachmentRequest(
    String fileName,
    String contentType,
    String dataBase64
) {
}
