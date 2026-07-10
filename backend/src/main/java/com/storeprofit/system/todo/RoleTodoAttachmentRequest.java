package com.storeprofit.system.todo;

public record RoleTodoAttachmentRequest(
    String fileName,
    String contentType,
    String dataBase64
) {
}
