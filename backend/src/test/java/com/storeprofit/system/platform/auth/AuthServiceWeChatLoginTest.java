package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.authorization.WorkspaceAccessResolver;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class AuthServiceWeChatLoginTest {
  @Test
  void unboundWeChatIsRejectedWithoutCreatingAnAccount() {
    AuthRepository authRepository = mock(AuthRepository.class);
    WeChatMiniProgramRepository bindingRepository = mock(WeChatMiniProgramRepository.class);
    WeChatMiniProgramService weChatService = configuredWeChatService();
    when(weChatService.exchangeCode("valid-code")).thenReturn(new WeChatMiniProgramService.Identity("openid-1", null));
    when(bindingRepository.boundUserId(1L, "mini-app", "openid-1")).thenReturn(Optional.empty());
    AuthService service = service(authRepository, bindingRepository, weChatService);

    BusinessException error = catchThrowableOfType(() -> service.weChatLogin("valid-code", null), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getCode()).isEqualTo("WECHAT_NOT_BOUND");
    verify(authRepository, never()).createUser(any(Long.class), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(authRepository, never()).createToken(anyString(), any(Long.class), any(Long.class), any(Long.class), any(OffsetDateTime.class));
  }

  @Test
  void disabledBoundUserCannotUseWeChatLogin() {
    AuthRepository authRepository = mock(AuthRepository.class);
    WeChatMiniProgramRepository bindingRepository = mock(WeChatMiniProgramRepository.class);
    WeChatMiniProgramService weChatService = configuredWeChatService();
    when(weChatService.exchangeCode("valid-code")).thenReturn(new WeChatMiniProgramService.Identity("openid-1", null));
    when(bindingRepository.boundUserId(1L, "mini-app", "openid-1")).thenReturn(Optional.of(7L));
    when(authRepository.user(1L, 7L)).thenReturn(Optional.of(user(false)));
    AuthService service = service(authRepository, bindingRepository, weChatService);

    BusinessException error = catchThrowableOfType(() -> service.weChatLogin("valid-code", null), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getCode()).isEqualTo("LOGIN_FAILED");
    verify(authRepository, never()).createToken(anyString(), any(Long.class), any(Long.class), any(Long.class), any(OffsetDateTime.class));
  }

  @Test
  void boundEnabledUserReceivesTheUsualSession() {
    AuthRepository authRepository = mock(AuthRepository.class);
    WeChatMiniProgramRepository bindingRepository = mock(WeChatMiniProgramRepository.class);
    WeChatMiniProgramService weChatService = configuredWeChatService();
    AuthUser user = user(true);
    when(weChatService.exchangeCode("valid-code")).thenReturn(new WeChatMiniProgramService.Identity("openid-1", null));
    when(bindingRepository.boundUserId(1L, "mini-app", "openid-1")).thenReturn(Optional.of(7L));
    when(authRepository.user(1L, 7L)).thenReturn(Optional.of(user));
    AuthService service = service(authRepository, bindingRepository, weChatService);

    LoginResponse response = service.weChatLogin("valid-code", null);

    assertThat(response.status()).isEqualTo("AUTHENTICATED");
    assertThat(response.token()).isNotBlank();
    verify(authRepository).deleteTokensForUser(1L, 7L);
    verify(authRepository).createToken(anyString(), eq(1L), eq(7L), eq(1L), any(OffsetDateTime.class));
  }

  @Test
  void repeatBindingForSameAccountIsIdempotentAndAudited() {
    AuthRepository authRepository = mock(AuthRepository.class);
    WeChatMiniProgramRepository bindingRepository = mock(WeChatMiniProgramRepository.class);
    WeChatMiniProgramService weChatService = configuredWeChatService();
    AuthUser user = user(true);
    when(weChatService.exchangeCode("valid-code")).thenReturn(new WeChatMiniProgramService.Identity("openid-1", "unionid-1"));
    when(bindingRepository.boundUserId(1L, "mini-app", "openid-1")).thenReturn(Optional.of(7L));
    AuthService service = service(authRepository, bindingRepository, weChatService);

    WeChatBindingStatus response = service.bindWeChat(user, "valid-code");

    assertThat(response).isEqualTo(new WeChatBindingStatus(true, true));
    verify(bindingRepository, never()).bind(any(Long.class), any(Long.class), anyString(), anyString(), any());
    verify(authRepository, never()).createUser(any(Long.class), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void bindingConflictIsAReadableBusinessError() {
    AuthRepository authRepository = mock(AuthRepository.class);
    WeChatMiniProgramRepository bindingRepository = mock(WeChatMiniProgramRepository.class);
    WeChatMiniProgramService weChatService = configuredWeChatService();
    AuthUser user = user(true);
    when(weChatService.exchangeCode("valid-code")).thenReturn(new WeChatMiniProgramService.Identity("openid-1", null));
    when(bindingRepository.isBound(1L, 7L, "mini-app")).thenReturn(false);
    org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate")).when(bindingRepository)
        .bind(1L, 7L, "mini-app", "openid-1", null);
    AuthService service = service(authRepository, bindingRepository, weChatService);

    BusinessException error = catchThrowableOfType(() -> service.bindWeChat(user, "valid-code"), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(error.getCode()).isEqualTo("WECHAT_ALREADY_BOUND");
  }

  private AuthService service(
      AuthRepository authRepository,
      WeChatMiniProgramRepository bindingRepository,
      WeChatMiniProgramService weChatService
  ) {
    return new AuthService(authRepository, mock(PasswordService.class), mock(AuditRepository.class), null, null,
        new WorkspaceAccessResolver(), null, 12, 10, weChatService, bindingRepository);
  }

  private WeChatMiniProgramService configuredWeChatService() {
    WeChatMiniProgramService service = mock(WeChatMiniProgramService.class);
    when(service.configured()).thenReturn(true);
    when(service.appId()).thenReturn("mini-app");
    return service;
  }

  private AuthUser user(boolean enabled) {
    return new AuthUser(7L, 1L, "测试租户", "staff", "hash", "测试员工", "EMPLOYEE", null, enabled, 1L);
  }
}
