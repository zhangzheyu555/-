package com.storeprofit.system.platform.auth;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StoreManagerAccountSeedServiceTest {

  private static final long TENANT_ID = 1L;

  @Test
  void createsStoreManagerLoginWithConfiguredBootstrapPassword() {
    OrganizationRepository organizationRepository = Mockito.mock(OrganizationRepository.class);
    AuthRepository authRepository = Mockito.mock(AuthRepository.class);
    PasswordService passwordService = Mockito.mock(PasswordService.class);
    StoreResponse store =
        new StoreResponse("rg1", "RG001", "荆州之星店", 1L, "轻量云", "湖北", "店长", "2026-01-01", "ACTIVE", "");

    when(organizationRepository.stores(TENANT_ID)).thenReturn(List.of(store));
    when(authRepository.userExists(TENANT_ID, "rg1")).thenReturn(false);
    when(passwordService.hash("audit-secret")).thenReturn("HASH_audit_secret");
    when(authRepository.findByUsername(TENANT_ID, "rg1"))
        .thenReturn(
            Optional.of(
                new AuthUser(
                    88L,
                    TENANT_ID,
                    "默认企业",
                    "rg1",
                    "HASH_audit_secret",
                    "店长·荆州之星店",
                    "STORE_MANAGER",
                    "rg1",
                    true)));

    new StoreManagerAccountSeedService(
            authRepository, passwordService, organizationRepository, false, "audit-secret")
        .seedStoreManagers();

    verify(passwordService).hash("audit-secret");
    verify(passwordService, never()).hash("RG001");
    verify(authRepository)
        .createUser(TENANT_ID, "rg1", "HASH_audit_secret", "店长·荆州之星店", "STORE_MANAGER", "rg1");
    verify(organizationRepository).addUserStoreScope(TENANT_ID, 88L, "rg1");
  }

  @Test
  void migratesExistingLegacyCodePasswordToConfiguredBootstrapPassword() {
    OrganizationRepository organizationRepository = Mockito.mock(OrganizationRepository.class);
    AuthRepository authRepository = Mockito.mock(AuthRepository.class);
    PasswordService passwordService = Mockito.mock(PasswordService.class);
    StoreResponse store =
        new StoreResponse("rg1", "RG001", "荆州之星店", 1L, "轻量云", "湖北", "店长", "2026-01-01", "ACTIVE", "");
    AuthUser existing =
        new AuthUser(
            88L,
            TENANT_ID,
            "默认企业",
            "rg1",
            "LEGACY_HASH",
            "店长·旧名称",
            "STORE_MANAGER",
            "rg1",
            true);

    when(organizationRepository.stores(TENANT_ID)).thenReturn(List.of(store));
    when(authRepository.userExists(TENANT_ID, "rg1")).thenReturn(true);
    when(authRepository.findByUsername(TENANT_ID, "rg1")).thenReturn(Optional.of(existing));
    when(passwordService.matches("audit-secret", "LEGACY_HASH")).thenReturn(false);
    when(passwordService.matches("RG001", "LEGACY_HASH")).thenReturn(true);
    when(passwordService.hash("audit-secret")).thenReturn("HASH_audit_secret");

    new StoreManagerAccountSeedService(
            authRepository, passwordService, organizationRepository, false, "audit-secret")
        .seedStoreManagers();

    verify(authRepository).ensureUserProfile(TENANT_ID, "rg1", "店长·荆州之星店", "STORE_MANAGER", "rg1");
    verify(authRepository).updatePassword(TENANT_ID, "rg1", "HASH_audit_secret");
    verify(organizationRepository).addUserStoreScope(TENANT_ID, 88L, "rg1");
  }
}
