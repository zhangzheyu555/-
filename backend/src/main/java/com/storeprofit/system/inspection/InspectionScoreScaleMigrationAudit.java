package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record InspectionScoreScaleMigrationAudit(
    long id,
    String migrationKey,
    BigDecimal originalFullScore,
    BigDecimal originalPassScore,
    BigDecimal originalScore,
    BigDecimal originalMaterialScore,
    BigDecimal originalHygieneScore,
    BigDecimal originalServiceScore,
    boolean originalPassed,
    String originalResultCode,
    BigDecimal convertedFullScore,
    BigDecimal convertedPassScore,
    BigDecimal convertedScore,
    BigDecimal convertedMaterialScore,
    BigDecimal convertedHygieneScore,
    BigDecimal convertedServiceScore,
    boolean convertedPassed,
    String convertedResultCode,
    LocalDateTime migratedAt
) {
  static final String HUNDRED_TO_TWO_HUNDRED = "V41_100_TO_200";

  String status() {
    return HUNDRED_TO_TWO_HUNDRED.equals(migrationKey)
        ? "SCORE_SCALE_MIGRATED" : "RESULT_RECALCULATED";
  }

  String reason() {
    return HUNDRED_TO_TWO_HUNDRED.equals(migrationKey)
        ? "历史100分制巡检已按原分数两倍迁移为200分制"
        : "历史200分制巡检已按180分合格线重新判定";
  }
}
