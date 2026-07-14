package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class InspectionRecordResponseContractTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void exposesOnlyCanonicalScoreDecisionFieldsToApiConsumers() throws Exception {
    JsonNode passed = objectMapper.valueToTree(record("98", true, "PASSED", "[]"));
    assertThat(passed.path("score").decimalValue()).isEqualByComparingTo("196.00");
    assertThat(passed.path("maxScore").decimalValue()).isEqualByComparingTo("200.00");
    assertThat(passed.path("fullScore").decimalValue()).isEqualByComparingTo("200.00");
    assertThat(passed.path("passScore").decimalValue()).isEqualByComparingTo("180.00");
    assertThat(passed.path("passed").booleanValue()).isTrue();
    assertThat(passed.path("resultCode").textValue()).isEqualTo("PASSED");
    assertThat(passed.path("originalScore").decimalValue()).isEqualByComparingTo("98.00");

    JsonNode failed = objectMapper.valueToTree(record("82", true, "PASSED", "[]"));
    assertThat(failed.path("score").decimalValue()).isEqualByComparingTo("164.00");
    assertThat(failed.path("passed").booleanValue()).isFalse();
    assertThat(failed.path("resultCode").textValue()).isEqualTo("FAILED");
  }

  @Test
  void countsHistoricalRedlinesWhenNoItemSnapshotsExist() {
    InspectionRecordResponse response = record(
        "98", false, "RED_LINE_FAILED", "[{\"code\":\"R1\"},{\"code\":\"R2\"}]");
    assertThat(response.redLineCount()).isEqualTo(2);
    assertThat(response.displayPassed()).isFalse();
  }

  private InspectionRecordResponse record(
      String score,
      boolean rawPassed,
      String resultCode,
      String redlines
  ) {
    return new InspectionRecordResponse(
        "inspection-1", "store-1", "S1", "门店一", 1L, "茹菓",
        "2026-07-13", "督导", "茹菓", new BigDecimal("100.00"),
        new BigDecimal(score), rawPassed, "[]", redlines, "[]", null,
        null, null, null, null, null, resultCode, List.of());
  }
}
