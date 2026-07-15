package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates immutable inspection snapshots before an historic record is exported or repaired.
 *
 * <p>This class is deliberately stateless. It must never fall back to the current standard when
 * a record's own score evidence is incomplete.</p>
 */
final class InspectionScoreSnapshotValidator {
  private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private InspectionScoreSnapshotValidator() {}

  static List<String> missingEvidence(InspectionRecordRepository.ScoreEvidence evidence) {
    List<String> missing = new ArrayList<>();
    if (evidence.snapshotCount() <= 0) {
      missing.add("标准快照");
    }
    if (evidence.snapshotCount() != evidence.snapshotStandardIdCount()) {
      missing.add("标准快照条款ID");
    }
    if (evidence.snapshotVersionCount() != 1) {
      missing.add("标准快照版本");
    }
    if (evidence.standardVersionId() != null
        && evidence.snapshotStandardVersionId() != null
        && !evidence.standardVersionId().equals(evidence.snapshotStandardVersionId())) {
      missing.add("标准版本与快照版本一致性");
    }
    return missing;
  }

  static Long resolveVersionId(
      InspectionRecordRepository.ScoreEvidence evidence,
      List<String> missingFields
  ) {
    if (evidence.standardVersionId() != null) {
      return evidence.standardVersionId();
    }
    if (evidence.snapshotStandardVersionId() != null && evidence.snapshotVersionCount() == 1) {
      return evidence.snapshotStandardVersionId();
    }
    missingFields.add("标准版本");
    return null;
  }

  static boolean hasCompleteStoredScore(InspectionRecordRepository.ScoreEvidence evidence) {
    return evidence.fullScore() != null
        && evidence.passScore() != null
        && evidence.score() != null
        && evidence.standardVersionId() != null
        && evidence.fullScore().signum() > 0
        && evidence.passScore().signum() > 0
        && evidence.score().signum() >= 0
        && evidence.score().compareTo(evidence.fullScore()) <= 0;
  }

  static void validateStoredScore(
      InspectionRecordRepository.ScoreEvidence evidence,
      InspectionStandardRepository.VersionRow version,
      ScoreRepair snapshotScore,
      List<String> missingFields
  ) {
    if (!Objects.equals(blankToNull(evidence.standardVersion()), blankToNull(version.version()))) {
      missingFields.add("标准版本与版本编号一致性");
    }
    if (evidence.fullScore().compareTo(version.fullScore()) != 0) {
      missingFields.add("满分与标准版本一致性");
    }
    if (evidence.passScore().compareTo(version.passScore()) != 0) {
      missingFields.add("合格线与标准版本一致性");
    }
    if (evidence.score().compareTo(snapshotScore.score()) != 0) {
      missingFields.add("最终得分与标准快照一致性");
    }
    if (evidence.passed() == null || evidence.passed() != snapshotScore.passed()
        || !Objects.equals(blankToNull(evidence.resultCode()), blankToNull(snapshotScore.resultCode()))) {
      missingFields.add("巡检结论与红线快照一致性");
    }
  }

  static ScoreRepair calculate(
      List<InspectionStandardItemResponse> standards,
      List<InspectionItemResultResponse> snapshots,
      InspectionStandardRepository.VersionRow version,
      List<String> missingFields
  ) {
    if (snapshots.size() != standards.size()) {
      missingFields.add("标准快照条款数量");
      return ScoreRepair.empty();
    }
    Map<String, InspectionItemResultResponse> snapshotByCode = new HashMap<>();
    for (InspectionItemResultResponse snapshot : snapshots) {
      String code = blankToNull(snapshot.code());
      if (code == null || snapshotByCode.put(code, snapshot) != null) {
        missingFields.add("标准快照条款编号");
        return ScoreRepair.empty();
      }
    }
    BigDecimal score = ZERO_AMOUNT;
    BigDecimal material = ZERO_AMOUNT;
    BigDecimal hygiene = ZERO_AMOUNT;
    BigDecimal service = ZERO_AMOUNT;
    boolean redLineHit = false;
    for (InspectionStandardItemResponse standard : standards) {
      InspectionItemResultResponse snapshot = snapshotByCode.remove(standard.code());
      if (snapshot == null) {
        missingFields.add("标准快照条款：" + standard.code());
        return ScoreRepair.empty();
      }
      BigDecimal maximum = standard.suggestedScore();
      BigDecimal snapshotMaximum = snapshot.standardScore();
      BigDecimal deduction = snapshot.deductionScore();
      BigDecimal snapshotActual = snapshot.actualScore();
      if (maximum == null || maximum.signum() < 0) {
        missingFields.add("标准条款分值：" + standard.code());
        return ScoreRepair.empty();
      }
      if (snapshotMaximum == null
          || snapshotMaximum.signum() < 0
          || snapshotMaximum.compareTo(maximum) != 0) {
        missingFields.add("标准快照分值一致性：" + standard.code());
        return ScoreRepair.empty();
      }
      if (deduction == null || deduction.signum() < 0 || deduction.compareTo(maximum) > 0) {
        missingFields.add("条款扣分：" + standard.code());
        return ScoreRepair.empty();
      }
      if (deduction.signum() > 0 && !snapshot.issueFound()) {
        missingFields.add("扣分问题状态：" + standard.code());
        return ScoreRepair.empty();
      }
      if (deduction.signum() > 0 && blankToNull(snapshot.deductionReason()) == null) {
        missingFields.add("扣分原因：" + standard.code());
        return ScoreRepair.empty();
      }
      BigDecimal actual = maximum.subtract(deduction).setScale(2, RoundingMode.HALF_UP);
      if (snapshotActual == null || snapshotActual.compareTo(actual) != 0) {
        missingFields.add("标准快照分值一致性：" + standard.code());
        return ScoreRepair.empty();
      }
      String bucket = InspectionStandardValidator.category(standard.dimension());
      if (bucket == null) {
        missingFields.add("条款分类：" + standard.code());
        return ScoreRepair.empty();
      }
      score = score.add(actual);
      switch (bucket) {
        case "MATERIAL" -> material = material.add(actual);
        case "HYGIENE" -> hygiene = hygiene.add(actual);
        case "SERVICE" -> service = service.add(actual);
        default -> throw new IllegalStateException("Unexpected inspection category: " + bucket);
      }
      redLineHit = redLineHit || (snapshot.issueFound()
          && "RED".equals(normalizeRiskLevel(standard.riskLevel(), standard.redLine())));
    }
    if (!snapshotByCode.isEmpty() || score.compareTo(version.fullScore()) > 0) {
      missingFields.add("标准快照与标准版本一致性");
      return ScoreRepair.empty();
    }
    String resultCode = redLineHit ? "RED_LINE_FAILED"
        : score.compareTo(version.passScore()) >= 0 ? "PASSED" : "FAILED";
    return new ScoreRepair(
        score.setScale(2, RoundingMode.HALF_UP),
        material.setScale(2, RoundingMode.HALF_UP),
        hygiene.setScale(2, RoundingMode.HALF_UP),
        service.setScale(2, RoundingMode.HALF_UP),
        "PASSED".equals(resultCode),
        resultCode,
        "依据记录绑定的标准版本 " + version.version() + " 及完整快照重新计算；未修改原始巡检记录"
    );
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

  record ScoreRepair(
      BigDecimal score,
      BigDecimal materialScore,
      BigDecimal hygieneScore,
      BigDecimal serviceScore,
      boolean passed,
      String resultCode,
      String reason
  ) {
    private static ScoreRepair empty() {
      return new ScoreRepair(null, null, null, null, false, null, null);
    }
  }
}
