package com.storeprofit.system.todo;

import java.util.List;

public record BusinessTodoActionResponse(
    String id,
    String actionType,
    String status,
    String statusLabel,
    String note,
    String actorName,
    String actorRole,
    String createdAt,
    List<BusinessTodoAttachmentResponse> attachments
) {
}
