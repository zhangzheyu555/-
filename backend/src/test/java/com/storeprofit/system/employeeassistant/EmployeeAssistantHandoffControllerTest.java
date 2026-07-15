package com.storeprofit.system.employeeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EmployeeAssistantHandoffControllerTest {
  @Test
  void unauthenticatedHandoffCreationIsRejectedBeforeBusinessService() {
    AuthService authService = mock(AuthService.class);
    EmployeeAssistantHandoffService handoffService = mock(EmployeeAssistantHandoffService.class);
    when(authService.requireUser(null)).thenThrow(new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));
    EmployeeAssistantHandoffController controller = new EmployeeAssistantHandoffController(authService, handoffService);

    BusinessException error = catchThrowableOfType(() -> controller.create(null,
        new EmployeeAssistantHandoffCreateRequest("需要人工帮助", "s1")), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(handoffService);
  }
}
