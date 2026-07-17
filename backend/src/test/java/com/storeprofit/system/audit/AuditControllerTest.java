package com.storeprofit.system.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.common.RequestIdFilter;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

  @Test
  void logsWithoutTokenReturnsHttp401() throws Exception {
    when(accessControl.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc().perform(get("/api/audit/logs"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(accessControl).requireUser(null);
    verifyNoInteractions(auditRepository);
  }

  @Test
  void logsWithoutAuditPermissionReturnsHttp403() throws Exception {
    AuthUser finance = new AuthUser(2L, 1L, "default", "finance", "", "财务", "FINANCE", null, true);
    when(accessControl.requireUser("Bearer finance-token")).thenReturn(finance);
    doThrow(new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN))
        .when(accessControl).requireAuditRead(finance);

    mockMvc().perform(get("/api/audit/logs").header("Authorization", "Bearer finance-token"))
        .andExpect(status().isForbidden())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(accessControl).requireUser("Bearer finance-token");
    verify(accessControl).requireAuditRead(finance);
    verifyNoInteractions(auditRepository);
  }

  private MockMvc mockMvc() {
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .addFilters(new RequestIdFilter())
        .build();
  }
}
