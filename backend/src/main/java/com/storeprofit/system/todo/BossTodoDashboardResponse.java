package com.storeprofit.system.todo;

import java.util.List;

public record BossTodoDashboardResponse(
    String roleName,
    String dataSource,
    String updatedAt,
    BossTodoFocusResponse todayFocus,
    List<RoleTodoItemResponse> needsBossAction,
    List<BossTodoRiskGroupResponse> highRiskReminders,
    List<BossTodoOwnerGroupResponse> roleProgress,
    List<RoleTodoItemResponse> doneReview
) {
}
