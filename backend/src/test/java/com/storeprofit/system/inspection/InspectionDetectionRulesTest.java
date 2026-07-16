package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InspectionDetectionRulesTest {

  @Test
  void h412AlwaysUsesTheServerFourPointDeductionAndOverridesModelScores() {
    InspectionStandardItemResponse floorClause = standard(
        412L, "H-4.1.2", "Floor hygiene", new BigDecimal("1.00"), true);
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("standard_item_id", 412L);
    source.put("deduction_score", new BigDecimal("99.00"));
    source.put("standardDeduction", new BigDecimal("98.00"));
    source.put("suggestedDeduction", new BigDecimal("97.00"));
    source.put("finalDeduction", new BigDecimal("96.00"));
    source.put("confirmedDeduction", new BigDecimal("95.00"));
    source.put("detections", List.of(Map.of(
        "class_name", "paper_scrap",
        "confidence", new BigDecimal("0.93")
    )));

    Map<String, Object> result = InspectionDetectionRules.enrichSuggestion(
        source, List.of(floorClause));

    assertThat(result.get("clauseId")).isEqualTo(412L);
    assertThat(result.get("clauseCode")).isEqualTo("H-4.1.2");
    assertAmount(result.get("standardDeduction"), "4.00");
    assertAmount(result.get("suggestedDeduction"), "4.00");
    assertAmount(result.get("finalDeduction"), "4.00");
    assertAmount(result.get("confirmedDeduction"), "4.00");
    assertAmount(result.get("deduction_score"), "4.00");
    assertAmount(result.get("legacyDeduction"), "2.00");
    assertAmount(result.get("convertedDeduction200"), "4.00");
    assertAmount(result.get("scoreScale"), "100.00");
    assertAmount(result.get("persistedScoreScale"), "200.00");
    assertThat(result.get("deductionPolicyVersion"))
        .isEqualTo("LEGACY_100_TO_200_H412_V1");
  }

  @Test
  void explicitEnabledClauseIdTakesPrecedenceOverAConflictingCode() {
    InspectionStandardItemResponse selectedById = standard(
        91L, "S-9.1", "Selected by id", new BigDecimal("7.00"), true);
    InspectionStandardItemResponse conflictingCode = standard(
        92L, "S-9.2", "Conflicting code", new BigDecimal("3.00"), true);
    Map<String, Object> source = Map.of(
        "standard_item_id", "91",
        "standard_code", "s-9.2"
    );

    assertThat(InspectionDetectionRules.matchClause(source, List.of(),
        List.of(selectedById, conflictingCode)))
        .contains(selectedById);
  }

  @Test
  void explicitEnabledClauseCodeIsResolvedCaseInsensitively() {
    InspectionStandardItemResponse selectedByCode = standard(
        93L, "S-9.3", "Selected by code", new BigDecimal("6.00"), true);

    assertThat(InspectionDetectionRules.matchClause(
        Map.of("clauseCode", "s-9.3"), List.of(), List.of(selectedByCode)))
        .contains(selectedByCode);
  }

  @Test
  void disabledExplicitClauseIsNeverSelected() {
    InspectionStandardItemResponse disabled = standard(
        94L, "S-9.4", "Disabled", new BigDecimal("6.00"), false);
    Map<String, Object> source = Map.of(
        "standardItemId", 94L,
        "standardCode", "S-9.4"
    );

    assertThat(InspectionDetectionRules.matchClause(source, List.of(), List.of(disabled))).isEmpty();
  }

  @Test
  void unmatchedEvidenceIsMarkedUnmatchedWithNoServerDeduction() {
    InspectionStandardItemResponse unrelated = standard(
        95L, "S-9.5", "Checkout greeting", new BigDecimal("5.00"), true);
    Map<String, Object> source = Map.of(
        "deduction_project", "unrelated inspection topic",
        "deduction_content", "does not describe any configured clause",
        "detections", List.of(Map.of("class_name", "unknown_label", "confidence", 0.88))
    );

    Map<String, Object> result = InspectionDetectionRules.enrichSuggestion(source, List.of(unrelated));

    assertThat(result.get("decisionStatus")).isEqualTo("UNMATCHED");
    assertThat(result).doesNotContainKeys("clauseId", "clauseCode", "clauseTitle");
    assertAmount(result.get("standardDeduction"), "0.00");
    assertAmount(result.get("suggestedDeduction"), "0.00");
    assertAmount(result.get("finalDeduction"), "0.00");
    assertAmount(result.get("deduction_score"), "0.00");
  }

  @Test
  void stripsBase64ImagePayloadsFromOutputWithoutMutatingTheCallerInput() {
    List<Map<String, Object>> originalDetections = List.of(Map.of(
        "class_name", "paper",
        "confidence", 0.91
    ));
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("image_id", "image-1");
    source.put("annotated_image", "data:image/png;base64,annotated-snake");
    source.put("annotatedImage", "data:image/png;base64,annotated-camel");
    source.put("original_image", "data:image/png;base64,original-snake");
    source.put("originalImage", "data:image/png;base64,original-camel");
    source.put("detections", originalDetections);

    Map<String, Object> result = InspectionDetectionRules.enrichSuggestion(source, List.of());

    assertThat(result).doesNotContainKeys(
        "annotated_image", "annotatedImage", "original_image", "originalImage");
    assertThat(source).containsEntry(
        "annotated_image", "data:image/png;base64,annotated-snake");
    assertThat(source).containsEntry(
        "annotatedImage", "data:image/png;base64,annotated-camel");
    assertThat(source).containsEntry(
        "original_image", "data:image/png;base64,original-snake");
    assertThat(source).containsEntry(
        "originalImage", "data:image/png;base64,original-camel");
    assertThat(source.get("detections")).isSameAs(originalDetections);
    assertThat(source).doesNotContainKeys("detectionKey", "standardDeduction", "decisionStatus");
  }

  private static InspectionStandardItemResponse standard(
      long id,
      String code,
      String title,
      BigDecimal suggestedScore,
      boolean enabled
  ) {
    return new InspectionStandardItemResponse(
        id,
        "SERVICE",
        code,
        title,
        "Test clause description",
        suggestedScore,
        false,
        enabled,
        (int) id,
        "Test method",
        "NORMAL"
    );
  }

  private static void assertAmount(Object actual, String expected) {
    assertThat(actual).isInstanceOf(BigDecimal.class);
    assertThat((BigDecimal) actual).isEqualByComparingTo(expected);
  }
}
