package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
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

  private AuthUser user(String role) {
    return new AuthUser(12L, 1L, "测试租户", "test-user", "hash", "测试用户", role, null, true);
  }
}
