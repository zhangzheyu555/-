package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InspectionHistoricalRepairCalculatorTest {
  @Test
  void recalculatesACompleteSnapshotUsingTheBoundStandardVersion() {
    InspectionHistoricalRepairCalculator.Calculation result = InspectionHistoricalRepairCalculator.calculate(
        List.of(standard(1L, "HYGIENE", "H-1", false, new BigDecimal("4.00"))),
        List.of(snapshot(1L, "HYGIENE", "H-1", false, new BigDecimal("1.00"))),
        version(new BigDecimal("200.00"), new BigDecimal("180.00")));

    assertThat(result.manualReview()).isFalse();
    assertThat(result.score()).isEqualByComparingTo("3.00");
    assertThat(result.hygieneScore()).isEqualByComparingTo("3.00");
    assertThat(result.materialScore()).isEqualByComparingTo("0.00");
    assertThat(result.serviceScore()).isEqualByComparingTo("0.00");
    assertThat(result.resultCode()).isEqualTo("FAILED");
  }

  @Test
  void sendsDuplicateSnapshotCodesToManualReviewInsteadOfGuessing() {
    InspectionHistoricalRepairCalculator.Calculation result = InspectionHistoricalRepairCalculator.calculate(
        List.of(
            standard(1L, "HYGIENE", "H-1", false, new BigDecimal("4.00")),
            standard(2L, "SERVICE", "S-1", false, new BigDecimal("4.00"))),
        List.of(
            snapshot(1L, "HYGIENE", "H-1", false, BigDecimal.ZERO),
            snapshot(2L, "SERVICE", "H-1", false, BigDecimal.ZERO)),
        version(new BigDecimal("200.00"), new BigDecimal("180.00")));

    assertThat(result.manualReview()).isTrue();
    assertThat(result.reason()).contains("唯一条款编号");
    assertThat(result.score()).isNull();
  }

  @Test
  void preservesRedLineFailureWhenTheSnapshotProvesAnIssue() {
    InspectionHistoricalRepairCalculator.Calculation result = InspectionHistoricalRepairCalculator.calculate(
        List.of(standard(1L, "HYGIENE", "H-1", true, new BigDecimal("200.00"))),
        List.of(snapshot(1L, "HYGIENE", "H-1", true, BigDecimal.ZERO)),
        version(new BigDecimal("200.00"), new BigDecimal("180.00")));

    assertThat(result.manualReview()).isFalse();
    assertThat(result.score()).isEqualByComparingTo("200.00");
    assertThat(result.resultCode()).isEqualTo("RED_LINE_FAILED");
  }

  private InspectionStandardRepository.VersionRow version(BigDecimal fullScore, BigDecimal passScore) {
    return new InspectionStandardRepository.VersionRow(
        1L, "测试标准", fullScore, passScore, "2025.11.06-R1", LocalDate.of(2025, 11, 6));
  }

  private InspectionStandardItemResponse standard(
      long id,
      String dimension,
      String code,
      boolean redLine,
      BigDecimal score
  ) {
    return new InspectionStandardItemResponse(
        id, dimension, code, "测试条款", "测试说明", score, redLine, true, (int) id,
        "查看", redLine ? "RED" : "NORMAL");
  }

  private InspectionItemResultResponse snapshot(
      long id,
      String dimension,
      String code,
      boolean redLine,
      BigDecimal deduction
  ) {
    BigDecimal standardScore = new BigDecimal("200.00");
    if (!redLine) {
      standardScore = new BigDecimal("4.00");
    }
    return new InspectionItemResultResponse(
        id, id, dimension, code, "测试条款", "测试说明", "查看", standardScore,
        standardScore.subtract(deduction), deduction, redLine, redLine ? "RED" : "NORMAL", redLine,
        deduction.signum() > 0 ? "测试扣分" : null, List.of(), null, null, "NOT_REQUIRED", null,
        List.of(), List.of(), (int) id);
  }
}
