package com.storeprofit.system.todo;

import java.util.List;

public record BossTodoRiskGroupResponse(
    String groupKey,
    String sourceModule,
    String ownerName,
    String storeName,
    String month,
    int count,
    String highestRisk,
    int highestPriority,
    String earliestDueAt,
    List<String> topStores,
    RoleTodoActionResponse action
) {
}
