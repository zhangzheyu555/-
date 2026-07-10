package com.storeprofit.system.todo;

import java.util.List;

public record RoleTodoCompletionRequest(
    String note,
    List<RoleTodoAttachmentRequest> attachments
) {
}
