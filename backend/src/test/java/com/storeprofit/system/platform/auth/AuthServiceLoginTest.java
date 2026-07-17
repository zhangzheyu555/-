package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AuthServiceLoginTest {
  @Test
  void existingEnabledUserCanLoginWithoutCreatingAnAccount() {
    AuthRepository repository = mock(AuthRepository.class);
    PasswordService passwordService = mock(PasswordService.class);
    AuthUser boss = user(true);
    when(repository.findByUsername(1L, "boss")).thenReturn(Optional.of(boss));
    when(passwordService.matches("submitted-password", "stored-hash")).thenReturn(true);
    AuthService service = service(repository, passwordService, mock(AuditRepository.class));

    LoginResponse response = service.login(new LoginRequest("boss", "submitted-password", null));

    assertThat(response.token()).isNotBlank();
    assertThat(response.user().role()).isEqualTo("BOSS");
    verify(repository).deleteTokensForUser(1L, 1L);
    verify(repository).createToken(
        anyString(), eq(1L), eq(1L), eq(1L), any(OffsetDateTime.class));
    verify(repository, never()).createUser(
        org.mockito.ArgumentMatchers.anyLong(),
        anyString(),
        anyString(),
        anyString(),
        anyString(),
        anyString());
  }

  @Test
  void unknownUserReturnsUnauthorizedWithoutCreatingUserTokenOrAuditLog() {
    AuthRepository repository = mock(AuthRepository.class);
    PasswordService passwordService = mock(PasswordService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    when(repository.findByUsername(1L, "missing-user")).thenReturn(Optional.empty());
    AuthService service = service(repository, passwordService, auditRepository);

    BusinessException error = catchThrowableOfType(
        () -> service.login(new LoginRequest("missing-user", "submitted-password", null)),
        BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("账号或密码错误");
    verify(repository, never()).createUser(
        org.mockito.ArgumentMatchers.anyLong(),
        anyString(),
        anyString(),
        anyString(),
        anyString(),
        anyString());
    verify(repository, never()).createToken(
        anyString(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        any(OffsetDateTime.class));
    verifyNoInteractions(passwordService, auditRepository);
  }

  @Test
  void wrongPasswordReturnsUnauthorizedWithSafeMessage() {
    AuthRepository repository = mock(AuthRepository.class);
    PasswordService passwordService = mock(PasswordService.class);
    when(repository.findByUsername(1L, "boss")).thenReturn(Optional.of(user(true)));
    when(passwordService.matches("submitted-password", "stored-hash")).thenReturn(false);
    AuthService service = service(repository, passwordService, mock(AuditRepository.class));

    BusinessException error = catchThrowableOfType(
        () -> service.login(new LoginRequest("boss", "submitted-password", null)),
        BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("账号或密码错误");
    verify(repository, never()).createToken(
        anyString(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        any(OffsetDateTime.class));
  }

  @Test
  void disabledUserCannotLogin() {
    AuthRepository repository = mock(AuthRepository.class);
    PasswordService passwordService = mock(PasswordService.class);
    when(repository.findByUsername(1L, "boss")).thenReturn(Optional.of(user(false)));
    AuthService service = service(repository, passwordService, mock(AuditRepository.class));

    BusinessException error = catchThrowableOfType(
        () -> service.login(new LoginRequest("boss", "submitted-password", null)),
        BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("账号或密码错误");
    verifyNoInteractions(passwordService);
    verify(repository, never()).createToken(
        anyString(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        any(OffsetDateTime.class));
  }

  @Test
  void repeatedWrongPasswordsAreRateLimited() {
    AuthRepository repository = mock(AuthRepository.class);
    when(repository.findByUsername(1L, "unknown-user")).thenReturn(Optional.empty());
    AuthService service = service(
        repository, mock(PasswordService.class), mock(AuditRepository.class));

    for (int attempt = 0; attempt < 5; attempt++) {
      BusinessException error = catchThrowableOfType(
          () -> service.login(new LoginRequest("unknown-user", "submitted-password", null)),
          BusinessException.class);
      assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    BusinessException limited = catchThrowableOfType(
        () -> service.login(new LoginRequest("unknown-user", "submitted-password", null)),
        BusinessException.class);
    assertThat(limited.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(limited.getMessage()).isEqualTo("登录尝试过多，请稍后再试");
  }

  @Test
  void repeatedFailuresFromOneSourceAreRateLimitedAcrossUsernames() {
    AuthRepository repository = mock(AuthRepository.class);
    when(repository.findByUsername(eq(1L), anyString())).thenReturn(Optional.empty());
    AuthService service = service(
        repository, mock(PasswordService.class), mock(AuditRepository.class));

    for (int attempt = 0; attempt < 5; attempt++) {
      String username = "unknown-user-" + attempt;
      BusinessException error = catchThrowableOfType(
          () -> service.login(
              new LoginRequest(username, "submitted-password", null),
              "198.51.100.10"),
          BusinessException.class);
      assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    BusinessException limited = catchThrowableOfType(
        () -> service.login(
            new LoginRequest("different-user", "submitted-password", null),
            "198.51.100.10"),
        BusinessException.class);

    assertThat(limited.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void missingAuthorizationReturnsUnauthorized() {
    AuthService service = service(
        mock(AuthRepository.class), mock(PasswordService.class), mock(AuditRepository.class));

    BusinessException error = catchThrowableOfType(
        () -> service.requireUser(null), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("请先登录");
  }

  @Test
  void unknownTokenReturnsUnauthorized() {
    AuthRepository repository = mock(AuthRepository.class);
    when(repository.findByToken("expired-token")).thenReturn(Optional.empty());
    AuthService service = service(
        repository, mock(PasswordService.class), mock(AuditRepository.class));

    BusinessException error = catchThrowableOfType(
        () -> service.requireUser("Bearer expired-token"), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("登录已失效，请重新登录");
  }

  private AuthService service(
      AuthRepository repository,
      PasswordService passwordService,
      AuditRepository auditRepository
  ) {
    return new AuthService(repository, passwordService, auditRepository, 12);
  }

  private AuthUser user(boolean enabled) {
    return new AuthUser(
        1L, 1L, "测试租户", "boss", "stored-hash", "老板", "BOSS", null, enabled);
  }
}
