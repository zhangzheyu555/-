package com.storeprofit.system.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkspaceAccessResolverTest {
  private final WorkspaceAccessResolver resolver = new WorkspaceAccessResolver();

  @Test
  void storeManagerRequiresPermissionOwnStoreScopeAndOneConsistentBinding() {
    AuthUser manager = user("STORE_MANAGER", "rg1", true);

    WorkspaceAccessProfile ready = resolver.resolve(
        manager,
        Set.of(PermissionCodes.STORE_READ, PermissionCodes.FINANCE_PROFIT_READ),
        Map.of(DataScopeDomains.STORE,
            new DataScope(DataScopeModes.OWN_STORE, List.of("rg1"))),
        List.of("rg1")
    );
    WorkspaceAccessProfile denied = resolver.resolve(
        manager,
        Set.of(PermissionCodes.FINANCE_PROFIT_READ),
        Map.of(DataScopeDomains.STORE,
            new DataScope(DataScopeModes.OWN_STORE, List.of("rg1"))),
        List.of("rg1")
    );
    WorkspaceAccessProfile inconsistent = resolver.resolve(
        manager,
        Set.of(PermissionCodes.STORE_READ),
        Map.of(DataScopeDomains.STORE,
            new DataScope(DataScopeModes.OWN_STORE, List.of("rg1"))),
        List.of("rg1", "rg2")
    );

    assertThat(ready.availableWorkspaces()).containsExactly("/store");
    assertThat(ready.defaultWorkspace()).isEqualTo("/store");
    assertThat(ready.status()).isEqualTo(WorkspaceAccessProfile.READY);
    assertThat(denied.availableWorkspaces()).isEmpty();
    assertThat(denied.defaultWorkspace()).isEqualTo("/no-permission");
    assertThat(denied.message()).isEqualTo("店长工作台未授权");
    assertThat(inconsistent.availableWorkspaces()).isEmpty();
    assertThat(inconsistent.message()).contains("门店绑定");
  }

  @Test
  void recommendedWorkspaceWinsAndFallbackUsesFirstActuallyAvailableWorkspace() {
    AuthUser finance = user("FINANCE", null, true);
    WorkspaceAccessProfile recommended = resolver.resolve(
        finance,
        Set.of(PermissionCodes.FINANCE_PROFIT_READ, PermissionCodes.STORE_READ),
        Map.of(
            DataScopeDomains.FINANCE, DataScope.all(),
            DataScopeDomains.STORE, new DataScope(DataScopeModes.STORE_LIST, List.of("rg1"))
        ),
        List.of("rg1")
    );
    WorkspaceAccessProfile fallback = resolver.resolve(
        finance,
        Set.of(PermissionCodes.STORE_READ),
        Map.of(DataScopeDomains.STORE,
            new DataScope(DataScopeModes.STORE_LIST, List.of("rg1"))),
        List.of("rg1")
    );

    assertThat(recommended.defaultWorkspace()).isEqualTo("/finance");
    assertThat(recommended.availableWorkspaces()).startsWith("/finance");
    assertThat(fallback.availableWorkspaces()).containsExactly("/store");
    assertThat(fallback.defaultWorkspace()).isEqualTo("/store");
  }

  @Test
  void disabledAccountNeverHasAvailableWorkspace() {
    WorkspaceAccessProfile profile = resolver.resolve(
        user("BOSS", null, false), PermissionCodes.ALL, Map.of(), List.of());

    assertThat(profile.availableWorkspaces()).isEmpty();
    assertThat(profile.status()).isEqualTo(WorkspaceAccessProfile.DISABLED);
    assertThat(profile.defaultWorkspace()).isEqualTo("/no-permission");
  }

  private AuthUser user(String role, String storeId, boolean enabled) {
    return new AuthUser(7L, 1L, "测试租户", "test-user", "hash", "测试账号",
        role, storeId, enabled, 2L);
  }
}
