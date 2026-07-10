package com.storeprofit.system.todo;

public record BossTodoFocusResponse(
    int totalOpenCount,
    int needsBossActionCount,
    int roleWorkCount,
    int highRiskCount,
    int highRiskGroupCount,
    int doneReviewCount,
    String summary
) {
}
