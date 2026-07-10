package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

class AuthServiceLogoutTest {
  @Test
  void validTokenIsRevokedAndLogoutIsAuditedWithoutTokenValue() {
    AuthRepository authRepository = mock(AuthRepository.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    AuthUser user = user();
    when(authRepository.findByToken("session-token")).thenReturn(Optional.of(user));

    service(authRepository, auditRepository).logout("Bearer session-token");

    ArgumentCaptor<AuditLogRequest> logCaptor = ArgumentCaptor.forClass(AuditLogRequest.class);
    InOrder order = inOrder(authRepository, auditRepository);
    order.verify(authRepository).findByToken("session-token");
    order.verify(authRepository).deleteToken("session-token");
    order.verify(auditRepository).writeLog(eq(user), logCaptor.capture());

    AuditLogRequest request = logCaptor.getValue();
    assertThat(request.action()).isEqualTo("logout");
    assertThat(request.targetType()).isEqualTo("auth_session");
    assertThat(request.targetId()).isEqualTo("9");
    assertThat(request.reason()).isEqualTo("用户主动退出登录");
    assertThat(request.toString()).doesNotContain("session-token");
  }

  @Test
  void missingTokenIsRejectedAsUnauthorized() {
    AuthRepository authRepository = mock(AuthRepository.class);
    AuditRepository auditRepository = mock(AuditRepository.class);

    BusinessException error = catchThrowableOfType(
        () -> service(authRepository, auditRepository).logout(null),
        BusinessException.class
    );

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("请先登录");
    verifyNoInteractions(authRepository, auditRepository);
  }

  @Test
  void revokedTokenCannotAccessProtectedApiOrProduceServerErrorOnRepeatedLogout() {
    AuthRepository authRepository = mock(AuthRepository.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    when(authRepository.findByToken("session-token"))
        .thenReturn(Optional.of(user()), Optional.empty(), Optional.empty());
    AuthService service = service(authRepository, auditRepository);

    service.logout("Bearer session-token");

    BusinessException protectedApiError = catchThrowableOfType(
        () -> service.requireUser("Bearer session-token"),
        BusinessException.class
    );
    BusinessException repeatedLogoutError = catchThrowableOfType(
        () -> service.logout("Bearer session-token"),
        BusinessException.class
    );

    assertThat(protectedApiError.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(repeatedLogoutError.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private AuthService service(AuthRepository authRepository, AuditRepository auditRepository) {
    return new AuthService(authRepository, mock(PasswordService.class), auditRepository, 12, false, "");
  }

  private AuthUser user() {
    return new AuthUser(9L, 1L, "测试租户", "finance-test", "hash", "测试财务", "FINANCE", null, true);
  }
}
