package com.storeprofit.system.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditControllerTest {
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final AuditController controller = new AuditController(accessControl, auditRepository);
  private final AuthUser boss = new AuthUser(1L, 1L, "default", "boss", "", "老板", "BOSS", null, true);

  @Test
  void logsAreReadOnlyAndRequireBossPermission() {
    when(accessControl.requireUser("Bearer token")).thenReturn(boss);
    when(auditRepository.logs(1L, 50)).thenReturn(List.of());

    ApiResponse<List<OperationLogResponse>> response = controller.logs("Bearer token", 50);

    assertThat(response.success()).isTrue();
    verify(accessControl).requireUser("Bearer token");
    verify(accessControl).requireAuditRead(boss);
    verify(auditRepository).logs(1L, 50);
    assertThat(List.of(AuditController.class.getDeclaredMethods()).stream().map(method -> method.getName()))
        .doesNotContain("writeLog");
  }
}
