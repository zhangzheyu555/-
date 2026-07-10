package com.storeprofit.system.boss;

import java.math.BigDecimal;
import java.util.List;

public record BossExamSummaryResponse(
    Integer activeExamCount,
    Integer assignedCount,
    Integer completedCount,
    BigDecimal completionRate,
    Integer passedCount,
    BigDecimal passRate,
    Integer overdueCount,
    BigDecimal averageScore,
    List<BossExamRiskStoreResponse> riskStores
) {
}
