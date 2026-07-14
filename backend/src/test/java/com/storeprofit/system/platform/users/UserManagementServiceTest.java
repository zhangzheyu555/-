package com.storeprofit.system.platform.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.PasswordService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.storeprofit.system.audit.AuditLogRequest;

class UserManagementServiceTest {
  private final AuthRepository authRepository = mock(AuthRepository.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
  private final PasswordService passwordService = mock(PasswordService.class);
  private final AccessControlService accessControl = new AccessControlService(
      mock(AuthService.class), authRepository, auditRepository);
  private final UserManagementService service = new UserManagementService(
      authRepository, passwordService, organizationRepository, accessControl, auditRepository);

  @BeforeEach
  void defaultScopeLookup() {
    when(authRepository.assignedStoreScope(1L, 2L)).thenReturn(List.of());
  }

  @Test
  void lastEnabledBossCannotBeDisabledOrDemoted() {
    AuthUser currentBoss = user(1L, "boss-main", "BOSS", true);
    AuthUser targetBoss = user(2L, "boss-backup", "BOSS", true);
    when(authRepository.user(1L, 2L)).thenReturn(Optional.of(targetBoss));
    when(authRepository.activeBossCount(1L)).thenReturn(1);

    UserUpdateRequest request = new UserUpdateRequest(
        "备用老板", "FINANCE", null, List.of(), true);

    assertThatThrownBy(() -> service.update(currentBoss, 2L, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("LAST_BOSS_PROTECTED"));

    verify(authRepository, never()).updateUser(1L, 2L, "备用老板", "FINANCE", null, true);
  }

  @Test
  void ordinaryRoleCannotModifyBossAccount() {
    AuthUser finance = user(3L, "finance", "FINANCE", true);
    UserUpdateRequest request = new UserUpdateRequest(
        "老板", "BOSS", null, List.of(), true);

    assertThatThrownBy(() -> service.update(finance, 1L, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(authRepository, never()).user(1L, 1L);
  }

  @Test
  void oneBossCannotResetAnotherBossPassword() {
    AuthUser currentBoss = user(1L, "boss-main", "BOSS", true);
    AuthUser targetBoss = user(2L, "boss-backup", "BOSS", true);
    when(authRepository.user(1L, 2L)).thenReturn(Optional.of(targetBoss));

    assertThatThrownBy(() -> service.resetPassword(
        currentBoss, 2L, new UserPasswordResetRequest("new-secure-password", "current-password")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("BOSS_PASSWORD_SELF_SERVICE_REQUIRED"));

    verify(authRepository, never()).updatePasswordByUserId(1L, 2L, "new-secure-password");
  }

  @Test
  void bossCanChangeOwnPasswordAfterCurrentPasswordVerificationWithoutLoggingSecrets() {
    AuthUser currentBoss = user(1L, "boss-main", "BOSS", true);
    when(authRepository.user(1L, 1L)).thenReturn(Optional.of(currentBoss));
    when(passwordService.matches("current-password", "hash")).thenReturn(true);
    when(passwordService.hash("new-secure-password")).thenReturn("new-password-hash");

    service.resetPassword(
        currentBoss, 1L, new UserPasswordResetRequest("new-secure-password", "current-password"));

    verify(authRepository).updatePasswordByUserId(1L, 1L, "new-password-hash");
    verify(authRepository).deleteTokensForUser(1L, 1L);
    ArgumentCaptor<AuditLogRequest> audit = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(eq(currentBoss), audit.capture());
    assertThat(audit.getValue().reason())
        .contains("本人验证当前密码后主动修改")
        .doesNotContain("current-password", "new-secure-password", "new-password-hash", "hash");
  }

  private AuthUser user(long id, String username, String role, boolean enabled) {
    return new AuthUser(id, 1L, "测试租户", username, "hash", username, role, null, enabled);
  }
}
