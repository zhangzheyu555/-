package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.storage.StorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InspectionScoringServiceTest {
  private InspectionRecordRepository recordRepository;
  private InspectionStandardRepository standardRepository;
  private InspectionService service;
  private StorageService storageService;
  private AtomicReference<InspectionRecordRequest> saved;
  private AtomicReference<List<InspectionStandardSnapshot>> savedSnapshots;
  private List<InspectionStandardItemResponse> standards;

  @BeforeEach
  void setUp() {
    recordRepository = mock(InspectionRecordRepository.class);
    standardRepository = mock(InspectionStandardRepository.class);
    storageService = mock(StorageService.class);
    saved = new AtomicReference<>();
    savedSnapshots = new AtomicReference<>(List.of());
    standards = standards();
    when(standardRepository.activeVersion(1L)).thenReturn(Optional.of(
        new InspectionStandardRepository.VersionRow(
            38L, "茹菓门店品质稽核标准 2025.11.06", bd("200"), bd("180"),
            "2025.11.06", LocalDate.of(2025, 11, 6))));
    when(standardRepository.items(1L, 38L)).thenReturn(standards);
    when(recordRepository.storeExists(1L, "s1")).thenReturn(true);
    when(recordRepository.insertRepairAudit(anyLong(), anyString(), any())).thenReturn(true);
    doAnswer(invocation -> {
      saved.set(invocation.getArgument(2));
      return null;
    }).when(recordRepository).upsert(anyLong(), anyString(), any());
    doAnswer(invocation -> {
      savedSnapshots.set(List.copyOf(invocation.getArgument(2)));
      return null;
    }).when(recordRepository).replaceStandardSnapshots(anyLong(), anyString(), any());
    when(recordRepository.record(anyLong(), anyString())).thenAnswer(invocation -> {
      InspectionRecordRequest request = saved.get();
      if (request == null) {
        return Optional.empty();
      }
      return Optional.of(new InspectionRecordResponse(
          invocation.getArgument(1), request.storeId(), "001", "一店", 1L, "茹菓",
          request.inspectionDate(), request.inspector(), request.brand(), request.fullScore(),
          request.score(), Boolean.TRUE.equals(request.passed()), request.deductionsJson(),
          request.redlinesJson(), request.photosJson(), request.note(), request.standardVersionId(),
          request.standardVersion(), request.materialScore(), request.hygieneScore(),
          request.serviceScore(), request.resultCode(), savedItemResults()));
    });
    service = new InspectionService(
        recordRepository, null, standardRepository, storageService,
        "http://127.0.0.1:8000/detect", "http://127.0.0.1:8000/export", Duration.ofSeconds(1));
  }

  @Test
  void calculatesExactCategoryScoresAndPassBoundary() {
    InspectionRecordResponse at180 = service.save(user(), null, request(resultsWithServiceDeduction(bd("20"))));

    assertThat(at180.fullScore()).isEqualByComparingTo("200.00");
    assertThat(at180.materialScore()).isEqualByComparingTo("37.00");
    assertThat(at180.hygieneScore()).isEqualByComparingTo("63.00");
    assertThat(at180.serviceScore()).isEqualByComparingTo("80.00");
    assertThat(at180.score()).isEqualByComparingTo("180.00");
    assertThat(at180.resultCode()).isEqualTo("PASSED");

    service.save(user(), null, request(resultsWithServiceDeduction(bd("21"))));
    assertThat(saved.get().score()).isEqualByComparingTo("179.00");
    assertThat(saved.get().resultCode()).isEqualTo("FAILED");
  }

  @Test
  void redIssueFailsWithoutChangingActualScoreAndYellowNeverVetoes() {
    List<InspectionItemResultRequest> results = new ArrayList<>(fullScoreResults());
    results.set(0, result(standards.get(0), bd("0"), true, "命中红线"));
    InspectionRecordResponse red = service.save(user(), null, request(results));

    assertThat(red.score()).isEqualByComparingTo("200.00");
    assertThat(red.resultCode()).isEqualTo("RED_LINE_FAILED");
    assertThat(red.passed()).isFalse();
    assertThat(saved.get().redlinesJson()).contains("M-RED-1", "命中红线");

    results = new ArrayList<>(fullScoreResults());
    results.set(21, result(standards.get(21), standards.get(21).suggestedScore(), true, "黄线提醒"));
    InspectionRecordResponse yellow = service.save(user(), null, request(results));
    assertThat(yellow.score()).isEqualByComparingTo("200.00");
    assertThat(yellow.resultCode()).isEqualTo("PASSED");
  }

  @Test
  void rejectsWrongCategoryCountsEvenWhenTotalCountAndCategoryScoresMatch() {
    List<InspectionStandardItemResponse> invalid = invalidStandardsWithMatchingScores();
    when(standardRepository.items(1L, 38L)).thenReturn(invalid);

    assertThatThrownBy(() -> service.save(user(), null, request(
        invalid.stream().map(item -> result(item, item.suggestedScore(), false, null)).toList())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException business = (BusinessException) error;
          assertThat(business.getCode()).isEqualTo("INSPECTION_STANDARD_INVALID");
          assertThat(business.getMessage()).contains(
              "物料应为40条/37分，当前43条/37分",
              "服务应为18条/100分，当前15条/100分"
          );
        });
  }

  @Test
  void writesAppendOnlyRecalculationForCompleteWrongVersionSnapshot() {
    useCorrectedActiveVersion();
    InspectionRecordResponse historical = wrongVersionRecord("history-complete");
    when(recordRepository.records(1L, null, null, null, null, null)).thenReturn(List.of(historical));
    when(recordRepository.snapshotItems(1L, historical.id())).thenReturn(snapshotResults(standards));

    InspectionHistoryRepairResponse response = service.repairHistory(user());

    assertThat(response.scanned()).isEqualTo(1);
    assertThat(response.recalculated()).isEqualTo(1);
    assertThat(response.manualReview()).isZero();
    ArgumentCaptor<InspectionResultRepairWrite> repair =
        ArgumentCaptor.forClass(InspectionResultRepairWrite.class);
    verify(recordRepository).insertRepairAudit(eq(1L), eq(historical.id()), repair.capture());
    assertThat(repair.getValue().repairStatus()).isEqualTo("RECALCULATED");
    assertThat(repair.getValue().originalScore()).isEqualByComparingTo("98.00");
    assertThat(repair.getValue().repairedScore()).isEqualByComparingTo("200.00");
    assertThat(repair.getValue().repairedMaterialScore()).isEqualByComparingTo("37.00");
    assertThat(repair.getValue().repairedHygieneScore()).isEqualByComparingTo("63.00");
    assertThat(repair.getValue().repairedServiceScore()).isEqualByComparingTo("100.00");
    assertThat(repair.getValue().repairedResultCode()).isEqualTo("PASSED");
    assertThat(repair.getValue().snapshotItemCount()).isEqualTo(105);
  }

  @Test
  void marksIncompleteWrongVersionSnapshotForManualReviewAndIsIdempotent() {
    useCorrectedActiveVersion();
    InspectionRecordResponse incomplete = wrongVersionRecord("history-incomplete");
    InspectionRecordResponse alreadyProcessed = wrongVersionRecord("history-existing");
    when(recordRepository.records(1L, null, null, null, null, null))
        .thenReturn(List.of(incomplete, alreadyProcessed));
    when(recordRepository.snapshotItems(1L, incomplete.id()))
        .thenReturn(snapshotResults(standards).subList(0, 104));
    when(recordRepository.snapshotItems(1L, alreadyProcessed.id()))
        .thenReturn(snapshotResults(standards));
    when(recordRepository.insertRepairAudit(anyLong(), anyString(), any()))
        .thenReturn(true, false);

    InspectionHistoryRepairResponse response = service.repairHistory(user());

    assertThat(response.scanned()).isEqualTo(2);
    assertThat(response.manualReview()).isEqualTo(1);
    assertThat(response.skipped()).isEqualTo(1);
    assertThat(response.manualReviewRecordIds()).containsExactly(incomplete.id());
    ArgumentCaptor<InspectionResultRepairWrite> repair =
        ArgumentCaptor.forClass(InspectionResultRepairWrite.class);
    verify(recordRepository).insertRepairAudit(eq(1L), eq(incomplete.id()), repair.capture());
    assertThat(repair.getValue().repairStatus()).isEqualTo("MANUAL_REVIEW");
    assertThat(repair.getValue().repairedScore()).isNull();
    assertThat(repair.getValue().repairedMaterialScore()).isNull();
    assertThat(repair.getValue().repairedHygieneScore()).isNull();
    assertThat(repair.getValue().repairedServiceScore()).isNull();
    assertThat(repair.getValue().repairReason()).contains("应为105条，实际为104条");
  }

  @Test
  void uniqueConflictIsAnIdempotentSkipWithoutDuplicateOperationLog() {
    useCorrectedActiveVersion();
    InspectionRecordResponse historical = wrongVersionRecord("history-race");
    when(recordRepository.records(1L, null, null, null, null, null)).thenReturn(List.of(historical));
    when(recordRepository.snapshotItems(1L, historical.id())).thenReturn(snapshotResults(standards));
    when(recordRepository.insertRepairAudit(eq(1L), eq(historical.id()), any())).thenReturn(false);

    InspectionHistoryRepairResponse response = service.repairHistory(user());

    assertThat(response.scanned()).isEqualTo(1);
    assertThat(response.recalculated()).isZero();
    assertThat(response.manualReview()).isZero();
    assertThat(response.skipped()).isEqualTo(1);
    verify(recordRepository, never()).logAction(
        anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void savesAll105SnapshotRows() {
    service.save(user(), "insp-105", request(fullScoreResults()));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<InspectionStandardSnapshot>> snapshots = ArgumentCaptor.forClass(List.class);
    verify(recordRepository).replaceStandardSnapshots(anyLong(), anyString(), snapshots.capture());
    assertThat(snapshots.getValue()).hasSize(105);
    assertThat(snapshots.getValue()).extracting(InspectionStandardSnapshot::standardVersion)
        .containsOnly("2025.11.06");
  }

  @Test
  void newAndHistoricalRecordsMustUseTheCurrentCompleteStandard() {
    assertThatThrownBy(() -> service.save(user(), null, request(List.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("INSPECTION_ITEMS_INCOMPLETE"));

    when(recordRepository.record(1L, "legacy-1")).thenReturn(Optional.of(legacyRecord("legacy-1")));
    assertThatThrownBy(() -> service.save(user(), "legacy-1", request(List.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("INSPECTION_ITEMS_INCOMPLETE"));
  }

  @Test
  void rejectsHundredPointPayloadOnTheFormalSavePath() {
    InspectionRecordRequest source = request(fullScoreResults());
    InspectionRecordRequest legacyScale = new InspectionRecordRequest(
        source.storeId(), source.inspectionDate(), source.inspector(), source.brand(),
        bd("100"), bd("98"), true, source.deductionsJson(), source.redlinesJson(),
        source.photosJson(), source.note(), source.standardVersionId(), source.standardVersion(),
        source.materialScore(), source.hygieneScore(), source.serviceScore(),
        source.resultCode(), source.itemResults());

    assertThatThrownBy(() -> service.save(user(), null, legacyScale))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("INSPECTION_SCORE_SCALE_INVALID"));
    verify(recordRepository, never()).upsert(anyLong(), anyString(), any());
  }

  @Test
  void rebindsNumericAttachmentIdsFromPhotosJsonAndNormalizesChineseStatus() {
    List<InspectionItemResultRequest> results = new ArrayList<>(fullScoreResults());
    InspectionStandardItemResponse changed = standards.get(21);
    results.set(21, new InspectionItemResultRequest(
        changed.id(), BigDecimal.ZERO.setScale(2), true, "需要整改", List.of(999L),
        "店长", LocalDate.of(2026, 7, 15), "已完成", null, List.of(), List.of()));
    InspectionRecordRequest request = request(results, "[{\"attachmentId\":999},{\"attachmentId\":999}]");

    service.save(user(), null, request);

    verify(storageService).rebindInspectionAttachments(
        any(), eq("s1"), anyString(), argThat(ids -> ids.size() == 1 && ids.contains(999L)));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<InspectionStandardSnapshot>> snapshots = ArgumentCaptor.forClass(List.class);
    verify(recordRepository).replaceStandardSnapshots(anyLong(), anyString(), snapshots.capture());
    assertThat(snapshots.getValue()).filteredOn(snapshot -> snapshot.standardId().equals(changed.id()))
        .extracting(InspectionStandardSnapshot::rectificationStatus)
        .containsExactly("COMPLETED");
  }

  @Test
  void preservesTheAuthoritativeFourPointDeductionSeparatelyFromActualScore() {
    List<InspectionItemResultRequest> results = new ArrayList<>(fullScoreResults());
    InspectionStandardItemResponse hygieneClause = standards.get(21);
    assertThat(hygieneClause.suggestedScore()).isEqualByComparingTo("4.00");
    results.set(21, result(hygieneClause, bd("0"), true, "店铺内部卫生未达标"));

    InspectionRecordResponse response = service.save(user(), "insp-four-point", request(results));

    assertThat(response.score()).isEqualByComparingTo("196.00");
    InspectionStandardSnapshot snapshot = savedSnapshots.get().stream()
        .filter(item -> item.standardId().equals(hygieneClause.id()))
        .findFirst().orElseThrow();
    assertThat(snapshot.suggestedScore()).isEqualByComparingTo("4.00");
    assertThat(snapshot.actualScore()).isEqualByComparingTo("0.00");
    assertThat(snapshot.actualDeductionScore()).isEqualByComparingTo("4.00");
  }

  @Test
  void unmatchedAiEvidenceDoesNotChangeScoreOrCreateDeductionSnapshots() {
    String unmatchedPhotos = """
        [{
          "attachmentId": 1008,
          "fileName": "微信图片_未匹配.jpg",
          "reviewStatus": "pending",
          "detection": {
            "image_id": "unmatched-ai-photo",
            "detections": [{"class_name":"unknown_scene","confidence":0.93,"bbox":[0,0,10,10]}]
          }
        }]
        """;

    InspectionRecordResponse response = service.save(
        user(), "insp-unmatched", request(withEvidence(fullScoreResults(), "M-RED-1", 1008L), unmatchedPhotos));

    assertThat(response.score()).isEqualByComparingTo("200.00");
    assertThat(response.hygieneScore()).isEqualByComparingTo("63.00");
    assertThat(saved.get().photosJson()).contains("\"decisionStatus\":\"UNMATCHED\"");
    assertThat(savedSnapshots.get()).allSatisfy(snapshot -> {
      assertThat(snapshot.actualScore()).isEqualByComparingTo(snapshot.suggestedScore());
      assertThat(snapshot.actualDeductionScore()).isEqualByComparingTo("0.00");
    });
    verify(recordRepository, never()).logAction(
        anyLong(), anyLong(), anyString(), eq("inspection_detection_confirm"),
        anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void convertsLegacyDetectionOnceAndPersistsFourPointDeductionOnTwoPointClause() throws Exception {
    java.util.Map<String, Object> rawEvidence = new java.util.LinkedHashMap<>();
    rawEvidence.put("image_id", "img-1");
    rawEvidence.put("deduction_score", 2);
    rawEvidence.put("finalDeduction", 999);
    rawEvidence.put("clauseId", 999999);
    rawEvidence.put("detections", List.of(
        java.util.Map.of("class_name", "floor_litter", "confidence", 0.91,
            "bbox", List.of(0, 0, 10, 10)),
        java.util.Map.of("class_name", "floor_litter", "confidence", 0.85,
            "bbox", List.of(0, 0, 10, 10))));
    String draftKey = service.detectionSuggestions(user(), new InspectionDetectionBindingRequest(
        null, null, bd("100"), List.of(rawEvidence), null))
        .getFirst().get("detectionKey").toString();
    java.util.Map<String, Object> draftConfirmed = service.confirmDraftDetection(
        user(), draftKey, new InspectionDraftDetectionConfirmRequest(rawEvidence));
    assertThat(draftConfirmed.get("scoreScale").toString()).isEqualTo("100.00");
    assertThat(draftConfirmed.get("confirmedDeduction").toString()).isEqualTo("4.00");
    assertThat(draftConfirmed.get("clauseDeduction").toString()).isEqualTo("2.00");
    assertThat(draftConfirmed.get("scaleAdjustmentDeduction").toString()).isEqualTo("2.00");
    assertThat(draftConfirmed.get("deductionPolicyVersion"))
        .isEqualTo("LEGACY_100_TO_200_H412_V1");
    assertThat(draftConfirmed.get("clauseCode")).isEqualTo("H-4.1.2");
    assertThat(draftConfirmed.get("decisionStatus")).isEqualTo("CONFIRMED");

    String photosJson = """
        [{
          "attachmentId": 999,
          "reviewStatus": "accepted",
          "detection": {
            "image_id": "img-1",
            "deduction_score": 2,
            "detections": [
              {"class_name":"floor_litter","confidence":0.91,"bbox":[0,0,10,10]},
              {"class_name":"floor_litter","confidence":0.85,"bbox":[0,0,10,10]}
            ]
          }
        }]
        """;

    InspectionRecordResponse response = service.save(
        user(), null, request(fullScoreResults(), photosJson));

    assertThat(response.hygieneScore()).isEqualByComparingTo("59.00");
    assertThat(response.score()).isEqualByComparingTo("196.00");
    assertThat(saved.get().photosJson())
        .contains("\"scoreScale\":100.00", "\"persistedScoreScale\":200.00")
        .contains("\"confirmedDeduction\":4.00", "\"detection_count\":1")
        .contains("\"clauseDeduction\":2.00", "\"scaleAdjustmentDeduction\":2.00")
        .contains("\"deductionPolicyVersion\":\"LEGACY_100_TO_200_H412_V1\"");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<InspectionStandardSnapshot>> snapshots = ArgumentCaptor.forClass(List.class);
    verify(recordRepository).replaceStandardSnapshots(anyLong(), anyString(), snapshots.capture());
    InspectionStandardSnapshot detected = snapshots.getValue().stream()
        .filter(item -> "H-4.1.2".equals(item.standardCode()))
        .findFirst().orElseThrow();
    assertThat(detected.suggestedScore()).isEqualByComparingTo("2.00");
    assertThat(detected.actualScore()).isEqualByComparingTo("0.00");
    assertThat(detected.actualDeductionScore()).isEqualByComparingTo("4.00");
    assertThat(detected.problemDescription()).contains("条款2+换算调整2=4");
    BigDecimal snapshotActualTotal = snapshots.getValue().stream()
        .map(InspectionStandardSnapshot::actualScore)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    List<Map<String, Object>> persistedPhotos = new ObjectMapper().readValue(
        saved.get().photosJson(), new TypeReference<>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> persistedDetection =
        (Map<String, Object>) persistedPhotos.getFirst().get("detection");
    BigDecimal persistedScaleAdjustment = new BigDecimal(
        persistedDetection.get("scaleAdjustmentDeduction").toString());
    assertThat(snapshotActualTotal).isEqualByComparingTo("198.00");
    assertThat(snapshotActualTotal.subtract(persistedScaleAdjustment))
        .isEqualByComparingTo(response.score());
    verify(recordRepository).logAction(
        eq(1L), eq(1L), eq("督导"), eq("inspection_detection_confirm"),
        anyString(), eq("s1"), eq("2026-07-13"),
        argThat(reason -> reason.contains("条款2+换算调整2=4")));

    String recordId = response.id();
    String detectionKey = service.detectionSuggestions(user(), new InspectionDetectionBindingRequest(
        null, null, bd("100"), List.of(java.util.Map.of(
            "image_id", "img-1", "deduction_score", 2,
            "detections", List.of(java.util.Map.of(
                "class_name", "floor_litter", "confidence", 0.91,
                "bbox", List.of(0, 0, 10, 10))))), null))
        .getFirst().get("detectionKey").toString();

    InspectionDetectionDecisionResponse repeatedConfirm = service.confirmDetection(
        user(), recordId, detectionKey, new InspectionDetectionDecisionRequest(0L));
    assertThat(repeatedConfirm.changed()).isFalse();
    assertThat(repeatedConfirm.record().score()).isEqualByComparingTo("196.00");
    assertThat(new BigDecimal(repeatedConfirm.detection().get("clauseDeduction").toString()))
        .isEqualByComparingTo("2.00");
    assertThat(new BigDecimal(
        repeatedConfirm.detection().get("scaleAdjustmentDeduction").toString()))
        .isEqualByComparingTo("2.00");
    assertThat(repeatedConfirm.detection().get("deductionPolicyVersion"))
        .isEqualTo("LEGACY_100_TO_200_H412_V1");

    InspectionDetectionDecisionResponse revoked = service.revokeDetection(
        user(), recordId, detectionKey, new InspectionDetectionDecisionRequest(1L));
    assertThat(revoked.changed()).isTrue();
    assertThat(revoked.record().hygieneScore()).isEqualByComparingTo("63.00");
    assertThat(revoked.record().score()).isEqualByComparingTo("200.00");

    InspectionDetectionDecisionResponse repeatedRevoke = service.revokeDetection(
        user(), recordId, detectionKey, new InspectionDetectionDecisionRequest(1L));
    assertThat(repeatedRevoke.changed()).isFalse();
  }

  @Test
  void savedConfirmIgnoresTamperedGeneralClauseDeductionAndUsesActiveRule() throws Exception {
    String pendingPhotos = """
        [{
          "attachmentId": 1001,
          "reviewStatus": "pending",
          "detection": {
            "image_id": "img-general",
            "deduction_project": "H-2",
            "legacyDeduction": 999,
            "finalDeduction": 999,
            "detections": [
              {"class_name":"general_hygiene_issue","confidence":0.88,"bbox":[1,1,8,8]}
            ]
          }
        }]
        """;
    InspectionRecordResponse created = service.save(
        user(), "insp-general", request(withEvidence(fullScoreResults(), "H-2", 1001L), pendingPhotos));
    assertThat(created.score()).isEqualByComparingTo("200.00");

    ObjectMapper mapper = new ObjectMapper();
    List<Map<String, Object>> photos = mapper.readValue(
        saved.get().photosJson(), new TypeReference<>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> detection = (Map<String, Object>) photos.getFirst().get("detection");
    String key = String.valueOf(detection.get("detectionKey"));
    detection.put("legacyDeduction", 999);
    detection.put("deduction_score", 999);
    detection.put("scoreScale", 999);
    detection.put("convertedDeduction200", 999);
    detection.put("standardDeduction", 999);
    detection.put("clauseDeduction", 999);
    detection.put("scaleAdjustmentDeduction", 999);
    detection.put("deductionPolicyVersion", "CLIENT_OVERRIDE");
    detection.put("finalDeduction", 999);
    detection.put("confirmedDeduction", 999);
    detection.put("clauseId", 999999);
    saved.set(withPhotos(saved.get(), mapper.writeValueAsString(photos)));

    InspectionDetectionDecisionResponse confirmed = service.confirmDetection(
        user(), created.id(), key, new InspectionDetectionDecisionRequest(0L));

    assertThat(confirmed.changed()).isTrue();
    assertThat(confirmed.detection().get("clauseCode")).isEqualTo("H-2");
    assertThat(confirmed.detection().get("confirmedDeduction").toString()).isEqualTo("1.00");
    assertThat(confirmed.detection().get("clauseDeduction").toString()).isEqualTo("1.00");
    assertThat(confirmed.detection().get("scaleAdjustmentDeduction").toString())
        .isEqualTo("0.00");
    assertThat(confirmed.detection().get("deductionPolicyVersion"))
        .isEqualTo("ACTIVE_CLAUSE_SCORE_V1");
    assertThat(confirmed.record().hygieneScore()).isEqualByComparingTo("62.00");
    assertThat(confirmed.record().score()).isEqualByComparingTo("199.00");
    assertThat(saved.get().photosJson()).contains("\"decisionStatus\":\"CONFIRMED\"");
    List<Map<String, Object>> confirmedPhotos = mapper.readValue(
        saved.get().photosJson(), new TypeReference<>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> confirmedNode =
        (Map<String, Object>) confirmedPhotos.getFirst().get("detection");
    assertThat(confirmedNode.get("decisionStatus")).isEqualTo("CONFIRMED");
    InspectionDetectionDecisionResponse replayed = service.confirmDetection(
        user(), created.id(), key, new InspectionDetectionDecisionRequest(0L));
    assertThat(replayed.changed()).isFalse();
    assertThat(replayed.record().score()).isEqualByComparingTo("199.00");
    InspectionStandardSnapshot persisted = savedSnapshots.get().stream()
        .filter(item -> "H-2".equals(item.standardCode())).findFirst().orElseThrow();
    assertThat(persisted.actualDeductionScore()).isEqualByComparingTo("1.00");
  }

  @Test
  void manualAdjustmentPersistsServerDerivedOriginalAndAdjustedDeductions() throws Exception {
    String pendingPhotos = """
        [{
          "attachmentId": 1002,
          "reviewStatus": "pending",
          "detection": {
            "image_id": "img-adjust",
            "deduction_project": "H-2",
            "finalDeduction": 999,
            "manualAdjustmentOriginalDeduction": 999,
            "manualAdjustmentAdjustedDeduction": 999,
            "manualAdjustmentReason": "客户端伪造",
            "detections": [
              {"class_name":"general_hygiene_issue","confidence":0.89,"bbox":[1,1,7,7]}
            ]
          }
        }]
        """;
    InspectionRecordResponse created = service.save(
        user(), "insp-adjust", request(withEvidence(fullScoreResults(), "H-2", 1002L), pendingPhotos));
    ObjectMapper mapper = new ObjectMapper();
    List<Map<String, Object>> photos = mapper.readValue(
        saved.get().photosJson(), new TypeReference<>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> pending = (Map<String, Object>) photos.getFirst().get("detection");
    String key = String.valueOf(pending.get("detectionKey"));
    long targetClauseId = standards.stream()
        .filter(item -> "S-2".equals(item.code()))
        .findFirst().orElseThrow().id();

    InspectionDetectionDecisionResponse adjusted = service.adjustDetection(
        user(), created.id(), key,
        new InspectionDetectionAdjustmentRequest(targetClauseId, "人工核对后改为服务条款"));

    assertThat(adjusted.changed()).isTrue();
    assertThat(adjusted.record().score()).isEqualByComparingTo("190.00");
    assertThat(new BigDecimal(
        adjusted.detection().get("manualAdjustmentOriginalDeduction").toString()))
        .isEqualByComparingTo("1.00");
    assertThat(new BigDecimal(
        adjusted.detection().get("manualAdjustmentAdjustedDeduction").toString()))
        .isEqualByComparingTo("10.00");
    assertThat(adjusted.detection().get("manualAdjustmentReason"))
        .isEqualTo("人工核对后改为服务条款");
    assertThat(adjusted.detection().get("deductionPolicyVersion"))
        .isEqualTo("ACTIVE_CLAUSE_SCORE_V1");
    verify(recordRepository).logAction(
        eq(1L), eq(1L), eq("督导"), eq("inspection_detection_manual_adjust"),
        eq(created.id()), eq("s1"), eq("2026-07-13"),
        argThat(reason -> reason.contains("原扣分1→调整后扣分10")
            && reason.contains("原因：人工核对后改为服务条款")));
  }

  @Test
  void serverRulesKeepFiveAndTenPointClausesWithoutGlobalCap() {
    assertServerClauseDeduction("S-1", "img-five", "5.00");
    assertServerClauseDeduction("S-2", "img-ten", "10.00");
  }

  private void assertServerClauseDeduction(String clauseCode, String imageId, String expected) {
    Map<String, Object> evidence = new java.util.LinkedHashMap<>();
    evidence.put("image_id", imageId);
    evidence.put("deduction_project", clauseCode);
    evidence.put("legacyDeduction", 999);
    evidence.put("finalDeduction", 999);
    evidence.put("detections", List.of(Map.of(
        "class_name", "general_service_issue", "confidence", 0.9,
        "bbox", List.of(2, 2, 9, 9))));
    String key = service.detectionSuggestions(user(), new InspectionDetectionBindingRequest(
        null, null, bd("100"), List.of(evidence), null))
        .getFirst().get("detectionKey").toString();
    Map<String, Object> confirmed = service.confirmDraftDetection(
        user(), key, new InspectionDraftDetectionConfirmRequest(evidence));
    assertThat(confirmed.get("clauseCode")).isEqualTo(clauseCode);
    assertThat(confirmed.get("standardDeduction").toString()).isEqualTo(expected);
    assertThat(confirmed.get("confirmedDeduction").toString()).isEqualTo(expected);
    assertThat(confirmed.get("clauseDeduction").toString()).isEqualTo(expected);
    assertThat(confirmed.get("scaleAdjustmentDeduction").toString()).isEqualTo("0.00");
    assertThat(confirmed.get("deductionPolicyVersion")).isEqualTo("ACTIVE_CLAUSE_SCORE_V1");
  }

  private InspectionRecordRequest withPhotos(InspectionRecordRequest source, String photosJson) {
    return new InspectionRecordRequest(
        source.storeId(), source.inspectionDate(), source.inspector(), source.brand(),
        source.fullScore(), source.score(), source.passed(), source.deductionsJson(),
        source.redlinesJson(), photosJson, source.note(), source.standardVersionId(),
        source.standardVersion(), source.materialScore(), source.hygieneScore(),
        source.serviceScore(), source.resultCode(), source.itemResults());
  }

  private List<InspectionItemResultResponse> savedItemResults() {
    return savedSnapshots.get().stream().map(snapshot -> new InspectionItemResultResponse(
        snapshot.standardId(), snapshot.standardId(), snapshot.dimension(), snapshot.standardCode(),
        snapshot.standardTitle(), snapshot.standardDescription(), snapshot.checkMethod(),
        snapshot.suggestedScore(), snapshot.actualScore(), snapshot.actualDeductionScore(),
        snapshot.actualDeductionScore().signum() > 0,
        snapshot.riskLevel(), snapshot.redLine(), snapshot.problemDescription(),
        snapshot.photoAttachmentIds(), snapshot.responsiblePerson(), snapshot.rectificationDeadline(),
        snapshot.rectificationStatus(), snapshot.reviewResult(), snapshot.beforePhotoAttachmentIds(),
        snapshot.afterPhotoAttachmentIds(), snapshot.sortOrder())).toList();
  }

  private InspectionRecordRequest request(List<InspectionItemResultRequest> results) {
    return request(results, null);
  }

  private InspectionRecordRequest request(List<InspectionItemResultRequest> results, String photosJson) {
    return new InspectionRecordRequest(
        "s1", "2026-07-13", "督导", "茹菓", null, null, null,
        null, null, photosJson, "", 38L, null, null, null, null, null, results);
  }

  private List<InspectionItemResultRequest> withEvidence(
      List<InspectionItemResultRequest> source,
      String standardCode,
      long attachmentId
  ) {
    int index = -1;
    for (int i = 0; i < standards.size(); i++) {
      if (standardCode.equals(standards.get(i).code())) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      throw new IllegalArgumentException("测试条款不存在：" + standardCode);
    }
    List<InspectionItemResultRequest> results = new ArrayList<>(source);
    InspectionItemResultRequest current = results.get(index);
    results.set(index, new InspectionItemResultRequest(
        current.standardItemId(), current.actualScore(), current.issueFound(), current.deductionReason(),
        List.of(attachmentId), current.responsiblePerson(), current.rectificationDeadline(),
        current.rectificationStatus(), current.reviewResult(), current.beforePhotoAttachmentIds(),
        current.afterPhotoAttachmentIds()));
    return results;
  }

  private InspectionRecordResponse legacyRecord(String id) {
    return new InspectionRecordResponse(
        id, "s1", "001", "一店", 1L, "茹菓", "2026-07-13", "督导", "茹菓",
        bd("100"), bd("100"), true, "[]", "[]", "[]", null,
        null, null, null, null, null, null, List.of());
  }

  private List<InspectionItemResultRequest> fullScoreResults() {
    return standards.stream().map(item -> result(item, item.suggestedScore(), false, null)).toList();
  }

  private List<InspectionItemResultRequest> resultsWithServiceDeduction(BigDecimal deduction) {
    List<InspectionItemResultRequest> results = new ArrayList<>(fullScoreResults());
    BigDecimal remaining = deduction;
    for (int index = 0; index < standards.size() && remaining.signum() > 0; index++) {
      InspectionStandardItemResponse item = standards.get(index);
      if (!"服务标准".equals(item.dimension())) {
        continue;
      }
      BigDecimal deduct = remaining.min(item.suggestedScore());
      results.set(index, result(item, item.suggestedScore().subtract(deduct), true, "服务扣分"));
      remaining = remaining.subtract(deduct);
    }
    return results;
  }

  private InspectionItemResultRequest result(
      InspectionStandardItemResponse item, BigDecimal actualScore, boolean issueFound, String reason) {
    return new InspectionItemResultRequest(
        item.id(), actualScore, issueFound, reason, List.of(), null, null, null, null, List.of(), List.of());
  }

  private List<InspectionStandardItemResponse> standards() {
    List<InspectionStandardItemResponse> items = new ArrayList<>();
    long id = 1;
    for (int i = 0; i < 21; i++) {
      items.add(item(id++, "物料标准", "M-RED-" + (i + 1), bd("0"), "RED", true));
    }
    for (int i = 0; i < 9; i++) {
      items.add(item(id++, "物料标准", "M-YELLOW-" + (i + 1), bd("4"), "YELLOW", false));
    }
    for (int i = 0; i < 10; i++) {
      items.add(item(id++, "物料标准", "M-" + (i + 1), bd(i == 0 ? "1" : "0"), "NORMAL", false));
    }
    for (int i = 0; i < 47; i++) {
      String code = i == 0 ? "H-4.1.2" : "H-" + (i + 1);
      String score = i == 0 ? "2" : i == 46 ? "16" : "1";
      items.add(item(id++, "卫生标准", code, bd(score), "NORMAL", false));
    }
    for (int i = 0; i < 18; i++) {
      String score = i == 1 ? "10" : i == 17 ? "10" : "5";
      items.add(item(id++, "服务标准", "S-" + (i + 1), bd(score), "NORMAL", false));
    }
    return items;
  }

  private void useCorrectedActiveVersion() {
    when(standardRepository.activeVersion(1L)).thenReturn(Optional.of(
        new InspectionStandardRepository.VersionRow(
            40L, "茹菓门店品质稽核标准 2025.11.06-R1", bd("200"), bd("180"),
            "2025.11.06-R1", LocalDate.of(2025, 11, 6))));
    when(standardRepository.items(1L, 40L)).thenReturn(standards);
  }

  private InspectionRecordResponse wrongVersionRecord(String id) {
    return new InspectionRecordResponse(
        id, "s1", "001", "一店", 1L, "茹菓", "2026-01-15", "督导", "茹菓",
        bd("200"), bd("98"), true, "[]", "[]", "[]", null,
        38L, "2025.11.06", null, null, null, "PASSED", List.of());
  }

  private List<InspectionItemResultResponse> snapshotResults(
      List<InspectionStandardItemResponse> sourceStandards
  ) {
    return sourceStandards.stream().map(item -> new InspectionItemResultResponse(
        item.id(), item.id(), item.dimension(), item.code(), item.title(), item.description(),
        item.checkMethod(), item.suggestedScore(), item.suggestedScore(), bd("0"), false,
        item.riskLevel(), item.redLine(), null, List.of(), null, null, "NOT_REQUIRED", null,
        List.of(), List.of(), item.sortOrder()
    )).toList();
  }

  private List<InspectionStandardItemResponse> invalidStandardsWithMatchingScores() {
    List<InspectionStandardItemResponse> items = new ArrayList<>();
    long id = 1000;
    for (int i = 0; i < 21; i++) {
      items.add(item(id++, "物料标准", "M-RED-X-" + i, bd("0"), "RED", true));
    }
    for (int i = 0; i < 9; i++) {
      items.add(item(id++, "物料标准", "M-YELLOW-X-" + i, bd("4"), "YELLOW", false));
    }
    for (int i = 0; i < 13; i++) {
      items.add(item(id++, "物料标准", "M-X-" + i, bd(i == 0 ? "1" : "0"), "NORMAL", false));
    }
    for (int i = 0; i < 47; i++) {
      items.add(item(id++, "卫生标准", "H-X-" + i, bd(i == 46 ? "17" : "1"), "NORMAL", false));
    }
    for (int i = 0; i < 15; i++) {
      items.add(item(id++, "服务标准", "S-X-" + i, bd(i == 14 ? "30" : "5"), "NORMAL", false));
    }
    return items;
  }

  private InspectionStandardItemResponse item(
      long id, String dimension, String code, BigDecimal score, String risk, boolean red) {
    return new InspectionStandardItemResponse(
        id, dimension, code, code, "内容", score, red, true, (int) id, "方法", risk);
  }

  private BigDecimal bd(String value) {
    return new BigDecimal(value).setScale(2);
  }

  private AuthUser user() {
    return new AuthUser(1L, 1L, "default", "supervisor", "", "督导", "SUPERVISOR", null, true);
  }
}
