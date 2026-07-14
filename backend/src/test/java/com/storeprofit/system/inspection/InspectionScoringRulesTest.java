package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InspectionScoringRulesTest {
  @Test
  void appliesTheSinglePassBoundaryAtOneHundredEighty() {
    assertThat(InspectionScoringRules.passed(score("179"), false)).isFalse();
    assertThat(InspectionScoringRules.resultCode(score("179"), false)).isEqualTo("FAILED");
    assertThat(InspectionScoringRules.passed(score("180"), false)).isTrue();
    assertThat(InspectionScoringRules.resultCode(score("180"), false)).isEqualTo("PASSED");
    assertThat(InspectionScoringRules.passed(score("200"), false)).isTrue();
    assertThat(InspectionScoringRules.resultCode(score("200"), false)).isEqualTo("PASSED");
    assertThat(InspectionScoringRules.passed(score("200"), true)).isFalse();
    assertThat(InspectionScoringRules.resultCode(score("200"), true))
        .isEqualTo("RED_LINE_FAILED");
  }

  @Test
  void convertsLegacyScoresWithoutChangingCanonicalScores() {
    assertThat(InspectionScoringRules.normalizeScore(score("98"), score("100")))
        .isEqualByComparingTo("196.00");
    assertThat(InspectionScoringRules.normalizeScore(score("82"), score("100")))
        .isEqualByComparingTo("164.00");
    assertThat(InspectionScoringRules.normalizeScore(score("98"), score("200")))
        .isEqualByComparingTo("98.00");
  }

  private BigDecimal score(String value) {
    return new BigDecimal(value);
  }
}
