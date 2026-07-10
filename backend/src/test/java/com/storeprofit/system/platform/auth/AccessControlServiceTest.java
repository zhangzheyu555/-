package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccessControlServiceTest {
  private final AuthService authService = mock(AuthService.class);
  private final AuthRepository authRepository = mock(AuthRepository.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final AccessControlService service = new AccessControlService(authService, authRepository, auditRepository);

  @Test
  void storeManagerCannotAccessAnotherStoreAndDenialIsAudited() {
    AuthUser manager = user("STORE_MANAGER", "rg1");
    when(authRepository.assignedStoreScope(1L, 10L)).thenReturn(List.of("rg1"));

    assertThatThrownBy(() -> service.requireStoreAccess(manager, "rg2", "查看利润数据"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(auditRepository).writePermissionDenied(
        any(AuthUser.class),
        any(String.class),
        any(String.class),
        any(String.class),
        any(String.class),
        any(String.class)
    );
  }

  @Test
  void operationsRoleCannotReadFinanceData() {
    AuthUser operations = user("OPERATIONS", null);

    assertThatThrownBy(() -> service.requireFinanceRead(operations))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void bossCanAccessAnyStore() {
    AuthUser boss = user("BOSS", null);

    service.requireStoreAccess(boss, "rg2", "查看利润数据");

    assertThat(service.canAccessStore(boss, "rg2")).isTrue();
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(10L, 1L, "默认企业", "user", "", "测试账号", role, storeId, true);
  }
}
