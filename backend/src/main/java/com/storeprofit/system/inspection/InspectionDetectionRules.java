package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure server-side rules for matching model evidence to formal inspection clauses and scores. */
final class InspectionDetectionRules {
  private static final String DETECTION_PENDING = "PENDING_MANUAL_CONFIRMATION";
  private static final String DETECTION_UNMATCHED = "UNMATCHED";
  private static final BigDecimal H_4_1_2_SERVER_DEDUCTION = new BigDecimal("4.00");
  private static final String H_4_1_2_DEDUCTION_POLICY = "LEGACY_100_TO_200_H412_V1";
  private static final String ACTIVE_CLAUSE_DEDUCTION_POLICY = "ACTIVE_CLAUSE_SCORE_V1";
  private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private InspectionDetectionRules() {}

  static Map<String, Object> enrichSuggestion(
      Map<String, Object> source,
      List<InspectionStandardItemResponse> standards
  ) {
    Map<String, Object> result = new LinkedHashMap<>(source);
    result.remove("annotated_image");
    result.remove("annotatedImage");
    result.remove("original_image");
    result.remove("originalImage");

    List<Map<String, Object>> detections = InspectionDetectionEvidenceNormalizer.deduplicate(
        value(source, "detections"));
    result.put("detections", detections);
    result.put("detection_count", detections.size());
    result.put("detectionCount", detections.size());

    InspectionStandardItemResponse clause = matchClause(source, detections, standards).orElse(null);
    BigDecimal sourceScale = InspectionScoringRules.LEGACY_MAX_SCORE;
    BigDecimal standardDeduction = serverDeduction(clause);
    BigDecimal legacyDeduction = standardDeduction
        .multiply(InspectionScoringRules.LEGACY_MAX_SCORE)
        .divide(InspectionScoringRules.MAX_SCORE, 2, RoundingMode.HALF_UP);
    BigDecimal convertedDeduction = standardDeduction;
    BigDecimal clauseDeduction = clause == null
        ? ZERO_AMOUNT
        : amountOrDefault(clause.suggestedScore(), ZERO_AMOUNT).abs()
            .setScale(2, RoundingMode.HALF_UP);
    BigDecimal scaleAdjustmentDeduction = standardDeduction.subtract(clauseDeduction)
        .max(ZERO_AMOUNT).setScale(2, RoundingMode.HALF_UP);
    String deductionPolicyVersion = clause != null && "H-4.1.2".equalsIgnoreCase(clause.code())
        ? H_4_1_2_DEDUCTION_POLICY
        : ACTIVE_CLAUSE_DEDUCTION_POLICY;
    BigDecimal confidence = InspectionDetectionEvidenceNormalizer.maximumConfidence(detections);
    String detectionKey = InspectionDetectionEvidenceNormalizer.stableKey(source, detections);
    String issueCode = InspectionDetectionEvidenceNormalizer.issueCode(detections);
    String issueName = firstNonBlank(
        textValue(source, "deduction_content", "deductionContent"),
        firstNonBlank(textValue(source, "auto_status", "autoStatus"), "模型识别到疑似现场问题")
    );

    result.put("detectionKey", detectionKey);
    result.put("imageId", textValue(source, "image_id", "imageId"));
    result.put("scoreScale", sourceScale.setScale(2, RoundingMode.HALF_UP));
    result.put("persistedScoreScale", InspectionScoringRules.MAX_SCORE);
    result.put("legacyDeduction", legacyDeduction);
    result.put("convertedDeduction200", convertedDeduction);
    result.put("standardDeduction", standardDeduction);
    result.put("clauseDeduction", clauseDeduction);
    result.put("scaleAdjustmentDeduction", scaleAdjustmentDeduction);
    result.put("deductionPolicyVersion", deductionPolicyVersion);
    result.put("suggestedDeduction", convertedDeduction);
    result.put("finalDeduction", standardDeduction);
    result.put("confirmedDeduction", standardDeduction);
    result.put("confidence", confidence);
    result.put("issueCode", issueCode);
    result.put("issueName", issueName);
    result.put("decisionStatus", clause == null ? DETECTION_UNMATCHED : DETECTION_PENDING);
    result.put("revision", 0L);
    result.put("deduction_score", standardDeduction);
    if (clause != null) {
      result.put("clauseId", clause.id());
      result.put("clauseCode", clause.code());
      result.put("clauseTitle", clause.title());
    }
    return result;
  }

  static Optional<InspectionStandardItemResponse> matchClause(
      Map<String, Object> source,
      List<Map<String, Object>> detections,
      List<InspectionStandardItemResponse> standards
  ) {
    Long explicitId = longValue(source, "standard_item_id", "standardItemId", "clause_id", "clauseId");
    if (explicitId != null) {
      Optional<InspectionStandardItemResponse> verified = standards.stream()
          .filter(item -> item.id() == explicitId && item.enabled())
          .findFirst();
      if (verified.isPresent()) {
        return verified;
      }
    }
    String explicitCode = textValue(source, "standard_code", "standardCode", "clause_code", "clauseCode");
    if (explicitCode != null) {
      Optional<InspectionStandardItemResponse> verified = standards.stream()
          .filter(item -> explicitCode.equalsIgnoreCase(item.code()) && item.enabled())
          .findFirst();
      if (verified.isPresent()) {
        return verified;
      }
    }

    Set<String> classes = detections.stream()
        .map(item -> textValue(item, "class_name", "className", "label"))
        .filter(Objects::nonNull)
        .map(value -> value.toLowerCase(java.util.Locale.ROOT))
        .collect(Collectors.toSet());
    if (classes.stream().anyMatch(value -> Set.of(
        "paper_scrap", "paper", "stain", "floor_litter", "corner_dust").contains(value))) {
      Optional<InspectionStandardItemResponse> floor = standards.stream()
          .filter(item -> item.enabled() && "H-4.1.2".equalsIgnoreCase(item.code()))
          .findFirst();
      if (floor.isPresent()) {
        return floor;
      }
    }

    String project = normalizeMatchText(textValue(
        source, "deduction_project", "deductionProject", "project", "suggested_project"));
    String issue = normalizeMatchText(textValue(
        source, "deduction_content", "deductionContent", "issue", "suggested_issue"));
    return standards.stream()
        .filter(InspectionStandardItemResponse::enabled)
        .map(item -> Map.entry(item, matchScore(item, project, issue, classes)))
        .filter(entry -> entry.getValue() >= 20)
        .sorted(Comparator
            .<Map.Entry<InspectionStandardItemResponse, Integer>>comparingInt(Map.Entry::getValue)
            .reversed()
            .thenComparingInt(entry -> entry.getKey().sortOrder()))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  private static BigDecimal serverDeduction(InspectionStandardItemResponse clause) {
    if (clause == null) {
      return ZERO_AMOUNT;
    }
    if ("H-4.1.2".equalsIgnoreCase(clause.code())) {
      return H_4_1_2_SERVER_DEDUCTION.setScale(2, RoundingMode.HALF_UP);
    }
    return amountOrDefault(clause.suggestedScore(), ZERO_AMOUNT).abs()
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static int matchScore(
      InspectionStandardItemResponse item,
      String project,
      String issue,
      Set<String> classes
  ) {
    String title = normalizeMatchText(item.title());
    String description = normalizeMatchText(item.description());
    String method = normalizeMatchText(item.checkMethod());
    int score = 0;
    if (!project.isBlank() && project.equals(title)) {
      score += 100;
    } else if (!project.isBlank() && !title.isBlank()
        && (project.contains(title) || title.contains(project))) {
      score += 45;
    }
    if (!issue.isBlank() && !title.isBlank() && issue.contains(title)) {
      score += 35;
    }
    if (!issue.isBlank() && (!description.isBlank() && description.contains(issue)
        || !method.isBlank() && method.contains(issue))) {
      score += 25;
    }
    if (!classes.isEmpty() && "HYGIENE".equals(InspectionStandardValidator.category(item.dimension()))) {
      score += 5;
    }
    return score;
  }

  private static String normalizeMatchText(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^\\p{IsHan}a-z0-9]+", "")
        .replace("检查标准", "")
        .replace("标准", "");
  }

  private static BigDecimal amountOrDefault(BigDecimal value, BigDecimal defaultValue) {
    return (value == null ? defaultValue : value).setScale(2, RoundingMode.HALF_UP);
  }

  private static String firstNonBlank(String first, String fallback) {
    String normalizedFirst = first == null || first.isBlank() ? null : first.trim();
    return normalizedFirst == null ? fallback : normalizedFirst;
  }

  private static Object value(Map<String, Object> source, String... keys) {
    if (source == null) {
      return null;
    }
    for (String key : keys) {
      if (source.containsKey(key)) {
        return source.get(key);
      }
    }
    return null;
  }

  private static String textValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
    if (raw == null) {
      return null;
    }
    String text = String.valueOf(raw).trim();
    return text.isBlank() ? null : text;
  }

  private static Long longValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number number) {
      return number.longValue();
    }
    try {
      String text = String.valueOf(raw).trim();
      return text.isBlank() ? null : Long.parseLong(text);
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
