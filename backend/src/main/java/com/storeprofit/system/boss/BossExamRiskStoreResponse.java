package com.storeprofit.system.boss;

import java.math.BigDecimal;
import java.util.List;

public record BossExamRiskStoreResponse(
    String storeId,
    String storeName,
    Integer assignedCount,
    Integer completedCount,
    BigDecimal completionRate,
    Integer passedCount,
    BigDecimal passRate,
    Integer overdueCount,
    BigDecimal averageScore,
    List<String> risks
) {
}
