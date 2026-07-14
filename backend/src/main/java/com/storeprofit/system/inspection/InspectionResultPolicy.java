package com.storeprofit.system.inspection;

import java.math.BigDecimal;

final class InspectionResultPolicy {
  private InspectionResultPolicy() {
  }

  static InspectionResultPresentation present(
      BigDecimal rawFullScore,
      BigDecimal rawScore,
      BigDecimal rawMaterialScore,
      BigDecimal rawHygieneScore,
      BigDecimal rawServiceScore,
      boolean rawPassed,
      String rawResultCode,
      String rawRedlinesJson,
      String rawStandardVersion,
      InspectionResultRepairAudit repair
  ) {
    BigDecimal originalFullScore = amount(repair == null ? rawFullScore : repair.originalFullScore());
    BigDecimal originalScore = amount(repair == null ? rawScore : repair.originalScore());
    BigDecimal originalMaterialScore = originalCategoryScore(
        rawMaterialScore, repair == null ? null : repair.originalMaterialScore());
    BigDecimal originalHygieneScore = originalCategoryScore(
        rawHygieneScore, repair == null ? null : repair.originalHygieneScore());
    BigDecimal originalServiceScore = originalCategoryScore(
        rawServiceScore, repair == null ? null : repair.originalServiceScore());
    BigDecimal originalPassScore = repair != null && repair.originalPassScore() != null
        ? amount(repair.originalPassScore()) : originalPassScore(originalFullScore);
    String originalResultCode = repair != null && hasText(repair.originalResultCode())
        ? repair.originalResultCode() : rawResultCode;
    boolean originalRedLineHit = "RED_LINE_FAILED".equalsIgnoreCase(originalResultCode)
        || hasJsonEntries(rawRedlinesJson);
    String originalStandardVersion = repair != null && hasText(repair.originalStandardVersion())
        ? repair.originalStandardVersion() : rawStandardVersion;
    BigDecimal referenceScore200 = InspectionScoringRules.normalizeScore(
        originalScore, originalFullScore);
    BigDecimal normalizedMaterialScore = InspectionScoringRules.normalizeCategoryScore(
        originalMaterialScore, originalFullScore);
    BigDecimal normalizedHygieneScore = InspectionScoringRules.normalizeCategoryScore(
        originalHygieneScore, originalFullScore);
    BigDecimal normalizedServiceScore = InspectionScoringRules.normalizeCategoryScore(
        originalServiceScore, originalFullScore);

    if (repair == null) {
      String displayOriginalResult = InspectionScoringRules.resultCode(
          referenceScore200, originalRedLineHit);
      return new InspectionResultPresentation(
          originalPassScore,
          InspectionScoringRules.MAX_SCORE,
          referenceScore200,
          InspectionScoringRules.PASS_SCORE,
          normalizedMaterialScore,
          normalizedHygieneScore,
          normalizedServiceScore,
          "PASSED".equals(displayOriginalResult),
          displayOriginalResult,
          "NOT_NEEDED",
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          originalStandardVersion,
          null,
          null,
          null,
          referenceScore200
      );
    }

    if ("MANUAL_REVIEW".equalsIgnoreCase(repair.repairStatus())) {
      return new InspectionResultPresentation(
          originalPassScore,
          InspectionScoringRules.MAX_SCORE,
          referenceScore200,
          InspectionScoringRules.PASS_SCORE,
          null,
          null,
          null,
          false,
          "MANUAL_REVIEW",
          "MANUAL_REVIEW",
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          repair.repairReason(),
          originalStandardVersion,
          repair.id(),
          repair.repairedBy(),
          repair.repairedAt(),
          referenceScore200
      );
    }

    BigDecimal repairedSourceFullScore = amount(repair.repairedFullScore());
    BigDecimal repairedFullScore = InspectionScoringRules.MAX_SCORE;
    BigDecimal repairedScore = InspectionScoringRules.normalizeScore(
        repair.repairedScore(), repairedSourceFullScore);
    BigDecimal repairedPassScore = InspectionScoringRules.PASS_SCORE;
    BigDecimal repairedMaterialScore = InspectionScoringRules.normalizeCategoryScore(
        repair.repairedMaterialScore(), repairedSourceFullScore);
    BigDecimal repairedHygieneScore = InspectionScoringRules.normalizeCategoryScore(
        repair.repairedHygieneScore(), repairedSourceFullScore);
    BigDecimal repairedServiceScore = InspectionScoringRules.normalizeCategoryScore(
        repair.repairedServiceScore(), repairedSourceFullScore);
    boolean repairedRedLineHit = "RED_LINE_FAILED".equalsIgnoreCase(repair.repairedResultCode());
    String repairedResultCode = InspectionScoringRules.resultCode(repairedScore, repairedRedLineHit);
    boolean repairedPassed = "PASSED".equals(repairedResultCode);
    return new InspectionResultPresentation(
        originalPassScore,
        repairedFullScore,
        repairedScore,
        repairedPassScore,
        repairedMaterialScore,
        repairedHygieneScore,
        repairedServiceScore,
        repairedPassed,
        repairedResultCode,
        "REPAIRED",
        true,
        repairedScore,
        repairedFullScore,
        repairedPassScore,
        repairedMaterialScore,
        repairedHygieneScore,
        repairedServiceScore,
        repairedPassed,
        repairedResultCode,
        repair.repairReason(),
        originalStandardVersion,
        repair.id(),
        repair.repairedBy(),
        repair.repairedAt(),
        referenceScore200
    );
  }

  private static BigDecimal originalPassScore(BigDecimal fullScore) {
    return amount(fullScore).compareTo(InspectionScoringRules.LEGACY_MAX_SCORE) == 0
        ? null : InspectionScoringRules.PASS_SCORE;
  }

  private static boolean hasJsonEntries(String value) {
    if (!hasText(value)) {
      return false;
    }
    String normalized = value.trim().replaceAll("\\s+", "");
    return !"[]".equals(normalized) && !"null".equalsIgnoreCase(normalized);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static BigDecimal amount(String value) {
    return InspectionScoringRules.amount(new BigDecimal(value));
  }

  private static BigDecimal amount(BigDecimal value) {
    return InspectionScoringRules.amount(value);
  }

  private static BigDecimal originalCategoryScore(BigDecimal rawValue, BigDecimal auditedValue) {
    return nullableAmount(auditedValue == null ? rawValue : auditedValue);
  }

  private static BigDecimal nullableAmount(BigDecimal value) {
    return value == null ? null : InspectionScoringRules.amount(value);
  }
}
