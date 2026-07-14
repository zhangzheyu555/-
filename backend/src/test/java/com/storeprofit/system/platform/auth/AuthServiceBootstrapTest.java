package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AuthServiceBootstrapTest {
  @Test
  void doesNotCreateBootstrapUsersWithoutConfiguredPassword() {
    AuthRepository authRepository = mock(AuthRepository.class);
    PasswordService passwordService = mock(PasswordService.class);
    AuthService service = new AuthService(authRepository, passwordService, mock(AuditRepository.class), 12, true, "");

    service.ensureDefaultUsers();

    verifyNoInteractions(authRepository, passwordService);
  }

  @Test
  void repeatedBootstrapNeverChangesAnExistingBossPasswordRoleOrStatus() {
    AuthRepository authRepository = mock(AuthRepository.class);
    PasswordService passwordService = mock(PasswordService.class);
    when(authRepository.userExists(anyLong(), anyString())).thenReturn(true);
    AuthService firstStart = new AuthService(
        authRepository, passwordService, mock(AuditRepository.class), 12, true, "runtime-secret");
    AuthService secondStart = new AuthService(
        authRepository, passwordService, mock(AuditRepository.class), 12, true, "different-runtime-secret");

    firstStart.ensureDefaultUsers();
    secondStart.ensureDefaultUsers();

    verify(authRepository, never()).createUser(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(authRepository, never()).ensureUserRole(anyLong(), anyString(), anyString(), anyString());
    verify(authRepository, never()).migrateLegacyOwnerRolesToBoss(anyLong());
    verify(authRepository, never()).updatePassword(anyLong(), anyString(), anyString());
    verifyNoInteractions(passwordService);
  }

  @Test
  void wrongPasswordReturnsUnauthorizedWithSafeMessage() {
    AuthRepository repository = mock(AuthRepository.class);
    PasswordService passwordService = mock(PasswordService.class);
    AuthUser boss = new AuthUser(1L, 1L, "测试租户", "boss", "stored-hash", "老板", "BOSS", null, true);
    when(repository.findByUsername(1L, "boss")).thenReturn(Optional.of(boss));
    when(passwordService.matches("submitted-password", "stored-hash")).thenReturn(false);
    AuthService service = new AuthService(repository, passwordService, mock(AuditRepository.class), 12, false, "");

    BusinessException error = catchThrowableOfType(
        () -> service.login(new LoginRequest("boss", "submitted-password", null)),
        BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("账号或密码错误");
  }

  @Test
  void repeatedWrongPasswordsAreRateLimited() {
    AuthRepository repository = mock(AuthRepository.class);
    when(repository.findByUsername(1L, "unknown-user")).thenReturn(Optional.empty());
    AuthService service = new AuthService(
        repository, mock(PasswordService.class), mock(AuditRepository.class), 12, false, "");

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
  void missingAuthorizationReturnsUnauthorized() {
    AuthService service = new AuthService(
        mock(AuthRepository.class), mock(PasswordService.class), mock(AuditRepository.class), 12, false, "");

    BusinessException error = catchThrowableOfType(() -> service.requireUser(null), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("请先登录");
  }

  @Test
  void unknownTokenReturnsUnauthorized() {
    AuthRepository repository = mock(AuthRepository.class);
    when(repository.findByToken("expired-token")).thenReturn(Optional.empty());
    AuthService service = new AuthService(repository, mock(PasswordService.class), mock(AuditRepository.class), 12, false, "");

    BusinessException error = catchThrowableOfType(
        () -> service.requireUser("Bearer expired-token"), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("登录已失效，请重新登录");
  }
}
