package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.ExamCenterModels.ExamAssignmentResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCampaignResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCenterOverviewResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamPaperEditorResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamPaperSaveRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamQuestionSaveRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamSubmissionRequest;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExamCenterServiceAccessTest {
  private final ExamCenterRepository repository = mock(ExamCenterRepository.class);
  private final OperationsBusinessRepository operationsRepository = mock(OperationsBusinessRepository.class);
  private final AuthService authService = mock(AuthService.class);
  private final AuthRepository authRepository = mock(AuthRepository.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final ExamLearningRepository learningRepository = mock(ExamLearningRepository.class);
  private final AccessControlService accessControl = new AccessControlService(authService, authRepository, auditRepository);
  private final ExamCenterService service = new ExamCenterService(
      repository, operationsRepository, accessControl, auditRepository, learningRepository);

  @Test
  void employeeOverviewContainsOnlyOwnAssignments() {
    AuthUser employee = user(12L, "EMPLOYEE", "rg1");
    when(repository.paperSummaries(1L, false)).thenReturn(List.of());
    when(repository.campaigns(1L, (Collection<String>) null, 12L)).thenReturn(List.of());
    when(repository.assignments(1L, (Collection<String>) null, 12L)).thenReturn(List.of());

    ExamCenterOverviewResponse result = service.overview(employee);

    assertThat(result.accessMode()).isEqualTo("SELF");
    assertThat(result.canManage()).isFalse();
    assertThat(result.candidates()).isEmpty();
    verify(repository).assignments(1L, (Collection<String>) null, 12L);
    verify(repository, never()).candidates(1L, (Collection<String>) null);
  }

  @Test
  void storeManagerOverviewIsRestrictedToOwnStore() {
    AuthUser manager = user(13L, "STORE_MANAGER", "rg1");
    when(repository.paperSummaries(1L, false)).thenReturn(List.of());
    when(repository.campaigns(1L, List.of("rg1"), null)).thenReturn(List.of());
    when(repository.assignments(1L, List.of("rg1"), null)).thenReturn(List.of());

    ExamCenterOverviewResponse result = service.overview(manager);

    assertThat(result.accessMode()).isEqualTo("STORE");
    verify(repository).campaigns(1L, List.of("rg1"), null);
    verify(repository).assignments(1L, List.of("rg1"), null);
  }

  @Test
  void bossCanCreateAndEditExamPaper() {
    AuthUser boss = user(1L, "BOSS", null);
    ExamQuestionSaveRequest question = new ExamQuestionSaveRequest(
        "SINGLE_CHOICE", "测试题", List.of("A", "B"), "A", null, BigDecimal.valueOf(100));
    ExamPaperEditorResponse saved = new ExamPaperEditorResponse(
        21L, "TEST", "测试卷", "EMPLOYEE", BigDecimal.valueOf(80), true, List.of());
    when(repository.insertPaper(anyLong(), any(), any(), any(), any(), anyBoolean())).thenReturn(21L);
    when(repository.paperForEdit(1L, 21L)).thenReturn(Optional.of(saved));

    ExamPaperEditorResponse result = service.savePaper(
        boss,
        new ExamPaperSaveRequest(null, "TEST", "测试卷", "EMPLOYEE", BigDecimal.valueOf(80), true, List.of(question))
    );

    assertThat(result.id()).isEqualTo(21L);
    verify(repository).insertPaper(anyLong(), any(), any(), any(), any(), anyBoolean());
    verify(repository).replaceQuestions(anyLong(), anyLong(), any());
  }

  @Test
  void employeeCannotSubmitAnotherUsersAssignment() {
    AuthUser employee = user(12L, "EMPLOYEE", "rg1");
    when(repository.assignment(1L, 99L, true)).thenReturn(Optional.of(assignment(99L, 88L, "rg1")));

    assertThatThrownBy(() -> service.submit(employee, 99L, new ExamSubmissionRequest(false, List.of())))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.getCode()).isEqualTo("FORBIDDEN"));

    verify(operationsRepository, never()).questionsForGrade(anyLong(), anyLong());
    verify(auditRepository).writePermissionDenied(any(), any(), any(), any(), any(), any());
  }

  @Test
  void storeManagerGetsForbiddenForCampaignOutsideStore() {
    AuthUser manager = user(13L, "STORE_MANAGER", "rg1");
    ExamCampaignResponse campaign = campaign(7L);
    when(repository.campaign(1L, 7L, (Collection<String>) null, null))
        .thenReturn(Optional.of(campaign));
    when(repository.campaign(1L, 7L, List.of("rg1"), null)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.campaignDetail(manager, 7L))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.getCode()).isEqualTo("FORBIDDEN"));
  }

  private AuthUser user(long id, String role, String storeId) {
    return new AuthUser(id, 1L, "默认企业", "tester", "", "测试账号", role, storeId, true);
  }

  private ExamAssignmentResponse assignment(long id, long userId, String storeId) {
    return new ExamAssignmentResponse(
        id, 7L, 3L, "月度考试", "食品安全", userId, "员工甲", "EMPLOYEE",
        storeId, "荆州之星店", "ASSIGNED", "待参加",
        "2026-07-01 09:00:00", "2026-07-31 18:00:00", null, null, null, null
    );
  }

  private ExamCampaignResponse campaign(long id) {
    return new ExamCampaignResponse(
        id, 3L, "食品安全", "月度考试", "PUBLISHED", "进行中",
        "2026-07-01 09:00:00", "2026-07-31 18:00:00", "EMPLOYEE",
        1, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, "2026-07-01 08:00:00"
    );
  }
}
