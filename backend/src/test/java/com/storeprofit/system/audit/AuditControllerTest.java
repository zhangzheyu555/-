package com.storeprofit.system.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;

class AuditControllerTest {
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final AuditController controller = new AuditController(accessControl, auditRepository);
  private final AuthUser boss = new AuthUser(1L, 1L, "default", "boss", "", "老板", "BOSS", null, true);

  @Test
  void writeLogUsesAuthenticatedUserAndStoresFrontendAuditEvent() {
    AuditLogRequest request = new AuditLogRequest(
        "修改",
        "门店",
        "rg1",
        "rg1",
        "2026-07",
        "前端保存门店资料",
        "{\"status\":\"旧\"}",
        "{\"status\":\"新\"}"
    );
    when(accessControl.requireUser("Bearer token")).thenReturn(boss);

    ApiResponse<Void> response = controller.writeLog("Bearer token", request);

    assertThat(response.success()).isTrue();
    verify(accessControl).requireUser("Bearer token");
    verify(accessControl).requireAuditWrite(boss);
    verify(auditRepository).writeLog(boss, request);
  }
}
