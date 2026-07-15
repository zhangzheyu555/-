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

  private BigDecimal bd(String value) {
    return new BigDecimal(value).setScale(2);
  }
}
