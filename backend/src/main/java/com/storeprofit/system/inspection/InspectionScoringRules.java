package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Single source of truth for the inspection score scale and pass decision. */
public final class InspectionScoringRules {
  public static final BigDecimal MAX_SCORE = amount("200");
  public static final BigDecimal PASS_SCORE = amount("180");
  public static final BigDecimal LEGACY_MAX_SCORE = amount("100");

  private InspectionScoringRules() {
  }

  public static BigDecimal normalizeScore(BigDecimal score, BigDecimal sourceMaxScore) {
    BigDecimal normalizedScore = amount(score);
    BigDecimal normalizedMaximum = sourceMaxScore == null
        ? LEGACY_MAX_SCORE : amount(sourceMaxScore);
    if (normalizedMaximum.signum() <= 0) {
      normalizedMaximum = LEGACY_MAX_SCORE;
    }
    if (normalizedMaximum.compareTo(MAX_SCORE) == 0) {
      return clamp(normalizedScore);
    }
    return clamp(normalizedScore.multiply(MAX_SCORE)
        .divide(normalizedMaximum, 2, RoundingMode.HALF_UP));
  }

  public static BigDecimal normalizeCategoryScore(
      BigDecimal score,
      BigDecimal sourceMaxScore
  ) {
    if (score == null) {
      return null;
    }
    BigDecimal normalizedMaximum = sourceMaxScore == null
        ? LEGACY_MAX_SCORE : amount(sourceMaxScore);
    if (normalizedMaximum.signum() <= 0) {
      normalizedMaximum = LEGACY_MAX_SCORE;
    }
    if (normalizedMaximum.compareTo(MAX_SCORE) == 0) {
      return amount(score);
    }
    return amount(score).multiply(MAX_SCORE)
        .divide(normalizedMaximum, 2, RoundingMode.HALF_UP);
  }

  public static boolean passed(BigDecimal normalizedScore, boolean redLineHit) {
    return !redLineHit && amount(normalizedScore).compareTo(PASS_SCORE) >= 0;
  }

  public static String resultCode(BigDecimal normalizedScore, boolean redLineHit) {
    if (redLineHit) {
      return "RED_LINE_FAILED";
    }
    return passed(normalizedScore, false) ? "PASSED" : "FAILED";
  }

  public static BigDecimal amount(BigDecimal value) {
    return value == null
        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
        : value.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal amount(String value) {
    return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal clamp(BigDecimal value) {
    return amount(value).max(BigDecimal.ZERO.setScale(2)).min(MAX_SCORE);
  }
}
