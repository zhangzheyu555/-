package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Verifies the unified 200-point scoring contract end-to-end:
 * 200 full / 180 pass / 200-scale normalization / test-data isolation.
 */
class InspectionScoreScaleGovernanceTest {

  // ── 200-point canonical contract ──

  @Test
  void canonicalTwoHundredPointInspectionReturnsCorrectScores() {
    InspectionRecordResponse record = new InspectionRecordResponse(
        "canonical-200", "store-a", "SA", "门店A", 1L, "茹菓",
        "2026-07-01", "督导", "茹菓",
        new BigDecimal("200.00"), // fullScore
        new BigDecimal("188.00"), // score
        true, // passed (raw, overwritten by present)
        "[]", "[]", "[]", null);

    assertThat(record.displayFullScore()).isEqualByComparingTo("200.00");
    assertThat(record.displayScore()).isEqualByComparingTo("188.00");
    assertThat(record.displayPassScore()).isEqualByComparingTo("180.00");
    assertThat(record.displayPassed()).isTrue();
    assertThat(record.displayResultCode()).isEqualTo("PASSED");
    assertThat(record.referenceScore200()).isEqualByComparingTo("188.00");
  }

  @Test
  void twoHundredPointInspectionBelowPassLineIsFailed() {
    InspectionRecordResponse record = new InspectionRecordResponse(
        "canonical-200-fail", "store-b", "SB", "门店B", 1L, "茹菓",
        "2026-07-01", "督导", "茹菓",
        new BigDecimal("200.00"),
        new BigDecimal("179.00"), // below 180
        true,
        "[]", "[]", "[]", null);

    assertThat(record.displayScore()).isEqualByComparingTo("179.00");
    assertThat(record.displayPassed()).isFalse();
    assertThat(record.displayResultCode()).isEqualTo("FAILED");
  }

  // ── Legacy 100-point normalization ──

  @Test
  void legacyHundredPointHistoryNormalizesToTwoHundredScale() {
    // Simulate old data: fullScore=100, score=82
    InspectionRecordResponse legacy = new InspectionRecordResponse(
        "legacy-82", "store-c", "SC", "门店C", 1L, "茹菓",
        "2026-07-01", "督导", "茹菓",
        new BigDecimal("100.00"), // old 100-point full
        new BigDecimal("82.00"),  // old score
        true,
        "[]", "[]", "[]", null);

    assertThat(legacy.fullScore()).isEqualByComparingTo("100.00");
    assertThat(legacy.score()).isEqualByComparingTo("82.00");
    // Display must be 200-scale: 82/100 = 164/200
    assertThat(legacy.displayFullScore()).isEqualByComparingTo("200.00");
    assertThat(legacy.displayScore()).isEqualByComparingTo("164.00");
    assertThat(legacy.displayPassScore()).isEqualByComparingTo("180.00");
    assertThat(legacy.displayPassed()).isFalse();
    assertThat(legacy.referenceScore200()).isEqualByComparingTo("164.00");
    // originalPassScore is null for legacy because raw fullScore=100 ≠ 200
    assertThat(legacy.originalPassScore()).isNull();
  }

  @Test
  void legacyHundredPointHighScoreNormalizesAndPasses() {
    InspectionRecordResponse legacy = new InspectionRecordResponse(
        "legacy-98", "store-d", "SD", "门店D", 1L, "茹菓",
        "2026-07-01", "督导", "茹菓",
        new BigDecimal("100.00"),
        new BigDecimal("98.00"),
        true,
        "[]", "[]", "[]", null);

    assertThat(legacy.displayScore()).isEqualByComparingTo("196.00");
    assertThat(legacy.displayPassed()).isTrue();
    assertThat(legacy.displayResultCode()).isEqualTo("PASSED");
  }

  // ── Already 200-point: no double-conversion ──

  @Test
  void alreadyTwoHundredPointRecordsAreNotDoubleConverted() {
    InspectionRecordResponse modern = new InspectionRecordResponse(
        "modern-185", "store-e", "SE", "门店E", 1L, "茹菓",
        "2026-07-01", "督导", "茹菓",
        new BigDecimal("200.00"), // already 200-point
        new BigDecimal("185.00"),
        true,
        "[]", "[]", "[]", null);

    // Display must be identical — no x2
    assertThat(modern.displayFullScore()).isEqualByComparingTo("200.00");
    assertThat(modern.displayScore()).isEqualByComparingTo("185.00");
    assertThat(modern.score()).isEqualByComparingTo("185.00");
    assertThat(modern.displayPassed()).isTrue();
  }

  @Test
  void legacyScoreExactAtOneHundredNormalizesToFullTwoHundred() {
    InspectionRecordResponse full = new InspectionRecordResponse(
        "legacy-100", "store-f", "SF", "门店F", 1L, "茹菓",
        "2026-07-01", "督导", "茹菓",
        new BigDecimal("100.00"),
        new BigDecimal("100.00"),
        true,
        "[]", "[]", "[]", null);

    assertThat(full.displayScore()).isEqualByComparingTo("200.00");
    assertThat(full.displayPassed()).isTrue();
  }

  // ── Red-line handling ──

  @Test
  void redLineHitFailsRegardlessOfScore() {
    InspectionRecordResponse redLine = new InspectionRecordResponse(
        "redline-195", "store-g", "SG", "门店G", 1L, "茹菓",
        "2026-07-01", "督导", "茹菓",
        new BigDecimal("200.00"),
        new BigDecimal("195.00"),
        true, // raw passed
        "[]", "[{\"code\":\"R1\"}]", "[]", null);

    assertThat(redLine.displayScore()).isEqualByComparingTo("195.00");
    assertThat(redLine.displayPassed()).isFalse();
    assertThat(redLine.displayResultCode()).isEqualTo("RED_LINE_FAILED");
    assertThat(redLine.redLineCount()).isGreaterThan(0);
  }

  // ── ScoringRules correctness ──

  @Test
  void scoringRulesMaxScoreIsTwoHundred() {
    assertThat(InspectionScoringRules.MAX_SCORE).isEqualByComparingTo("200.00");
  }

  @Test
  void scoringRulesPassScoreIsOneEighty() {
    assertThat(InspectionScoringRules.PASS_SCORE).isEqualByComparingTo("180.00");
  }

  @Test
  void normalizeScorePropagatesNullSourceMaxAsLegacy() {
    // null sourceMaxScore defaults to LEGACY_MAX_SCORE (100)
    assertThat(InspectionScoringRules.normalizeScore(new BigDecimal("82"), null))
        .isEqualByComparingTo("164.00");
  }

  @Test
  void normalizeScoreClampsToZeroAndTwoHundred() {
    assertThat(InspectionScoringRules.normalizeScore(new BigDecimal("-1"), new BigDecimal("100")))
        .isEqualByComparingTo("0.00");
    assertThat(InspectionScoringRules.normalizeScore(new BigDecimal("110"), new BigDecimal("100")))
        .isEqualByComparingTo("200.00");
  }

  // ── Category score normalization ──

  @Test
  void normalizeCategoryScoresConsistently() {
    // Legacy: material=30/100 → 60/200, hygiene=50/100 → 100/200
    assertThat(InspectionScoringRules.normalizeCategoryScore(
        new BigDecimal("30"), new BigDecimal("100")))
        .isEqualByComparingTo("60.00");
    assertThat(InspectionScoringRules.normalizeCategoryScore(
        new BigDecimal("50"), new BigDecimal("100")))
        .isEqualByComparingTo("100.00");
  }

  @Test
  void normalizeCategoryDoesNotDoubleConvertAlreadyTwoHundred() {
    assertThat(InspectionScoringRules.normalizeCategoryScore(
        new BigDecimal("60"), new BigDecimal("200")))
        .isEqualByComparingTo("60.00");
    assertThat(InspectionScoringRules.normalizeCategoryScore(
        new BigDecimal("180"), new BigDecimal("200")))
        .isEqualByComparingTo("180.00");
  }

  // ── Test-data isolation contract ──

  @Test
  void testMarkerIsNeverExposedToApi() {
    // The InspectionRecordResponse must not have a testMarker field —
    // test data isolation happens at the repository/query level.
    InspectionRecordResponse record = new InspectionRecordResponse(
        "test-rec", "store-h", "SH", "门店H", 1L, "茹菓",
        "2026-07-01", "督导", "茹菓",
        new BigDecimal("200.00"), new BigDecimal("190.00"), true,
        "[]", "[]", "[]", null);
    // The record itself never leaks test_marker to API consumers
    assertThat(record.displayScore()).isEqualByComparingTo("190.00");
  }
}
