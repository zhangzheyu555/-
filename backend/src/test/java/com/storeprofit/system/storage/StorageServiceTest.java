package com.storeprofit.system.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class StorageServiceTest {
  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final StorageService storageService = new StorageService(jdbcTemplate, accessControl);

  @Test
  void financeCanWriteFinanceKeysOnly() {
    AuthUser finance = user("FINANCE");

    assertThatNoException().isThrownBy(() -> storageService.set(finance, "entries", "{}"));
    assertThatNoException().isThrownBy(() -> storageService.set(finance, "expenses", "[]"));
    assertThatNoException().isThrownBy(() -> storageService.set(finance, "salary", "[]"));

    assertThatThrownBy(() -> storageService.set(finance, "inspections", "[]"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("legacy KV");
  }

  @Test
  void supervisorAndStoreManagerCanWriteOnlyTheirBrowserMigrationKeys() {
    AuthUser supervisor = user("SUPERVISOR");
    AuthUser storeManager = user("STORE_MANAGER");

    assertThatNoException().isThrownBy(() -> storageService.set(supervisor, "inspections", "[]"));
    assertThatNoException().isThrownBy(() -> storageService.set(storeManager, "expenses", "[]"));

    assertThatThrownBy(() -> storageService.set(supervisor, "entries", "{}"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> storageService.set(storeManager, "entries", "{}"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void browserAuthKeysRemainBlockedEvenForBoss() {
    AuthUser boss = user("BOSS");

    assertThatThrownBy(() -> storageService.set(boss, "accounts", "[]"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> storageService.set(boss, "app_pin", "\"123\""))
        .isInstanceOf(BusinessException.class);
  }

  private AuthUser user(String role) {
    return new AuthUser(1L, 1L, "default", role.toLowerCase(), "", role, role, null, true);
  }
}
