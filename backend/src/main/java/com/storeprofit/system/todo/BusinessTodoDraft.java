package com.storeprofit.system.todo;

record BusinessTodoDraft(
    String ruleCode,
    String sourceModule,
    String sourceRecordId,
    String sourceKey,
    String title,
    String summary,
    String assigneeRole,
    String reviewRole,
    String storeId,
    String month,
    int priority,
    String metadataJson
) {
}
