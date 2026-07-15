package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class InspectionLegacySnapshotParserTest {
  @Test
  void parsesDeductionAndRedLineSnapshotsWithoutChangingTheirOrder() {
    List<InspectionStandardSnapshot> snapshots = InspectionLegacySnapshotParser.parse(request(
        "[{\"standard_id\":12,\"standard_version\":\"R1\",\"dimension\":\"卫生\",\"item\":\"地面\",\"score\":4,\"deduct\":2}]",
        "[{\"standardId\":13,\"standardVersion\":\"R1\",\"project\":\"服务\",\"title\":\"红线\",\"deductionScore\":4}]"));

    assertThat(snapshots).hasSize(2);
    assertThat(snapshots.get(0).standardId()).isEqualTo(12L);
    assertThat(snapshots.get(0).actualDeductionScore()).isEqualByComparingTo("2.00");
    assertThat(snapshots.get(0).redLine()).isFalse();
    assertThat(snapshots.get(1).standardId()).isEqualTo(13L);
    assertThat(snapshots.get(1).redLine()).isTrue();
    assertThat(snapshots.get(1).sortOrder()).isEqualTo(2);
  }

  @Test
  void ignoresMalformedLegacyPayloadInsteadOfCreatingBrokenSnapshots() {
    assertThat(InspectionLegacySnapshotParser.parse(request("not-json", null))).isEmpty();
    assertThat(InspectionLegacySnapshotParser.parse(request("[{\"deduct\":2}]", null))).isEmpty();
  }

  private InspectionRecordRequest request(String deductionsJson, String redlinesJson) {
    return new InspectionRecordRequest(
        "store-1", "2026-07-15", "督导", "茹果", new BigDecimal("200.00"),
        new BigDecimal("200.00"), true, deductionsJson, redlinesJson, "[]", "测试");
  }
}
