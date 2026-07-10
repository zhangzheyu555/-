package com.storeprofit.system.todo;

public record RoleTodoItemResponse(
    String id,
    String title,
    String summary,
    String status,
    int priority,
    String brandName,
    String storeId,
    String storeName,
    String month,
    String ownerName,
    String dueAt,
    String sourceModule,
    String sourceRecordId,
    String processStatus,
    boolean escalatedToBoss,
    String dataSource,
    String updatedAt,
    String occurredAt,
    RoleTodoActionResponse action
) {
}
