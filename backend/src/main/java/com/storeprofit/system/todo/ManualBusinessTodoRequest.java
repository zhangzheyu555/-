package com.storeprofit.system.todo;

public record ManualBusinessTodoRequest(
    String title,
    String summary,
    String storeId,
    String month,
    String assigneeRole,
    String dueAt,
    String sourceModule,
    String sourceRecordId,
    String expectedImpact,
    String verificationMetric,
    Boolean confirmed
) {
}
