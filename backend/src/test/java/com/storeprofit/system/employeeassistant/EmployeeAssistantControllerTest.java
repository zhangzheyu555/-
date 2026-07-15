package com.storeprofit.system.employeeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EmployeeAssistantControllerTest {
  @Test
  void authenticatedUserWithPermissionCanReadStatusAndSendChat() {
    AuthService authService = mock(AuthService.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    EmployeeAssistantService service = mock(EmployeeAssistantService.class);
    AuthUser user = user();
    when(authService.requireUser("Bearer permitted")).thenReturn(user);
    when(service.health(user)).thenReturn(new EmployeeAssistantStatusResponse(
        false,
        false,
        EmployeeAssistantState.UNCONFIGURED,
        "员工服务助手未配置",
        false,
        false
    ));
    EmployeeAssistantChatRequest request = new EmployeeAssistantChatRequest(null, "漏发吸管怎么处理？");
    when(service.chat(user, request)).thenReturn(new EmployeeAssistantChatResponse(
        "请致歉", true, "request-id", "session-id", false,
        EmployeeAssistantAnswerSource.ASSISTANT, null, null, null, null));
    EmployeeAssistantController controller = new EmployeeAssistantController(authService, accessControl, service);

    assertThat(controller.status("Bearer permitted").data().configured()).isFalse();
    assertThat(controller.chat("Bearer permitted", request).data().answer()).isEqualTo("请致歉");
    verify(accessControl, org.mockito.Mockito.times(2)).requireEmployeeAssistantUse(user);
    verify(service).chat(user, request);
  }

  @Test
  void missingLoginStopsBeforePermissionOrExternalService() {
    AuthService authService = mock(AuthService.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    EmployeeAssistantService service = mock(EmployeeAssistantService.class);
    when(authService.requireUser(null)).thenThrow(new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));
    EmployeeAssistantController controller = new EmployeeAssistantController(authService, accessControl, service);

    BusinessException error = catchThrowableOfType(() -> controller.status(null), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(accessControl, service);
  }

  @Test
  void deniedPermissionStopsBeforeExternalService() {
    AuthService authService = mock(AuthService.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    EmployeeAssistantService service = mock(EmployeeAssistantService.class);
    AuthUser user = user();
    when(authService.requireUser("Bearer denied")).thenReturn(user);
    doThrow(new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN))
        .when(accessControl).requireEmployeeAssistantUse(user);
    EmployeeAssistantController controller = new EmployeeAssistantController(authService, accessControl, service);

    BusinessException error = catchThrowableOfType(
        () -> controller.chat("Bearer denied", new EmployeeAssistantChatRequest(null, "漏发吸管怎么处理？")),
        BusinessException.class
    );

    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    verifyNoInteractions(service);
  }

  private AuthUser user() {
    return new AuthUser(8L, 1L, "测试租户", "test-user", "hash", "测试用户", "STORE_MANAGER", "s1", true, 3L);
  }
}
