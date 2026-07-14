package com.storeprofit.system.platform.auth;

import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.tenant.TenantDefaults;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("organizationSeedService")
public class StoreManagerAccountSeedService {
  private static final Logger log = LoggerFactory.getLogger(StoreManagerAccountSeedService.class);
  private final AuthRepository authRepository;
  private final PasswordService passwordService;
  private final OrganizationRepository organizationRepository;
  private final boolean bootstrapStoreManagerAccountsEnabled;
  private final String bootstrapStoreManagerPassword;

  public StoreManagerAccountSeedService(
      AuthRepository authRepository,
      PasswordService passwordService,
      OrganizationRepository organizationRepository,
      @Value("${app.bootstrap.store-manager-accounts-enabled:false}") boolean bootstrapStoreManagerAccountsEnabled,
      @Value("${app.bootstrap.store-manager-password:}") String bootstrapStoreManagerPassword
  ) {
    this.authRepository = authRepository;
    this.passwordService = passwordService;
    this.organizationRepository = organizationRepository;
    this.bootstrapStoreManagerAccountsEnabled = bootstrapStoreManagerAccountsEnabled;
    this.bootstrapStoreManagerPassword = bootstrapStoreManagerPassword == null ? "" : bootstrapStoreManagerPassword.trim();
  }

  @PostConstruct
  public void ensureStoreManagers() {
    if (!bootstrapStoreManagerAccountsEnabled) {
      return;
    }
    if (bootstrapStoreManagerPassword.isBlank()) {
      log.error("Store manager bootstrap was requested without APP_BOOTSTRAP_STORE_MANAGER_PASSWORD; no accounts were created.");
      return;
    }
    seedStoreManagers();
  }

  /** Explicit migration operation retained for tests and controlled one-time migrations. */
  public void seedStoreManagers() {
    if (bootstrapStoreManagerPassword.isBlank()) {
      throw new IllegalStateException("Store manager bootstrap password must be configured");
    }
    long tenantId = TenantDefaults.DEFAULT_TENANT_ID;
    for (StoreResponse store : organizationRepository.stores(tenantId)) {
      String username = storeLoginCode(store);
      if (username.isBlank()) {
        continue;
      }
      String displayName = "店长·" + store.name();
      if (authRepository.userExists(tenantId, username)) {
        log.warn("Store manager bootstrap skipped existing account. tenantId={} username={}", tenantId, username);
        continue;
      }
      authRepository.createUser(
          tenantId, username, passwordService.hash(bootstrapStoreManagerPassword), displayName, "STORE_MANAGER", store.id());
      AuthUser user = authRepository.findByUsername(tenantId, username).orElseThrow();
      organizationRepository.addUserStoreScope(tenantId, user.id(), store.id());
    }
  }

  private String storeLoginCode(StoreResponse store) {
    String id = normalize(store.id());
    if (!id.isBlank()) {
      return id;
    }
    return normalize(store.code());
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
