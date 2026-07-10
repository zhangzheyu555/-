package com.storeprofit.system.todo;

import java.util.List;

public record BusinessTodoResponse(
    String id,
    String ruleCode,
    String title,
    String summary,
    String status,
    String statusLabel,
    int priority,
    String sourceModule,
    String sourceRecordId,
    String targetRoute,
    String storeId,
    String storeName,
    String brandName,
    String month,
    String assigneeRole,
    String reviewRole,
    String createdAt,
    String updatedAt,
    String completedAt,
    boolean canTransition,
    List<BusinessTodoActionResponse> actions
) {
}
