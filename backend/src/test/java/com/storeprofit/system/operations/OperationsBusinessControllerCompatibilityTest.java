package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamPaperResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class OperationsBusinessControllerCompatibilityTest {
  private final AuthService authService = mock(AuthService.class);
  private final OperationsBusinessService service = mock(OperationsBusinessService.class);
  private final OperationsBusinessController controller = new OperationsBusinessController(authService, service);
  private final AuthUser operator =
      new AuthUser(7L, 1L, "默认企业", "operator", "", "运营", "OPERATIONS", null, true);

  @Test
  void legacyPaperListIsReadOnlyAndPointsToExamCenter() {
    when(authService.requireUser("Bearer token")).thenReturn(operator);
    when(service.examPapers(operator)).thenReturn(List.of());

    ResponseEntity<ApiResponse<List<ExamPaperResponse>>> result = controller.examPapers("Bearer token");

    assertThat(result.getHeaders().getFirst("Deprecation")).isEqualTo("true");
    assertThat(result.getHeaders().getFirst("Link"))
        .isEqualTo("</api/exam-center/overview>; rel=\"successor-version\"");
    assertThat(result.getBody()).isNotNull();
    assertThat(result.getBody().success()).isTrue();
    assertThat(result.getBody().data()).isEmpty();
    verify(service).examPapers(operator);
  }

  @Test
  void legacySubmitIsGoneAndCannotReachAnyExamWriteService() {
    when(authService.requireUser("Bearer token")).thenReturn(operator);
    ExamAttemptRequest request = new ExamAttemptRequest(1L, "测试人员", null, false, List.of());

    assertThatThrownBy(() -> controller.submitExamAttempt("Bearer token", request))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("LEGACY_EXAM_WRITE_DISABLED");
          assertThat(error.getStatus().value()).isEqualTo(410);
        });

    verify(authService).requireUser("Bearer token");
    verifyNoInteractions(service);
  }

  @Test
  void legacyReadDoesNotBroadenExamPermissions() {
    OperationsBusinessRepository repository = mock(OperationsBusinessRepository.class);
    OperationsBusinessService compatibilityService = new OperationsBusinessService(repository);
    AuthUser employee =
        new AuthUser(8L, 1L, "默认企业", "employee", "", "员工", "EMPLOYEE", "rg1", true);

    assertThatThrownBy(() -> compatibilityService.examPapers(employee))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("FORBIDDEN");
          assertThat(error.getStatus().value()).isEqualTo(403);
        });

    verifyNoInteractions(repository);
  }
}
