package com.storeprofit.system.todo;

import java.util.List;

public record BossTodoOwnerGroupResponse(
    String ownerName,
    int openCount,
    int riskCount,
    int pendingCount,
    String earliestDueAt,
    List<String> topSources
) {
}
