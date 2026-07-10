package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
