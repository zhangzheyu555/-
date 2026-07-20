package com.storeprofit.system.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeRepository.DataScopeAssignmentRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataScopeServiceTest {
  private final DataScopeRepository repository = mock(DataScopeRepository.class);
  private final AuthRepository authRepository = mock(AuthRepository.class);
  private final DataScopeService service = new DataScopeService(repository, authRepository, new ObjectMapper());

  @Test
  void bossReceivesAllDomainsWithoutDependingOnAssignments() {
    Map<String, DataScope> scopes = service.dataScopes(user(1L, "BOSS", null));

    assertThat(scopes).hasSize(DataScopeDomains.ALL.size());
    assertThat(scopes.values()).allMatch(DataScope::allowsAllStores);
  }

  @Test
  void ownStoreIsMaterializedAndLegacyBroaderManagerScopeIsDenied() {
    AuthUser manager = user(2L, "STORE_MANAGER", "s1");
    when(repository.assignmentsForUser(1L, 2L)).thenReturn(List.of(
        new DataScopeAssignmentRow(DataScopeDomains.FINANCE, DataScopeModes.OWN_STORE, null),
        new DataScopeAssignmentRow(DataScopeDomains.EXAM, DataScopeModes.STORE_LIST, "[\"s2\",\"s3\"]"),
        new DataScopeAssignmentRow(DataScopeDomains.STORE, DataScopeModes.ALL, null)
    ));

    assertThat(service.allowedStoreIds(manager, DataScopeDomains.FINANCE)).containsExactly("s1");
    assertThat(service.allowedStoreIds(manager, DataScopeDomains.EXAM)).isEmpty();
    assertThat(service.canAccessStore(manager, DataScopeDomains.EXAM, "s1")).isFalse();
    assertThat(service.hasAllDataScope(manager, DataScopeDomains.STORE)).isFalse();
    assertThat(service.allowedStoreIds(manager, DataScopeDomains.STORE)).isEmpty();
  }

  @Test
  void missingAssignmentsUseConservativeLegacyScopeInsteadOfTenantWideAccess() {
    AuthUser operations = user(3L, "OPERATIONS", null);
    when(repository.assignmentsForUser(1L, 3L)).thenReturn(List.of());
    when(authRepository.assignedStoreScope(1L, 3L)).thenReturn(List.of("s2"));

    assertThat(service.hasAllDataScope(operations, DataScopeDomains.INSPECTION)).isFalse();
    assertThat(service.allowedStoreIds(operations, DataScopeDomains.INSPECTION)).containsExactly("s2");
    assertThat(service.allowedStoreIds(operations, DataScopeDomains.FINANCE)).isEmpty();
  }

  @Test
  void warehouseListKeepsWarehouseAndStoreIdentifiersDisjoint() {
    DataScope scope = new DataScope(
        DataScopeModes.WAREHOUSE_LIST,
        List.of("store-must-not-survive"),
        List.of("2", "1", "2")
    );

    assertThat(scope.storeIds()).isEmpty();
    assertThat(scope.warehouseIds()).containsExactly("1", "2");
    assertThat(scope.allowsWarehouse("2")).isTrue();
    assertThat(scope.allowsStore("2")).isFalse();
  }

  @Test
  void supervisorUsesAssignedStoreOrWarehouseScopeForOwnedDomains() {
    AuthUser supervisor = user("SUPERVISOR");
    when(repository.assignmentsForUser(1L, 7L)).thenReturn(List.of(
        new DataScopeAssignmentRow(DataScopeDomains.STORE, DataScopeModes.STORE_LIST, "[\"rg1\"]"),
        new DataScopeAssignmentRow(DataScopeDomains.INSPECTION, DataScopeModes.STORE_LIST, "[\"rg1\"]"),
        new DataScopeAssignmentRow(DataScopeDomains.PLATFORM, DataScopeModes.ALL, null),
        new DataScopeAssignmentRow(DataScopeDomains.WAREHOUSE, DataScopeModes.STORE_LIST, "[\"rg2\"]")
    ));

    Map<String, DataScope> scopes = service.dataScopes(supervisor);

    assertThat(scopes.get(DataScopeDomains.STORE).storeIds()).containsExactly("rg1");
    assertThat(scopes.get(DataScopeDomains.INSPECTION).storeIds()).containsExactly("rg1");
    assertThat(scopes.get(DataScopeDomains.PLATFORM).allowsAllStores()).isFalse();
    assertThat(scopes.get(DataScopeDomains.PLATFORM).storeIds()).isEmpty();
    assertThat(scopes.get(DataScopeDomains.WAREHOUSE).storeIds()).containsExactly("rg2");
  }

  @Test
  void supervisorFallbackUsesAssignedStoresOnlyAndNeverAllStores() {
    AuthUser supervisor = user("SUPERVISOR");
    when(repository.assignmentsForUser(1L, 7L)).thenReturn(List.of());
    when(authRepository.assignedStoreScope(1L, 7L)).thenReturn(List.of("rg1", "rg2"));

    Map<String, DataScope> scopes = service.dataScopes(supervisor);

    assertThat(scopes.get(DataScopeDomains.STORE).mode()).isEqualTo(DataScopeModes.STORE_LIST);
    assertThat(scopes.get(DataScopeDomains.STORE).storeIds()).containsExactly("rg1", "rg2");
    assertThat(scopes.get(DataScopeDomains.INSPECTION).storeIds()).containsExactly("rg1", "rg2");
    assertThat(scopes.get(DataScopeDomains.WAREHOUSE).storeIds()).containsExactly("rg1", "rg2");
    assertThat(scopes.get(DataScopeDomains.EXAM).storeIds()).containsExactly("rg1", "rg2");
    assertThat(scopes.get(DataScopeDomains.PLATFORM).storeIds()).containsExactly("rg1", "rg2");
    assertThat(scopes.values()).noneMatch(DataScope::allowsAllStores);
  }

  private AuthUser user(long id, String role, String storeId) {
    return new AuthUser(id, 1L, "测试租户", "user-" + id, "hash", "测试账号", role, storeId, true, 2L);
  }

  private AuthUser user(String role) {
    return new AuthUser(7L, 1L, "测试企业", "supervisor", "hash", "督导", role, null, true);
  }
}
