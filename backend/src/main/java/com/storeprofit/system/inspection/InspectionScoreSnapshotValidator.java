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
  private static final String SCALE_ADJUSTMENT_CLAUSE_CODE = "H-4.1.2";
  private static final String SCALE_ADJUSTMENT_POLICY = "LEGACY_100_TO_200_H412_V1";
  private static final BigDecimal LEGACY_SCORE_SCALE = amount("100");
  private static final BigDecimal PERSISTED_SCORE_SCALE = amount("200");
  private static final BigDecimal SCALE_ADJUSTMENT_CLAUSE_DEDUCTION = amount("2");
  private static final BigDecimal SCALE_ADJUSTMENT_DEDUCTION = amount("2");
  private static final BigDecimal SCALE_ADJUSTMENT_TOTAL_DEDUCTION = amount("4");

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
      String photosJson,
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
      if (deduction == null || deduction.signum() < 0) {
        missingFields.add("条款扣分：" + standard.code());
        return ScoreRepair.empty();
      }
      ScaleAdjustmentEvidence scaleAdjustment = null;
      if (deduction.compareTo(maximum) > 0) {
        scaleAdjustment = confirmedScaleAdjustment(
            standard, snapshot, deduction, photosJson);
        if (scaleAdjustment == null) {
          missingFields.add("条款扣分：" + standard.code());
          return ScoreRepair.empty();
        }
      }
      if (deduction.signum() > 0 && !snapshot.issueFound()) {
        missingFields.add("扣分问题状态：" + standard.code());
        return ScoreRepair.empty();
      }
      if (deduction.signum() > 0 && blankToNull(snapshot.deductionReason()) == null) {
        missingFields.add("扣分原因：" + standard.code());
        return ScoreRepair.empty();
      }
      BigDecimal actual = scaleAdjustment == null
          ? maximum.subtract(deduction).setScale(2, RoundingMode.HALF_UP)
          : maximum.subtract(scaleAdjustment.clauseDeduction())
              .max(ZERO_AMOUNT).setScale(2, RoundingMode.HALF_UP);
      if (snapshotActual == null || snapshotActual.compareTo(actual) != 0) {
        missingFields.add("标准快照分值一致性：" + standard.code());
        return ScoreRepair.empty();
      }
      BigDecimal scoreContribution = scaleAdjustment == null
          ? actual
          : actual.subtract(scaleAdjustment.scaleAdjustmentDeduction())
              .setScale(2, RoundingMode.HALF_UP);
      String bucket = InspectionStandardValidator.category(standard.dimension());
      if (bucket == null) {
        missingFields.add("条款分类：" + standard.code());
        return ScoreRepair.empty();
      }
      switch (bucket) {
        case "MATERIAL" -> material = material.add(scoreContribution);
        case "HYGIENE" -> hygiene = hygiene.add(scoreContribution);
        case "SERVICE" -> service = service.add(scoreContribution);
        default -> throw new IllegalStateException("Unexpected inspection category: " + bucket);
      }
      redLineHit = redLineHit || (snapshot.issueFound()
          && "RED".equals(normalizeRiskLevel(standard.riskLevel(), standard.redLine())));
    }
    material = material.max(ZERO_AMOUNT);
    hygiene = hygiene.max(ZERO_AMOUNT);
    service = service.max(ZERO_AMOUNT);
    BigDecimal score = material.add(hygiene).add(service).setScale(2, RoundingMode.HALF_UP);
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

  /**
   * Returns the only persisted scale adjustment that is allowed to exceed a clause's own score.
   *
   * <p>The detection payload is authorization evidence, so no deduction reason text or client
   * policy flag is trusted. Every server-derived field, the terminal decision, the clause identity,
   * and both attachment links must agree with the immutable snapshot.</p>
   */
  private static ScaleAdjustmentEvidence confirmedScaleAdjustment(
      InspectionStandardItemResponse standard,
      InspectionItemResultResponse snapshot,
      BigDecimal deduction,
      String photosJson
  ) {
    if (!SCALE_ADJUSTMENT_CLAUSE_CODE.equalsIgnoreCase(standard.code())
        || !SCALE_ADJUSTMENT_CLAUSE_CODE.equalsIgnoreCase(snapshot.code())
        || !Objects.equals(standard.id(), snapshot.standardItemId())
        || standard.suggestedScore() == null
        || standard.suggestedScore().compareTo(SCALE_ADJUSTMENT_CLAUSE_DEDUCTION) != 0
        || deduction.compareTo(SCALE_ADJUSTMENT_TOTAL_DEDUCTION) != 0) {
      return null;
    }

    List<Map<String, Object>> photos;
    try {
      photos = InspectionPhotoJsonCodec.parseDetectionPhotos(photosJson);
    } catch (RuntimeException ex) {
      return null;
    }

    ScaleAdjustmentEvidence resolved = null;
    for (Map<String, Object> photo : photos) {
      Object rawDetection = photo == null ? null : photo.get("detection");
      if (!(rawDetection instanceof Map<?, ?> detection)) {
        continue;
      }
      Long clauseId = longValue(detection, "clauseId");
      String clauseCode = textValue(detection, "clauseCode");
      String policy = textValue(detection, "deductionPolicyVersion");
      boolean relevant = Objects.equals(clauseId, standard.id())
          || SCALE_ADJUSTMENT_CLAUSE_CODE.equalsIgnoreCase(clauseCode)
          || SCALE_ADJUSTMENT_POLICY.equals(policy);
      if (!relevant || !"CONFIRMED".equals(textValue(detection, "decisionStatus"))) {
        continue;
      }

      Long photoAttachmentId = longValue(photo, "attachmentId");
      Long detectionAttachmentId = longValue(detection, "attachmentId");
      BigDecimal scoreScale = decimalValue(detection, "scoreScale");
      BigDecimal persistedScoreScale = decimalValue(detection, "persistedScoreScale");
      BigDecimal clauseDeduction = decimalValue(detection, "clauseDeduction");
      BigDecimal adjustmentDeduction = decimalValue(detection, "scaleAdjustmentDeduction");
      BigDecimal standardDeduction = decimalValue(detection, "standardDeduction");
      BigDecimal finalDeduction = decimalValue(detection, "finalDeduction");
      BigDecimal confirmedDeduction = decimalValue(detection, "confirmedDeduction");

      boolean valid = blankToNull(textValue(detection, "detectionKey")) != null
          && Objects.equals(clauseId, standard.id())
          && Objects.equals(clauseId, snapshot.standardItemId())
          && SCALE_ADJUSTMENT_CLAUSE_CODE.equalsIgnoreCase(clauseCode)
          && SCALE_ADJUSTMENT_POLICY.equals(policy)
          && photoAttachmentId != null
          && photoAttachmentId > 0
          && Objects.equals(photoAttachmentId, detectionAttachmentId)
          && snapshot.photoAttachmentIds().contains(photoAttachmentId)
          && equalAmount(scoreScale, LEGACY_SCORE_SCALE)
          && equalAmount(persistedScoreScale, PERSISTED_SCORE_SCALE)
          && equalAmount(clauseDeduction, SCALE_ADJUSTMENT_CLAUSE_DEDUCTION)
          && equalAmount(adjustmentDeduction, SCALE_ADJUSTMENT_DEDUCTION)
          && equalAmount(standardDeduction, SCALE_ADJUSTMENT_TOTAL_DEDUCTION)
          && equalAmount(finalDeduction, SCALE_ADJUSTMENT_TOTAL_DEDUCTION)
          && equalAmount(confirmedDeduction, SCALE_ADJUSTMENT_TOTAL_DEDUCTION)
          && equalAmount(clauseDeduction.add(adjustmentDeduction), confirmedDeduction)
          && equalAmount(deduction, confirmedDeduction);
      if (!valid) {
        return null;
      }

      ScaleAdjustmentEvidence candidate =
          new ScaleAdjustmentEvidence(clauseDeduction, adjustmentDeduction);
      if (resolved != null && !resolved.equals(candidate)) {
        return null;
      }
      resolved = candidate;
    }
    return resolved;
  }

  private static boolean equalAmount(BigDecimal left, BigDecimal right) {
    return left != null && right != null && left.compareTo(right) == 0;
  }

  private static String textValue(Map<?, ?> source, String key) {
    Object value = source == null ? null : source.get(key);
    return value == null ? null : String.valueOf(value).trim();
  }

  private static Long longValue(Map<?, ?> source, String key) {
    Object value = source == null ? null : source.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      String text = value == null ? null : String.valueOf(value).trim();
      return text == null || text.isBlank() ? null : Long.parseLong(text);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static BigDecimal decimalValue(Map<?, ?> source, String key) {
    Object value = source == null ? null : source.get(key);
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number || value instanceof String) {
      try {
        return new BigDecimal(String.valueOf(value).trim());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  private static BigDecimal amount(String value) {
    return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
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

  private record ScaleAdjustmentEvidence(
      BigDecimal clauseDeduction,
      BigDecimal scaleAdjustmentDeduction
  ) {}

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
