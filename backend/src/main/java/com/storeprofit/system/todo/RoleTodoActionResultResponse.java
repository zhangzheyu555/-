package com.storeprofit.system.todo;

public record RoleTodoActionResultResponse(
    String todoId,
    String status,
    String actionId,
    int attachmentCount,
    String processStatus
) {
}
