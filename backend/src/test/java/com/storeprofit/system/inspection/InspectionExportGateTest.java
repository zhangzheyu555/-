package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InspectionExportGateTest {
  private static final String RECORD_ID = "e2e-seed-redline-inspection";
  private static final long VERSION_ID = 41L;
  private static final String VERSION = "2026.07-R1";
  private static final long SCALE_ADJUSTMENT_CLAUSE_ID = 208L;
  private static final long SCALE_ADJUSTMENT_ATTACHMENT_ID = 116L;
  private static final AuthUser BOSS = new AuthUser(
      1L, 1L, "default", "boss", "", "老板", "BOSS", null, true);

  @Test
  void allowsACompleteCanonicalRedLineRecordWithoutRewritingItsHistoricalScore() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    InspectionStandardRepository standardRepository = mock(InspectionStandardRepository.class);
    List<InspectionStandardItemResponse> standards = canonicalStandards();
    InspectionRecordResponse record = canonicalRecord(standards);
    stubCanonicalEvidence(recordRepository, standardRepository, record, standards);
    InspectionService service = service(recordRepository, standardRepository);

    InspectionRecordResponse prepared = service.prepareForExport(BOSS, RECORD_ID);

    assertThat(prepared).isSameAs(record);
    assertThat(prepared.standardVersion()).isEqualTo(VERSION);
    assertThat(prepared.displayFullScore()).isEqualByComparingTo("200.00");
    assertThat(prepared.displayPassScore()).isEqualByComparingTo("180.00");
    assertThat(prepared.displayScore()).isEqualByComparingTo("196.00");
    assertThat(prepared.displayResultCode()).isEqualTo("RED_LINE_FAILED");
    verify(recordRepository, never()).insertRepairAudit(anyLong(), anyString(), any());
    verify(recordRepository, never()).logAction(
        anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void blocksTwoSnapshotHistoricRecordInsteadOfTreatingItAsACompleteFormalReport() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    InspectionStandardRepository standardRepository = mock(InspectionStandardRepository.class);
    List<InspectionStandardItemResponse> standards = canonicalStandards();
    InspectionRecordResponse record = canonicalRecord(standards.subList(0, 2));
    when(recordRepository.record(1L, RECORD_ID)).thenReturn(Optional.of(record));
    when(recordRepository.scoreEvidence(1L, RECORD_ID)).thenReturn(Optional.of(
        evidence(2, 2, 1, VERSION_ID)));
    when(standardRepository.version(1L, VERSION_ID)).thenReturn(Optional.of(canonicalVersion()));
    when(standardRepository.items(1L, VERSION_ID)).thenReturn(standards);
    InspectionService service = service(recordRepository, standardRepository);

    InspectionScoreRepairRequiredException exception = catchThrowableOfType(
        () -> service.prepareForExport(BOSS, RECORD_ID),
        InspectionScoreRepairRequiredException.class);

    assertThat(exception.getCode()).isEqualTo("INSPECTION_SCORE_REPAIR_REQUIRED");
    assertThat(exception.missingFields()).containsExactly("标准快照条款数量");
    assertThat(exception.getMessage()).contains("标准快照条款数量");
    verify(recordRepository, never()).insertRepairAudit(anyLong(), anyString(), any());
    verify(recordRepository, never()).logAction(
        anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void reportsMissingSnapshotVersionAndClauseIdsBeforeAnyRepairCanRun() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    InspectionStandardRepository standardRepository = mock(InspectionStandardRepository.class);
    InspectionRecordResponse record = canonicalRecord(canonicalStandards().subList(0, 2));
    when(recordRepository.record(1L, RECORD_ID)).thenReturn(Optional.of(record));
    when(recordRepository.scoreEvidence(1L, RECORD_ID)).thenReturn(Optional.of(
        evidence(2, 1, 0, null)));
    InspectionService service = service(recordRepository, standardRepository);

    InspectionScoreRepairRequiredException exception = catchThrowableOfType(
        () -> service.prepareForExport(BOSS, RECORD_ID),
        InspectionScoreRepairRequiredException.class);

    assertThat(exception.getCode()).isEqualTo("INSPECTION_SCORE_REPAIR_REQUIRED");
    assertThat(exception.missingFields()).containsExactly("标准快照条款ID", "标准快照版本");
    assertThat(exception.getMessage()).contains("标准快照条款ID", "标准快照版本");
    verify(recordRepository, never()).insertRepairAudit(anyLong(), anyString(), any());
    verify(recordRepository, never()).logAction(
        anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void allowsConfirmedH412ScaleAdjustmentWhenItsPersistedEvidenceMatchesTheSnapshot() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    InspectionStandardRepository standardRepository = mock(InspectionStandardRepository.class);
    List<InspectionStandardItemResponse> standards = scaleAdjustmentStandards();
    InspectionRecordResponse record = scaleAdjustmentRecord(
        scaleAdjustmentPhotos("LEGACY_100_TO_200_H412_V1", "CONFIRMED"));
    stubScaleAdjustmentEvidence(recordRepository, standardRepository, record, standards);
    InspectionService service = service(recordRepository, standardRepository);

    InspectionRecordResponse prepared = service.prepareForExport(BOSS, RECORD_ID);

    assertThat(prepared).isSameAs(record);
    assertThat(prepared.displayScore()).isEqualByComparingTo("196.00");
    assertThat(prepared.displayHygieneScore()).isEqualByComparingTo("59.00");
    assertThat(prepared.displayResultCode()).isEqualTo("PASSED");
    verify(recordRepository, never()).insertRepairAudit(anyLong(), anyString(), any());
    verify(recordRepository, never()).logAction(
        anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void blocksH412ScaleAdjustmentWhenConfirmedPolicyEvidenceIsMissing() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    InspectionStandardRepository standardRepository = mock(InspectionStandardRepository.class);
    List<InspectionStandardItemResponse> standards = scaleAdjustmentStandards();
    InspectionRecordResponse record = scaleAdjustmentRecord("[]");
    stubScaleAdjustmentEvidence(recordRepository, standardRepository, record, standards);
    InspectionService service = service(recordRepository, standardRepository);

    InspectionScoreRepairRequiredException exception = catchThrowableOfType(
        () -> service.prepareForExport(BOSS, RECORD_ID),
        InspectionScoreRepairRequiredException.class);

    assertThat(exception.getCode()).isEqualTo("INSPECTION_SCORE_REPAIR_REQUIRED");
    assertThat(exception.missingFields()).containsExactly("条款扣分：H-4.1.2");
  }

  @Test
  void blocksH412ScaleAdjustmentWhenPersistedPolicyDoesNotMatch() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    InspectionStandardRepository standardRepository = mock(InspectionStandardRepository.class);
    List<InspectionStandardItemResponse> standards = scaleAdjustmentStandards();
    InspectionRecordResponse record = scaleAdjustmentRecord(
        scaleAdjustmentPhotos("ACTIVE_CLAUSE_SCORE_V1", "CONFIRMED"));
    stubScaleAdjustmentEvidence(recordRepository, standardRepository, record, standards);
    InspectionService service = service(recordRepository, standardRepository);

    InspectionScoreRepairRequiredException exception = catchThrowableOfType(
        () -> service.prepareForExport(BOSS, RECORD_ID),
        InspectionScoreRepairRequiredException.class);

    assertThat(exception.getCode()).isEqualTo("INSPECTION_SCORE_REPAIR_REQUIRED");
    assertThat(exception.missingFields()).containsExactly("条款扣分：H-4.1.2");
  }

  @Test
  void blocksH412ScaleAdjustmentWhenTheDetectionWasRevoked() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    InspectionStandardRepository standardRepository = mock(InspectionStandardRepository.class);
    List<InspectionStandardItemResponse> standards = scaleAdjustmentStandards();
    InspectionRecordResponse record = scaleAdjustmentRecord(
        scaleAdjustmentPhotos("LEGACY_100_TO_200_H412_V1", "REVOKED"));
    stubScaleAdjustmentEvidence(recordRepository, standardRepository, record, standards);
    InspectionService service = service(recordRepository, standardRepository);

    InspectionScoreRepairRequiredException exception = catchThrowableOfType(
        () -> service.prepareForExport(BOSS, RECORD_ID),
        InspectionScoreRepairRequiredException.class);

    assertThat(exception.getCode()).isEqualTo("INSPECTION_SCORE_REPAIR_REQUIRED");
    assertThat(exception.missingFields()).containsExactly("条款扣分：H-4.1.2");
  }

  @Test
  void blocksH412ScaleAdjustmentWhenItsAttachmentIsNotLinkedToTheSnapshot() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    InspectionStandardRepository standardRepository = mock(InspectionStandardRepository.class);
    List<InspectionStandardItemResponse> standards = scaleAdjustmentStandards();
    InspectionRecordResponse record = scaleAdjustmentRecord(
        scaleAdjustmentPhotos("LEGACY_100_TO_200_H412_V1", "CONFIRMED", 999L));
    stubScaleAdjustmentEvidence(recordRepository, standardRepository, record, standards);
    InspectionService service = service(recordRepository, standardRepository);

    InspectionScoreRepairRequiredException exception = catchThrowableOfType(
        () -> service.prepareForExport(BOSS, RECORD_ID),
        InspectionScoreRepairRequiredException.class);

    assertThat(exception.getCode()).isEqualTo("INSPECTION_SCORE_REPAIR_REQUIRED");
    assertThat(exception.missingFields()).containsExactly("条款扣分：H-4.1.2");
  }

  @Test
  void blocksH412ScaleAdjustmentWhenItsPersistedFinalDeductionsDisagree() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    InspectionStandardRepository standardRepository = mock(InspectionStandardRepository.class);
    List<InspectionStandardItemResponse> standards = scaleAdjustmentStandards();
    String tamperedPhotos = scaleAdjustmentPhotos(
        "LEGACY_100_TO_200_H412_V1", "CONFIRMED")
        .replace("\"finalDeduction\": 4,", "\"finalDeduction\": 3,");
    InspectionRecordResponse record = scaleAdjustmentRecord(tamperedPhotos);
    stubScaleAdjustmentEvidence(recordRepository, standardRepository, record, standards);
    InspectionService service = service(recordRepository, standardRepository);

    InspectionScoreRepairRequiredException exception = catchThrowableOfType(
        () -> service.prepareForExport(BOSS, RECORD_ID),
        InspectionScoreRepairRequiredException.class);

    assertThat(exception.getCode()).isEqualTo("INSPECTION_SCORE_REPAIR_REQUIRED");
    assertThat(exception.missingFields()).containsExactly("条款扣分：H-4.1.2");
  }

  private InspectionService service(
      InspectionRecordRepository recordRepository,
      InspectionStandardRepository standardRepository
  ) {
    return new InspectionService(
        recordRepository, null, standardRepository, null,
        "http://127.0.0.1:8000/detect", "http://127.0.0.1:8000/export", Duration.ofSeconds(1));
  }

  private void stubCanonicalEvidence(
      InspectionRecordRepository recordRepository,
      InspectionStandardRepository standardRepository,
      InspectionRecordResponse record,
      List<InspectionStandardItemResponse> standards
  ) {
    when(recordRepository.record(1L, RECORD_ID)).thenReturn(Optional.of(record));
    when(recordRepository.scoreEvidence(1L, RECORD_ID)).thenReturn(Optional.of(
        evidence(standards.size(), standards.size(), 1, VERSION_ID)));
    when(standardRepository.version(1L, VERSION_ID)).thenReturn(Optional.of(canonicalVersion()));
    when(standardRepository.items(1L, VERSION_ID)).thenReturn(standards);
  }

  private void stubScaleAdjustmentEvidence(
      InspectionRecordRepository recordRepository,
      InspectionStandardRepository standardRepository,
      InspectionRecordResponse record,
      List<InspectionStandardItemResponse> standards
  ) {
    when(recordRepository.record(1L, RECORD_ID)).thenReturn(Optional.of(record));
    when(recordRepository.scoreEvidence(1L, RECORD_ID)).thenReturn(Optional.of(
        scaleAdjustmentEvidence(standards.size())));
    when(standardRepository.version(1L, VERSION_ID)).thenReturn(Optional.of(canonicalVersion()));
    when(standardRepository.items(1L, VERSION_ID)).thenReturn(standards);
  }

  private InspectionRecordRepository.ScoreEvidence evidence(
      int snapshotCount,
      int snapshotStandardIdCount,
      int snapshotVersionCount,
      Long snapshotStandardVersionId
  ) {
    return new InspectionRecordRepository.ScoreEvidence(
        RECORD_ID, bd("200"), bd("180"), bd("196"), bd("33"), bd("63"), bd("100"),
        false, "RED_LINE_FAILED", VERSION_ID, VERSION,
        snapshotCount, snapshotStandardIdCount, snapshotVersionCount, snapshotStandardVersionId);
  }

  private InspectionStandardRepository.VersionRow canonicalVersion() {
    return new InspectionStandardRepository.VersionRow(
        VERSION_ID, "2026年巡检标准", bd("200"), bd("180"), VERSION, LocalDate.of(2026, 7, 1));
  }

  private InspectionRecordRepository.ScoreEvidence scaleAdjustmentEvidence(int snapshotCount) {
    return new InspectionRecordRepository.ScoreEvidence(
        RECORD_ID, bd("200"), bd("180"), bd("196"), bd("37"), bd("59"), bd("100"),
        true, "PASSED", VERSION_ID, VERSION,
        snapshotCount, snapshotCount, 1, VERSION_ID);
  }

  private List<InspectionStandardItemResponse> canonicalStandards() {
    List<InspectionStandardItemResponse> items = new ArrayList<>();
    long id = 1L;
    for (int index = 0; index < 40; index++) {
      items.add(standard(
          id++, "物料标准", "M-" + (index + 1),
          index == 0 ? bd("4") : index == 1 ? bd("33") : bd("0"),
          index < 8, index >= 8 && index < 11));
    }
    for (int index = 0; index < 47; index++) {
      items.add(standard(
          id++, "卫生标准", "H-" + (index + 1), index == 0 ? bd("63") : bd("0"),
          index < 10, index >= 10 && index < 16));
    }
    for (int index = 0; index < 18; index++) {
      items.add(standard(
          id++, "服务标准", "S-" + (index + 1), index == 0 ? bd("100") : bd("0"),
          index < 3, false));
    }
    return List.copyOf(items);
  }

  private List<InspectionStandardItemResponse> scaleAdjustmentStandards() {
    List<InspectionStandardItemResponse> items = new ArrayList<>();
    long id = 152L;
    for (int index = 0; index < 40; index++) {
      items.add(standard(
          id++, "物料标准", "M-" + (index + 1),
          index == 0 ? bd("4") : index == 1 ? bd("33") : bd("0"),
          index < 8, index >= 8 && index < 11));
    }
    for (int index = 0; index < 47; index++) {
      String code = index == 16 ? "H-4.1.2" : "H-" + (index + 1);
      BigDecimal score = index == 0 ? bd("61") : index == 16 ? bd("2") : bd("0");
      items.add(standard(
          id++, "卫生标准", code, score,
          index < 10, index >= 10 && index < 16));
    }
    for (int index = 0; index < 18; index++) {
      items.add(standard(
          id++, "服务标准", "S-" + (index + 1), index == 0 ? bd("100") : bd("0"),
          index < 3, false));
    }
    return List.copyOf(items);
  }

  private InspectionStandardItemResponse standard(
      long id,
      String dimension,
      String code,
      BigDecimal score,
      boolean redLine,
      boolean yellowLine
  ) {
    return new InspectionStandardItemResponse(
        id, dimension, code, code + "条款", "标准描述", score, redLine, true, (int) id,
        "现场检查", redLine ? "RED" : yellowLine ? "YELLOW" : "NORMAL");
  }

  private InspectionRecordResponse canonicalRecord(List<InspectionStandardItemResponse> standards) {
    List<InspectionItemResultResponse> snapshots = standards.stream().map(standard -> {
      boolean deducted = "M-1".equals(standard.code());
      BigDecimal deduction = deducted ? bd("4") : bd("0");
      return new InspectionItemResultResponse(
          standard.id(), standard.id(), standard.dimension(), standard.code(), standard.title(),
          standard.description(), standard.checkMethod(), standard.suggestedScore(),
          standard.suggestedScore().subtract(deduction), deduction, deducted, standard.riskLevel(),
          standard.redLine(), deducted ? "红线问题：E2E 导出回归" : null, List.of(), "店长",
          null, deducted ? "待整改" : null, null, List.of(), List.of(), (int) standard.id());
    }).toList();
    return new InspectionRecordResponse(
        RECORD_ID, "rg1", "RG-01", "荆州之星店", 1L, "茹菓", "2026-07-08", "E2E-SEED 督导",
        "茹菓", bd("200"), bd("196"), false, "[]", "[]", "[]", "正式红线导出回归样本",
        VERSION_ID, VERSION, bd("33"), bd("63"), bd("100"), "RED_LINE_FAILED", snapshots);
  }

  private InspectionRecordResponse scaleAdjustmentRecord(String photosJson) {
    List<InspectionItemResultResponse> snapshots = scaleAdjustmentStandards().stream().map(standard -> {
      boolean adjusted = "H-4.1.2".equals(standard.code());
      return new InspectionItemResultResponse(
          standard.id(), standard.id(), standard.dimension(), standard.code(), standard.title(),
          standard.description(), standard.checkMethod(), standard.suggestedScore(),
          adjusted ? bd("0") : standard.suggestedScore(), adjusted ? bd("4") : bd("0"),
          adjusted, standard.riskLevel(), standard.redLine(),
          adjusted ? "角落积灰；条款2+换算调整2=4；策略LEGACY_100_TO_200_H412_V1" : null,
          adjusted ? List.of(SCALE_ADJUSTMENT_ATTACHMENT_ID) : List.of(), null,
          null, adjusted ? "待整改" : "无需整改", null, List.of(), List.of(), (int) standard.id());
    }).toList();
    return new InspectionRecordResponse(
        RECORD_ID, "rg11", "RG-11", "花台店", 1L, "茹菓", "2026-07-28", "123",
        "茹菓", bd("200"), bd("196"), true, "[]", "[]", photosJson, "量表调整导出回归样本",
        VERSION_ID, VERSION, bd("37"), bd("59"), bd("100"), "PASSED", snapshots);
  }

  private String scaleAdjustmentPhotos(String policy, String decisionStatus) {
    return scaleAdjustmentPhotos(policy, decisionStatus, SCALE_ADJUSTMENT_ATTACHMENT_ID);
  }

  private String scaleAdjustmentPhotos(String policy, String decisionStatus, long attachmentId) {
    return """
        [{
          "attachmentId": %d,
          "reviewStatus": "accepted",
          "detection": {
            "attachmentId": %d,
            "detectionKey": "fixture-h412-scale-adjustment",
            "decisionStatus": "%s",
            "clauseId": %d,
            "clauseCode": "H-4.1.2",
            "scoreScale": 100,
            "persistedScoreScale": 200,
            "clauseDeduction": 2,
            "scaleAdjustmentDeduction": 2,
            "standardDeduction": 4,
            "finalDeduction": 4,
            "confirmedDeduction": 4,
            "deductionPolicyVersion": "%s"
          }
        }]
        """.formatted(
        attachmentId,
        attachmentId,
        decisionStatus,
        SCALE_ADJUSTMENT_CLAUSE_ID,
        policy);
  }

  private BigDecimal bd(String value) {
    return new BigDecimal(value).setScale(2);
  }
}
