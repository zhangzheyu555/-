package com.storeprofit.system.todo;

public record BusinessTodoAttachmentRequest(
    String fileName,
    String contentType,
    String dataBase64
) {
}
