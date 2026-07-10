package com.storeprofit.system.todo;

public record BusinessTodoAttachmentResponse(
    String id,
    String fileName,
    String contentType,
    long sizeBytes,
    String downloadUrl
) {
}
