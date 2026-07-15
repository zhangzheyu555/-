package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recalculates legacy inspection scores only when the complete historic snapshot proves a safe
 * one-to-one mapping. Any ambiguity is returned for manual review instead of guessed.
 */
final class InspectionHistoricalRepairCalculator {
  private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private InspectionHistoricalRepairCalculator() {}

  static Calculation calculate(
      List<InspectionStandardItemResponse> standards,
      List<InspectionItemResultResponse> snapshots,
      InspectionStandardRepository.VersionRow active
  ) {
    if (snapshots.size() != standards.size()) {
      return Calculation.manual(
          "原巡检条款快照不完整：应为" + standards.size() + "条，实际为" + snapshots.size() + "条");
    }
    Map<String, InspectionItemResultResponse> snapshotByCode = new HashMap<>();
    for (InspectionItemResultResponse snapshot : snapshots) {
      String code = blankToNull(snapshot.code());
      if (code == null || snapshotByCode.put(code, snapshot) != null) {
        return Calculation.manual("原巡检条款快照缺少唯一条款编号，禁止猜测匹配关系");
      }
    }
    BigDecimal score = ZERO_AMOUNT;
    BigDecimal materialScore = ZERO_AMOUNT;
    BigDecimal hygieneScore = ZERO_AMOUNT;
    BigDecimal serviceScore = ZERO_AMOUNT;
    boolean redLineHit = false;
    for (InspectionStandardItemResponse standard : standards) {
      InspectionItemResultResponse snapshot = snapshotByCode.remove(standard.code());
      if (snapshot == null) {
        return Calculation.manual("原巡检快照缺少修正版条款：" + standard.code());
      }
      BigDecimal maximum = amountOrZero(standard.suggestedScore());
      BigDecimal deduction = amountOrZero(snapshot.deductionScore());
      if (deduction.signum() < 0) {
        return Calculation.manual("原巡检快照存在负扣分：" + standard.code());
      }
      BigDecimal actual = maximum.subtract(deduction).max(BigDecimal.ZERO)
          .setScale(2, RoundingMode.HALF_UP);
      score = score.add(actual);
      String category = InspectionStandardValidator.category(standard.dimension());
      if (category == null) {
        return Calculation.manual("修正版条款分类无法识别，禁止自动重算：" + standard.code());
      }
      switch (category) {
        case "MATERIAL" -> materialScore = materialScore.add(actual);
        case "HYGIENE" -> hygieneScore = hygieneScore.add(actual);
        case "SERVICE" -> serviceScore = serviceScore.add(actual);
        default -> throw new IllegalStateException("Unexpected inspection category: " + category);
      }
      redLineHit = redLineHit || (snapshot.issueFound()
          && "RED".equals(normalizeRiskLevel(standard.riskLevel(), standard.redLine())));
    }
    if (!snapshotByCode.isEmpty()) {
      return Calculation.manual("原巡检快照包含修正版中不存在的条款，禁止自动重算");
    }
    score = score.setScale(2, RoundingMode.HALF_UP);
    String resultCode = InspectionScoringRules.resultCode(score, redLineHit);
    return Calculation.recalculated(
        score,
        materialScore.setScale(2, RoundingMode.HALF_UP),
        hygieneScore.setScale(2, RoundingMode.HALF_UP),
        serviceScore.setScale(2, RoundingMode.HALF_UP),
        resultCode,
        "依据修正版" + active.version() + "及105条完整历史快照重新计算"
    );
  }

  private static BigDecimal amountOrZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String normalizeRiskLevel(String value, boolean redLine) {
    if (redLine) {
      return "RED";
    }
    String normalized = value == null ? "NORMAL" : value.trim().toUpperCase();
    return switch (normalized) {
      case "RED", "YELLOW" -> normalized;
      default -> "NORMAL";
    };
  }

  record Calculation(
      boolean manualReview,
      BigDecimal score,
      BigDecimal materialScore,
      BigDecimal hygieneScore,
      BigDecimal serviceScore,
      String resultCode,
      String reason
  ) {
    private static Calculation manual(String reason) {
      return new Calculation(true, null, null, null, null, null, reason);
    }

    private static Calculation recalculated(
        BigDecimal score,
        BigDecimal materialScore,
        BigDecimal hygieneScore,
        BigDecimal serviceScore,
        String resultCode,
        String reason
    ) {
      return new Calculation(false, score, materialScore, hygieneScore, serviceScore, resultCode, reason);
    }
  }
}
