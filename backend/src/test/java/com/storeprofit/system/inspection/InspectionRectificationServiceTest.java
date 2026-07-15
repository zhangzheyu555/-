package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.inspection.InspectionRectificationRepository.InspectionRectificationRecord;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.storage.StorageService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InspectionRectificationServiceTest {
  private AccessControlService accessControl;
  private InspectionRecordRepository inspectionRecordRepository;
  private InspectionRectificationRepository rectificationRepository;
  private StorageService storageService;
  private InspectionRectificationService service;

  @BeforeEach
  void setUp() {
    accessControl = mock(AccessControlService.class);
    inspectionRecordRepository = mock(InspectionRecordRepository.class);
    rectificationRepository = mock(InspectionRectificationRepository.class);
    storageService = mock(StorageService.class);
    service = new InspectionRectificationService(
        accessControl, inspectionRecordRepository, rectificationRepository, storageService);
  }

  @Test
  void wrongRoleIsRejectedBeforeAnyInspectionOrAttachmentLookup() {
    AuthUser finance = new AuthUser(
        12L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true);
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "role is not allowed", HttpStatus.FORBIDDEN);
    doThrow(forbidden).when(accessControl).requireInspectionRectificationSubmit(finance);

    assertThatThrownBy(() -> service.submit(
        finance, "inspection-1", new InspectionRectificationSubmitRequest("done", List.of(81L))))
        .isSameAs(forbidden);

    verify(accessControl).requireInspectionRead(finance);
    verify(accessControl).requireInspectionRectificationSubmit(finance);
    verifyNoInteractions(inspectionRecordRepository, rectificationRepository, storageService);
  }

  @Test
  void crossStoreManagerSubmissionIsRejectedBeforeTheInspectionIsLockedOrWorkflowIsCreated() {
    AuthUser manager = manager();
    InspectionRecordResponse record = record("inspection-other-store", "store-b");
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "inspection belongs to another store", HttpStatus.FORBIDDEN);
    when(inspectionRecordRepository.record(1L, "inspection-other-store")).thenReturn(Optional.of(record));
    doThrow(forbidden).when(accessControl).requireStoreAccess(
        eq(manager), eq(DataScopeDomains.INSPECTION), eq("store-b"), anyString());

    assertThatThrownBy(() -> service.submit(
        manager, "inspection-other-store", new InspectionRectificationSubmitRequest("done", List.of(81L))))
        .isSameAs(forbidden);

    verify(accessControl).requireInspectionRead(manager);
    verify(accessControl).requireInspectionRectificationSubmit(manager);
    verify(inspectionRecordRepository).record(1L, "inspection-other-store");
    verify(inspectionRecordRepository, never()).lockRecord(anyLong(), anyString());
    verifyNoInteractions(rectificationRepository, storageService);
  }

  @Test
  void submissionWritesOnlyTheDedicatedWorkflowAndAuditRecordsNotHistoricalInspectionScoresOrSnapshots() {
    AuthUser manager = manager();
    InspectionRecordResponse record = record("inspection-1", "store-a");
    InspectionRectificationRecord pending = rectification(
        "rectification-1", "inspection-1", "store-a", InspectionRectificationStatus.PENDING_SUBMISSION);
    InspectionRectificationRecord submitted = rectification(
        "rectification-1", "inspection-1", "store-a", InspectionRectificationStatus.PENDING_REVIEW);
    when(inspectionRecordRepository.record(1L, "inspection-1")).thenReturn(Optional.of(record));
    when(rectificationRepository.findForUpdate(1L, "inspection-1"))
        .thenReturn(Optional.of(pending), Optional.of(submitted));
    when(rectificationRepository.ownsEvidenceAttachments(
        1L, "rectification-1", "store-a", List.of(81L))).thenReturn(true);
    when(rectificationRepository.submit(
        1L, "inspection-1", "evidence uploaded", 8L, "Manager")).thenReturn(true);
    when(rectificationRepository.evidenceAttachmentIds(1L, "rectification-1", "store-a"))
        .thenReturn(List.of(81L));

    InspectionRectificationResponse result = service.submit(
        manager,
        "inspection-1",
        new InspectionRectificationSubmitRequest("evidence uploaded", List.of(81L)));

    assertThat(result.recordId()).isEqualTo("inspection-1");
    assertThat(result.status()).isEqualTo("PENDING_REVIEW");
    assertThat(result.evidenceAttachmentIds()).containsExactly(81L);
    verify(inspectionRecordRepository).lockRecord(1L, "inspection-1");
    verify(rectificationRepository).saveAction(any());
    verify(rectificationRepository).logOperation(
        eq(1L), eq(8L), eq("Manager"), eq("inspection_rectification_submit"),
        eq("inspection-1"), eq("store-a"), eq("2026-07-15"), anyString());
    verify(inspectionRecordRepository, never()).upsert(anyLong(), anyString(), any());
    verify(inspectionRecordRepository, never()).updateRecordPhotosJson(anyLong(), anyString(), anyString());
    verify(inspectionRecordRepository, never()).updateSnapshotEvidenceIds(
        anyLong(), anyString(), anyLong(), any());
    verify(inspectionRecordRepository, never()).updateSnapshotEvidenceIdsBySnapshotId(
        anyLong(), anyString(), anyLong(), any());
    verifyNoInteractions(storageService);
  }

  @Test
  void reviewRoleCheckStopsAStoreManagerBeforeAnyRecordLookup() {
    AuthUser manager = manager();
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "review role is required", HttpStatus.FORBIDDEN);
    doThrow(forbidden).when(accessControl).requireInspectionRectificationReview(manager);

    assertThatThrownBy(() -> service.review(
        manager, "inspection-1", new InspectionRectificationReviewRequest("APPROVED", "verified")))
        .isSameAs(forbidden);

    verify(accessControl).requireInspectionRectificationReview(manager);
    verifyNoInteractions(inspectionRecordRepository, rectificationRepository, storageService);
  }

  private AuthUser manager() {
    return new AuthUser(
        8L, 1L, "default", "manager", "", "Manager", "STORE_MANAGER", "store-a", true);
  }

  private InspectionRecordResponse record(String id, String storeId) {
    return new InspectionRecordResponse(
        id, storeId, "S-001", "Store", 1L, "Brand", "2026-07-15", "Supervisor", "Brand",
        new BigDecimal("200"), new BigDecimal("176"), false,
        "[]", "[{\"code\":\"R-1\"}]", "[]", "rectification required");
  }

  private InspectionRectificationRecord rectification(
      String id,
      String inspectionRecordId,
      String storeId,
      InspectionRectificationStatus status
  ) {
    return new InspectionRectificationRecord(
        id, 1L, inspectionRecordId, storeId, status,
        null, null, null, null, null, null, null, null, 0L, null, null);
  }
}
