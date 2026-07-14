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
  void existingAccountIsNeverChangedEvenWhenItsUsernameMatchesAStoreLoginCode() {
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
            "boss",
            "LEGACY_HASH",
            "店长·旧名称",
            "BOSS",
            null,
            true);

    when(organizationRepository.stores(TENANT_ID)).thenReturn(List.of(store));
    StoreResponse conflictingStore =
        new StoreResponse("boss", "RG001", "冲突门店", 1L, "轻量云", "湖北", "店长", "2026-01-01", "ACTIVE", "");
    when(organizationRepository.stores(TENANT_ID)).thenReturn(List.of(conflictingStore));
    when(authRepository.userExists(TENANT_ID, "boss")).thenReturn(true);

    new StoreManagerAccountSeedService(
            authRepository, passwordService, organizationRepository, false, "audit-secret")
        .seedStoreManagers();

    verify(authRepository, never()).ensureUserProfile(TENANT_ID, "boss", "店长·冲突门店", "STORE_MANAGER", "boss");
    verify(authRepository, never()).updatePassword(TENANT_ID, "boss", "HASH_audit_secret");
    verify(authRepository, never()).createUser(TENANT_ID, "boss", "HASH_audit_secret", "店长·冲突门店", "STORE_MANAGER", "boss");
    verify(organizationRepository, never()).addUserStoreScope(TENANT_ID, 88L, "boss");
    verify(passwordService, never()).hash("audit-secret");
  }
}
