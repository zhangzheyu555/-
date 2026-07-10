package com.storeprofit.system.todo;

public record RoleTodoAiSummaryResponse(
    String source,
    String text,
    String fallbackReason
) {
}
