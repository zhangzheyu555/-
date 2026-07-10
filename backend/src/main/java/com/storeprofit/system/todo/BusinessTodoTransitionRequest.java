package com.storeprofit.system.todo;

import java.util.List;

public record BusinessTodoTransitionRequest(
    String status,
    String note,
    List<BusinessTodoAttachmentRequest> attachments
) {
}
