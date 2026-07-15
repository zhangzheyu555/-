package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AssistantControllerAccessTest {
  @Test
  void employeeCannotCallAssistantChatDirectly() {
    AuthService authService = mock(AuthService.class);
    AssistantService assistantService = mock(AssistantService.class);
    when(authService.requireUser("Bearer employee-token")).thenReturn(user("EMPLOYEE"));
    AssistantController controller = new AssistantController(
        authService,
        assistantService,
        mock(DeepSeekProperties.class)
    );

    BusinessException error = catchThrowableOfType(
        () -> controller.chat("Bearer employee-token", null),
        BusinessException.class
    );

    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    verifyNoInteractions(assistantService);
  }

  @Test
  void financeCanCallAssistantChat() {
    AuthService authService = mock(AuthService.class);
    AssistantService assistantService = mock(AssistantService.class);
    AuthUser finance = user("FINANCE");
    when(authService.requireUser("Bearer finance-token")).thenReturn(finance);
    AssistantController controller = new AssistantController(
        authService,
        assistantService,
        mock(DeepSeekProperties.class)
    );

    controller.chat("Bearer finance-token", null);

    verify(assistantService).chat(finance, null);
  }

  @Test
  void productionControllerDelegatesChatAndStatusToPermissionWrappers() {
    AuthService authService = mock(AuthService.class);
    AssistantService assistantService = mock(AssistantService.class);
    DeepSeekProperties properties = mock(DeepSeekProperties.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    AuthUser user = user("EMPLOYEE");
    when(authService.requireUser("Bearer token")).thenReturn(user);
    AssistantController controller = new AssistantController(
        authService,
        assistantService,
        properties,
        accessControl
    );

    controller.chat("Bearer token", null);
    controller.status("Bearer token");

    verify(accessControl, org.mockito.Mockito.times(2)).requireAssistantUse(user);
    verify(assistantService).chat(user, null);
  }

  @Test
  void missingLoginStopsOperatingAssistantStatusBeforeConfigurationIsRead() {
    AuthService authService = mock(AuthService.class);
    AssistantService assistantService = mock(AssistantService.class);
    DeepSeekProperties properties = mock(DeepSeekProperties.class);
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));
    AssistantController controller = new AssistantController(authService, assistantService, properties);

    BusinessException error = catchThrowableOfType(() -> controller.status(null), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(assistantService, properties);
  }

  @Test
  void authenticatedStatusReportsConfigurationWithoutSerializingOperatingAssistantKey() throws Exception {
    AuthService authService = mock(AuthService.class);
    AssistantService assistantService = mock(AssistantService.class);
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-operating-assistant-key-not-a-real-secret");
    properties.setBaseUrl("https://ai.example.test/v1");
    properties.setModel("operating-assistant-model");
    AuthUser boss = user("BOSS");
    when(authService.requireUser("Bearer boss-token")).thenReturn(boss);
    AssistantController controller = new AssistantController(authService, assistantService, properties);

    AssistantStatusResponse status = controller.status("Bearer boss-token").data();
    String serialized = new ObjectMapper().writeValueAsString(status);

    assertThat(status.configured()).isTrue();
    assertThat(status.state()).isEqualTo("CONFIGURED");
    assertThat(status.baseUrlHost()).isEqualTo("ai.example.test");
    assertThat(status.model()).isEqualTo("operating-assistant-model");
    assertThat(serialized)
        .doesNotContain("test-operating-assistant-key-not-a-real-secret")
        .doesNotContain("apiKey")
        .doesNotContain("api-key");
  }

  @Test
  void statusSeparatesConfiguredReadyRejectedAndUpstreamStates() {
    AuthService authService = mock(AuthService.class);
    AssistantService assistantService = mock(AssistantService.class);
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-operating-assistant-key-not-a-real-secret");
    AuthUser boss = user("BOSS");
    when(authService.requireUser("Bearer boss-token")).thenReturn(boss);
    AssistantController controller = new AssistantController(authService, assistantService, properties);

    assertThat(controller.status("Bearer boss-token").data().state()).isEqualTo("CONFIGURED");
    properties.markAnalysisReady();
    assertThat(controller.status("Bearer boss-token").data().state()).isEqualTo("READY");
    assertThat(controller.status("Bearer boss-token").data().lastSuccessAt()).isNotNull();
    properties.markAnalysisResponseRejected("SCHEMA_INVALID");
    assertThat(controller.status("Bearer boss-token").data().state()).isEqualTo("RESPONSE_REJECTED");
    assertThat(controller.status("Bearer boss-token").data().lastErrorCode()).isEqualTo("SCHEMA_INVALID");
    properties.markAnalysisUpstreamError("DEEPSEEK_TIMEOUT");
    assertThat(controller.status("Bearer boss-token").data().state()).isEqualTo("UPSTREAM_ERROR");
    properties.setApiKey("");
    assertThat(controller.status("Bearer boss-token").data().state()).isEqualTo("NOT_CONFIGURED");
  }

  private AuthUser user(String role) {
    return new AuthUser(12L, 1L, "测试租户", "test-user", "hash", "测试用户", role, null, true);
  }
}
