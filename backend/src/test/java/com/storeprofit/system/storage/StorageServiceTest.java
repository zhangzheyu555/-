package com.storeprofit.system.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

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

  @Test
  void browserAuthKeysArePermanentlyBlockedOnRead() {
    AuthUser boss = user("BOSS");

    assertThatThrownBy(() -> storageService.get(boss, "accounts"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("LEGACY_STORAGE_SENSITIVE_KEY_BLOCKED"));
    assertThatThrownBy(() -> storageService.get(boss, "tokens"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> storageService.get(boss, "unknown_key"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("LEGACY_STORAGE_KEY_NOT_ALLOWED"));
  }

  @Test
  void allowlistedBusinessKeyCanStillBeRead() {
    AuthUser boss = user("BOSS");
    when(jdbcTemplate.queryForObject(
        "select storage_value from kv_storage where storage_key = ?", String.class, "entries"))
        .thenReturn("{}");

    assertThat(storageService.get(boss, " entries ")).contains("{}");

    verify(accessControl).requireLegacyStorageAccess(boss);
  }

  @Test
  void onlyExactInspectionStoreDraftCanUploadBeforeRecordExists() {
    AuthUser supervisor = user("SUPERVISOR");
    when(jdbcTemplate.queryForObject(
        "select count(*) from store_branch where tenant_id = ? and id = ?",
        Integer.class, 1L, "s1")).thenReturn(1);
    MockMultipartFile photo = new MockMultipartFile(
        "file", "shop.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});

    assertThatNoException().isThrownBy(() -> storageService.upload(
        supervisor, photo, "INSPECTION_RECORD", "inspection-s1-draft", "s1"));

    assertThatThrownBy(() -> storageService.upload(
        supervisor, photo, "INSPECTION_RECORD", "draft", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("INSPECTION_HISTORICAL_EVIDENCE_SPECIAL_ENDPOINT_REQUIRED"));
    assertThatThrownBy(() -> storageService.upload(
        supervisor, photo, "INSPECTION_RECORD", "inspection-s2-draft", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("INSPECTION_HISTORICAL_EVIDENCE_SPECIAL_ENDPOINT_REQUIRED"));
    assertThatThrownBy(() -> storageService.upload(
        supervisor, photo, "EXPENSE", "inspection-s1-draft", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("ATTACHMENT_BUSINESS_NOT_FOUND"));
  }

  @Test
  void crossStoreDraftUploadStillRequiresAttachmentWritePermission() {
    AuthUser supervisor = user("SUPERVISOR");
    MockMultipartFile photo = new MockMultipartFile(
        "file", "shop.jpg", "image/jpeg", new byte[]{1});
    doThrow(new BusinessException("FORBIDDEN", "跨店禁止上传", org.springframework.http.HttpStatus.FORBIDDEN))
        .when(accessControl).requireAttachmentWrite(supervisor, "s2");

    assertThatThrownBy(() -> storageService.upload(
        supervisor, photo, "INSPECTION_RECORD", "inspection-s2-draft", "s2"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  private AuthUser user(String role) {
    return new AuthUser(1L, 1L, "default", role.toLowerCase(), "", role, role, null, true);
  }
}
