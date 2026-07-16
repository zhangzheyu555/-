package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.platform.authorization.WorkspaceAccessResolver;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthServiceAuthorizationContractTest {
  @Test
  void loginBindsTokenVersionAndReturnsEffectiveAuthorizationContract() {
    AuthRepository repository = mock(AuthRepository.class);
    PasswordService passwordService = mock(PasswordService.class);
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    AuthUser finance = new AuthUser(
        7L, 1L, "测试租户", "finance", "stored-hash", "测试财务", "FINANCE", null, true, 8L);
    when(repository.findByUsername(1L, "finance")).thenReturn(Optional.of(finance));
    when(passwordService.matches("safe-password", "stored-hash")).thenReturn(true);
    when(authorizationService.effectivePermissions(finance))
        .thenReturn(Set.of(PermissionCodes.FINANCE_PROFIT_READ, PermissionCodes.EXPENSE_REVIEW));
    when(dataScopeService.dataScopes(finance)).thenReturn(Map.of(
        DataScopeDomains.FINANCE, new DataScope("STORE_LIST", java.util.List.of("s1"))));
    when(repository.storeScope(1L, 7L, "FINANCE", null)).thenReturn(java.util.List.of("s1"));
    AuthService service = new AuthService(
        repository,
        passwordService,
        mock(AuditRepository.class),
        authorizationService,
        dataScopeService,
        12
    );

    LoginResponse response = service.login(new LoginRequest("finance", "safe-password", 1L));

    verify(repository).createToken(anyString(), org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(8L), any(OffsetDateTime.class));
    assertThat(response.user().permissions())
        .containsExactly(PermissionCodes.EXPENSE_REVIEW, PermissionCodes.FINANCE_PROFIT_READ);
    assertThat(response.user().dataScopes().get(DataScopeDomains.FINANCE).storeIds()).containsExactly("s1");
    assertThat(response.user().defaultWorkspace()).isEqualTo("/finance");
    assertThat(response.user().permissionVersion()).isEqualTo(8L);
  }

  @Test
  void employeeUsesLearnerWorkspace() {
    AuthService service = new AuthService(
        mock(AuthRepository.class), mock(PasswordService.class), mock(AuditRepository.class), 12);

    assertThat(service.defaultWorkspace("EMPLOYEE")).isEqualTo("/learn/exams");
  }

  @Test
  void storeManagerWorkspaceUsesEffectivePermissionScopeAndConsistentBinding() {
    AuthRepository repository = mock(AuthRepository.class);
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    AuthUser manager = new AuthUser(
        9L, 1L, "测试租户", "manager", "hash", "店长", "STORE_MANAGER", "rg1", true, 4L);
    when(repository.assignedStoreScope(1L, 9L)).thenReturn(List.of("rg1"));
    when(dataScopeService.dataScopes(manager)).thenReturn(Map.of(
        DataScopeDomains.STORE, new DataScope("OWN_STORE", List.of("rg1"))));
    when(authorizationService.effectivePermissions(manager)).thenReturn(
        Set.of(PermissionCodes.STORE_READ),
        Set.of()
    );
    AuthService service = new AuthService(
        repository,
        mock(PasswordService.class),
        mock(AuditRepository.class),
        authorizationService,
        dataScopeService,
        12
    );

    assertThat(service.toSessionUser(manager).defaultWorkspace()).isEqualTo("/store");
    assertThat(service.toSessionUser(manager).defaultWorkspace()).isEqualTo("/no-permission");
  }

  @Test
  void authMeSessionIncludesResolvedBoundStoreAndBrand() {
    AuthRepository repository = mock(AuthRepository.class);
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    BusinessScopeResolver businessScopeResolver = mock(BusinessScopeResolver.class);
    AuthUser manager = new AuthUser(
        9L, 1L, "测试租户", "rg1", "hash", "店长", "STORE_MANAGER", "rg1", true, 4L);
    DataScope ownStore = new DataScope("OWN_STORE", List.of("rg1"));
    when(repository.assignedStoreScope(1L, 9L)).thenReturn(List.of("rg1"));
    when(dataScopeService.dataScopes(manager)).thenReturn(Map.of(DataScopeDomains.STORE, ownStore));
    when(authorizationService.effectivePermissions(manager)).thenReturn(Set.of(PermissionCodes.STORE_READ));
    when(businessScopeResolver.sessionScope(manager)).thenReturn(new BusinessScope(
        "rg1", "荆州之星店", 9L, "茹菓", ownStore));
    AuthService service = new AuthService(
        repository,
        mock(PasswordService.class),
        mock(AuditRepository.class),
        authorizationService,
        dataScopeService,
        new WorkspaceAccessResolver(),
        businessScopeResolver,
        12
    );

    var session = service.toSessionUser(manager);

    assertThat(session.boundStoreId()).isEqualTo("rg1");
    assertThat(session.boundStoreName()).isEqualTo("荆州之星店");
    assertThat(session.brandId()).isEqualTo(9L);
    assertThat(session.brandName()).isEqualTo("茹菓");
    assertThat(session.dataScope()).isEqualTo(ownStore);
  }
}
