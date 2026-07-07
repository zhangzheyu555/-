package com.storeprofit.system.platform.auth;

import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.tenant.TenantDefaults;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("organizationSeedService")
public class StoreManagerAccountSeedService {
  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final OrganizationRepository organizationRepository;

  public StoreManagerAccountSeedService(
      AuthRepository authRepository,
      PasswordService passwordService,
      OrganizationRepository organizationRepository
  ) {
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.organizationRepository = organizationRepository;
  }

  @PostConstruct
  public void ensureStoreManagers() {
    long tenantId = TenantDefaults.DEFAULT_TENANT_ID;
    for (StoreResponse store : organizationRepository.stores(tenantId)) {
      String username = store.id();
      String password = store.code() == null || store.code().isBlank() ? store.id() : store.code();
      String displayName = "店长·" + store.name();
      if (!authRepository.userExists(tenantId, username)) {
        authRepository.createUser(tenantId, username, passwordService.hash(password), displayName, "STORE_MANAGER", store.id());
      } else {
        authRepository.ensureUserProfile(tenantId, username, displayName, "STORE_MANAGER", store.id());
      }
      organizationRepository.addUserStoreScope(tenantId, authRepository.findByUsername(tenantId, username).orElseThrow().id(), store.id());
    }
  }
}
