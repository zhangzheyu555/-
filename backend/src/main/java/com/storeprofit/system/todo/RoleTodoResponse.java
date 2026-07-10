package com.storeprofit.system.todo;

import java.util.List;

public record RoleTodoResponse(
    String roleName,
    String dataSource,
    String updatedAt,
    List<RoleTodoStatResponse> stats,
    RoleTodoAiSummaryResponse aiSummary,
    List<RoleTodoItemResponse> items
) {
}
