package com.storeprofit.system.platform.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.PasswordService;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeAssignment;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.platform.authorization.PermissionCatalogEntry;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.platform.authorization.PermissionEffect;
import com.storeprofit.system.platform.authorization.UserPermissionOverride;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserAuthorizationManagementTest {
  private final AuthRepository authRepository = mock(AuthRepository.class);
  private final PasswordService passwordService = mock(PasswordService.class);
  private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final AuthorizationService authorizationService = mock(AuthorizationService.class);
  private final DataScopeService dataScopeService = mock(DataScopeService.class);
  private final UserManagementService service = new UserManagementService(
      authRepository,
      passwordService,
      organizationRepository,
      accessControl,
      auditRepository,
      authorizationService,
      dataScopeService,
      new ObjectMapper()
  );
  private final AuthUser boss = user(1L, "boss", "BOSS", null, 3L);

  @BeforeEach
  void stores() {
    when(organizationRepository.stores(1L)).thenReturn(List.of(
        new StoreResponse("rg1", "RG1", "一店", 1L, "测试品牌", null, null, null, "营业中", null),
        new StoreResponse("rg2", "RG2", "二店", 1L, "测试品牌", null, null, null, "营业中", null)
    ));
    when(authorizationService.catalog()).thenReturn(List.of(
        catalogEntry(PermissionCodes.STORE_READ),
        catalogEntry(PermissionCodes.FINANCE_PROFIT_READ),
        catalogEntry(PermissionCodes.FINANCE_EXPORT),
        catalogEntry(PermissionCodes.EXAM_LEARN),
        catalogEntry(PermissionCodes.EXAM_MANAGE),
        catalogEntry(PermissionCodes.WAREHOUSE_READ),
        catalogEntry(PermissionCodes.EMPLOYEE_READ),
        catalogEntry(PermissionCodes.EMPLOYEE_MANAGE),
        catalogEntry(PermissionCodes.ASSISTANT_USE),
        catalogEntry(PermissionCodes.EMPLOYEE_ASSISTANT_USE),
        catalogEntry(PermissionCodes.DAILY_LOSS_READ)
    ));
  }

  @Test
  void catalogAndUserAuthorizationExposeFourFrontendSections() {
    AuthUser finance = user(2L, "finance", "FINANCE", null, 7L);
    PermissionCatalogEntry permission = new PermissionCatalogEntry(
        PermissionCodes.FINANCE_PROFIT_READ, "FINANCE", "查看利润", "查看利润", "MEDIUM", true, 10);
    when(authorizationService.catalog()).thenReturn(List.of(permission));
    when(authRepository.user(1L, 2L)).thenReturn(Optional.of(finance));
    when(authorizationService.roleTemplatePermissions(1L, "FINANCE"))
        .thenReturn(Set.of(PermissionCodes.FINANCE_PROFIT_READ));
    when(dataScopeService.assignmentsForUser(1L, 2L))
        .thenReturn(List.of(new DataScopeAssignment("FINANCE", "STORE_LIST", List.of("rg1"))));
    when(authorizationService.userOverrides(1L, 2L))
        .thenReturn(List.of(new UserPermissionOverride(PermissionCodes.FINANCE_EXPORT, PermissionEffect.DENY)));
    when(authorizationService.effectivePermissions(finance))
        .thenReturn(Set.of(PermissionCodes.FINANCE_PROFIT_READ));

    AuthorizationCatalogResponse catalog = service.authorizationCatalog(boss);
    UserAuthorizationResponse response = service.authorization(boss, 2L);

    assertThat(catalog.permissions()).containsExactly(permission);
    assertThat(catalog.dataScopeDomains()).contains("FINANCE", "WAREHOUSE", "EXAM");
    assertThat(catalog.dataScopeModes()).contains("ALL", "STORE_LIST", "OWN_STORE", "NONE");
    assertThat(response.roleTemplatePermissions()).containsExactly(PermissionCodes.FINANCE_PROFIT_READ);
    assertThat(response.dataScopes()).hasSize(1);
    assertThat(response.overrides()).hasSize(1);
    assertThat(response.effectivePermissions()).containsExactly(PermissionCodes.FINANCE_PROFIT_READ);
    assertThat(response.permissionVersion()).isEqualTo(7L);
    verify(accessControl, times(2)).requireUserManagementRead(boss);
  }

  @Test
  void updatingAuthorizationReplacesBothPartsInvalidatesTokensAndAuditsBeforeAfter() {
    AuthUser finance = user(2L, "finance", "FINANCE", null, 7L);
    when(authRepository.user(1L, 2L)).thenReturn(Optional.of(finance));
    when(authorizationService.roleTemplatePermissions(1L, "FINANCE"))
        .thenReturn(Set.of(PermissionCodes.FINANCE_PROFIT_READ));
    when(dataScopeService.assignmentsForUser(1L, 2L)).thenReturn(
        List.of(new DataScopeAssignment("FINANCE", "STORE_LIST", List.of("rg1"))),
        List.of(new DataScopeAssignment("FINANCE", "STORE_LIST", List.of("rg2")))
    );
    when(authorizationService.userOverrides(1L, 2L)).thenReturn(
        List.of(new UserPermissionOverride(PermissionCodes.FINANCE_EXPORT, PermissionEffect.DENY)),
        List.of(new UserPermissionOverride(PermissionCodes.FINANCE_EXPORT, PermissionEffect.ALLOW))
    );
    when(authorizationService.effectivePermissions(finance)).thenReturn(
        Set.of(PermissionCodes.FINANCE_PROFIT_READ),
        Set.of(PermissionCodes.FINANCE_PROFIT_READ, PermissionCodes.FINANCE_EXPORT)
    );
    when(authorizationService.incrementPermissionVersionAndDeleteTokens(1L, 2L)).thenReturn(8L);
    UserAuthorizationUpdateRequest request = new UserAuthorizationUpdateRequest(
        List.of(new UserPermissionOverrideRequest(PermissionCodes.FINANCE_EXPORT, "ALLOW")),
        List.of(new UserDataScopeRequest("FINANCE", "STORE_LIST", List.of("rg2")))
    );

    UserAuthorizationResponse response = service.updateAuthorization(boss, 2L, request);

    verify(authorizationService).replaceUserOverrides(
        1L,
        2L,
        List.of(new UserPermissionOverride(PermissionCodes.FINANCE_EXPORT, PermissionEffect.ALLOW)),
        1L
    );
    verify(dataScopeService).replaceAssignments(
        eq(1L),
        eq(2L),
        org.mockito.ArgumentMatchers.argThat(scopes -> scopes.size() == 7
            && scopes.stream().anyMatch(scope -> "FINANCE".equals(scope.domainCode())
                && "STORE_LIST".equals(scope.mode())
                && scope.storeIds().equals(List.of("rg2")))
            && scopes.stream().filter(scope -> !"FINANCE".equals(scope.domainCode()))
                .allMatch(scope -> "NONE".equals(scope.mode()))),
        eq(1L)
    );
    verify(authorizationService).incrementPermissionVersionAndDeleteTokens(1L, 2L);
    assertThat(response.permissionVersion()).isEqualTo(8L);
    assertThat(response.effectivePermissions()).contains(PermissionCodes.FINANCE_EXPORT);
    ArgumentCaptor<AuditLogRequest> audit = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(eq(boss), audit.capture());
    assertThat(audit.getValue().beforeJson())
        .contains("DENY", "rg1", "permissionVersion\":7");
    assertThat(audit.getValue().afterJson())
        .contains("ALLOW", "rg2", "permissionVersion\":8");
  }

  @Test
  void employeeCannotReceiveManagementPermission() {
    AuthUser employee = user(3L, "learner", "EMPLOYEE", "rg1", 2L);
    stubAuthorizationSnapshot(employee);
    UserAuthorizationUpdateRequest request = new UserAuthorizationUpdateRequest(
        List.of(new UserPermissionOverrideRequest(PermissionCodes.EXAM_MANAGE, "ALLOW")),
        List.of(new UserDataScopeRequest("EXAM", "SELF", List.of()))
    );

    assertThatThrownBy(() -> service.updateAuthorization(boss, 3L, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("EMPLOYEE_PERMISSION_CEILING"));

    verify(authorizationService, never()).replaceUserOverrides(eq(1L), eq(3L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
    verify(authorizationService, never()).incrementPermissionVersionAndDeleteTokens(1L, 3L);
  }

  @Test
  void storeManagementCannotBeGrantedToANonBossAccount() {
    AuthUser manager = user(4L, "manager", "STORE_MANAGER", "rg1", 2L);
    stubAuthorizationSnapshot(manager);
    when(authorizationService.catalog()).thenReturn(List.of(catalogEntry(PermissionCodes.STORE_MANAGE)));

    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        4L,
        new UserAuthorizationUpdateRequest(
            List.of(new UserPermissionOverrideRequest(PermissionCodes.STORE_MANAGE, "ALLOW")),
            List.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_MANAGEMENT_BOSS_ONLY"));

    verify(authorizationService, never()).replaceUserOverrides(
        eq(1L), eq(4L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
    verify(authorizationService, never()).incrementPermissionVersionAndDeleteTokens(1L, 4L);
  }

  @Test
  void financeImportCannotBeGrantedToANonFinanceAccount() {
    AuthUser manager = user(5L, "manager-import", "STORE_MANAGER", "rg1", 2L);
    stubAuthorizationSnapshot(manager);
    when(authorizationService.catalog()).thenReturn(List.of(catalogEntry(PermissionCodes.FINANCE_PROFIT_IMPORT)));

    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        5L,
        new UserAuthorizationUpdateRequest(
            List.of(new UserPermissionOverrideRequest(PermissionCodes.FINANCE_PROFIT_IMPORT, "ALLOW")),
            List.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("FINANCE_IMPORT_FINANCE_ONLY"));

    verify(authorizationService, never()).replaceUserOverrides(
        eq(1L), eq(5L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
    verify(authorizationService, never()).incrementPermissionVersionAndDeleteTokens(1L, 5L);
  }

  @Test
  void financeCannotReceiveWarehouseOrEmployeeAssistantPermission() {
    AuthUser finance = user(6L, "finance-boundary", "FINANCE", null, 2L);
    stubAuthorizationSnapshot(finance);

    for (String permissionCode : List.of(
        PermissionCodes.WAREHOUSE_READ,
        PermissionCodes.EMPLOYEE_ASSISTANT_USE,
        PermissionCodes.DAILY_LOSS_READ)) {
      assertThatThrownBy(() -> service.updateAuthorization(
          boss,
          6L,
          new UserAuthorizationUpdateRequest(
              List.of(new UserPermissionOverrideRequest(permissionCode, "ALLOW")),
              List.of())))
          .isInstanceOf(BusinessException.class)
          .satisfies(error -> assertThat(((BusinessException) error).getCode())
              .isEqualTo("FINANCE_PERMISSION_BOUNDARY"));
    }

    verify(authorizationService, never()).replaceUserOverrides(
        eq(1L), eq(6L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
    verify(authorizationService, never()).incrementPermissionVersionAndDeleteTokens(1L, 6L);
  }

  @Test
  void supervisorCanReceiveOperationsPermissionWithinFormalBoundary() {
    AuthUser supervisor = user(9L, "supervisor", "SUPERVISOR", null, 2L);
    stubAuthorizationSnapshot(supervisor);
    when(authorizationService.catalog()).thenReturn(List.of(catalogEntry(PermissionCodes.PLATFORM_MANAGE)));

    assertThatCode(() -> service.updateAuthorization(
        boss,
        9L,
        new UserAuthorizationUpdateRequest(
            List.of(new UserPermissionOverrideRequest(PermissionCodes.PLATFORM_MANAGE, "ALLOW")),
            List.of()))).doesNotThrowAnyException();

    verify(authorizationService).replaceUserOverrides(
        eq(1L), eq(9L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
  }

  @Test
  void supervisorCannotReceiveWarehouseOrAssistantPermission() {
    AuthUser supervisor = user(11L, "supervisor-boundary", "SUPERVISOR", null, 2L);
    stubAuthorizationSnapshot(supervisor);

    for (String permissionCode : List.of(
        PermissionCodes.WAREHOUSE_READ,
        PermissionCodes.ASSISTANT_USE,
        PermissionCodes.EMPLOYEE_ASSISTANT_USE)) {
      assertThatThrownBy(() -> service.updateAuthorization(
          boss,
          11L,
          new UserAuthorizationUpdateRequest(
              List.of(new UserPermissionOverrideRequest(permissionCode, "ALLOW")),
              List.of())))
          .isInstanceOf(BusinessException.class)
          .satisfies(error -> assertThat(((BusinessException) error).getCode())
              .isEqualTo("SUPERVISOR_PERMISSION_BOUNDARY"));
    }

    verify(authorizationService, never()).replaceUserOverrides(
        eq(1L), eq(11L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
    verify(authorizationService, never()).incrementPermissionVersionAndDeleteTokens(1L, 11L);
  }

  @Test
  void supervisorDataScopeSupportsAssignedOperationsDomainsWithoutAll() {
    AuthUser supervisor = user(10L, "supervisor2", "SUPERVISOR", null, 2L);
    stubAuthorizationSnapshot(supervisor);

    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        10L,
        new UserAuthorizationUpdateRequest(
            List.of(),
            List.of(new UserDataScopeRequest(DataScopeDomains.PLATFORM, DataScopeModes.ALL, List.of())))))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("SUPERVISOR_SCOPE_BOUNDARY"));

    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        10L,
        new UserAuthorizationUpdateRequest(
            List.of(),
            List.of(new UserDataScopeRequest(
                DataScopeDomains.WAREHOUSE, DataScopeModes.STORE_LIST, List.of("rg1"))))))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("SUPERVISOR_SCOPE_BOUNDARY"));

    verify(dataScopeService, never()).replaceAssignments(
        eq(1L), eq(10L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
  }

  @Test
  void legacySupervisorAuthorizationSynchronizesAccountStoreScope() {
    AuthUser supervisor = user(13L, "supervisor-sync", "SUPERVISOR", null, 2L);
    stubAuthorizationSnapshot(supervisor);
    when(authorizationService.incrementPermissionVersionAndDeleteTokens(1L, 13L)).thenReturn(3L);

    service.updateAuthorization(
        boss,
        13L,
        new UserAuthorizationUpdateRequest(
            List.of(),
            List.of(new UserDataScopeRequest(
                DataScopeDomains.STORE,
                DataScopeModes.STORE_LIST,
                List.of("rg1", "rg2"))))
    );

    verify(authRepository).replaceStoreScope(1L, 13L, List.of("rg1", "rg2"));
    verify(dataScopeService).replaceAssignments(
        eq(1L),
        eq(13L),
        org.mockito.ArgumentMatchers.argThat(scopes -> scopes.stream()
            .anyMatch(scope -> DataScopeDomains.STORE.equals(scope.domainCode())
                && DataScopeModes.STORE_LIST.equals(scope.mode())
                && scope.storeIds().equals(List.of("rg1", "rg2")))),
        eq(1L));
  }

  @Test
  void bossAuthorizationIsFixedAndCannotBeOverridden() {
    when(authRepository.user(1L, 1L)).thenReturn(Optional.of(boss));

    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        1L,
        new UserAuthorizationUpdateRequest(List.of(), List.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("BOSS_AUTHORIZATION_FIXED"));

    verify(authorizationService, never()).replaceUserOverrides(
        eq(1L), eq(1L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
    verify(dataScopeService, never()).replaceAssignments(
        eq(1L), eq(1L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
  }

  @Test
  void invalidAndDuplicateOverrideCodesAreRejectedBeforePersistence() {
    AuthUser finance = user(7L, "finance4", "FINANCE", null, 5L);
    stubAuthorizationSnapshot(finance);

    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        7L,
        new UserAuthorizationUpdateRequest(
            List.of(new UserPermissionOverrideRequest("missing.permission", "ALLOW")),
            List.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("PERMISSION_INVALID"));

    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        7L,
        new UserAuthorizationUpdateRequest(
            List.of(
                new UserPermissionOverrideRequest(PermissionCodes.FINANCE_EXPORT, "ALLOW"),
                new UserPermissionOverrideRequest(PermissionCodes.FINANCE_EXPORT, "DENY")
            ),
            List.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("PERMISSION_DUPLICATE"));

    verify(authorizationService, never()).replaceUserOverrides(
        eq(1L), eq(7L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
  }

  @Test
  void omittedDataScopeDomainsArePersistedAsExplicitNone() {
    AuthUser finance = user(8L, "finance5", "FINANCE", null, 2L);
    stubAuthorizationSnapshot(finance);
    when(authorizationService.incrementPermissionVersionAndDeleteTokens(1L, 8L)).thenReturn(3L);

    service.updateAuthorization(
        boss,
        8L,
        new UserAuthorizationUpdateRequest(List.of(), List.of())
    );

    verify(dataScopeService).replaceAssignments(
        eq(1L),
        eq(8L),
        org.mockito.ArgumentMatchers.argThat(scopes -> scopes.size() == 7
            && scopes.stream().allMatch(scope -> "NONE".equals(scope.mode()))),
        eq(1L)
    );
  }

  @Test
  void storeManagerCannotReceiveAllStoresOrUnknownStore() {
    AuthUser manager = user(4L, "manager", "STORE_MANAGER", "rg1", 4L);
    stubAuthorizationSnapshot(manager);

    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        4L,
        new UserAuthorizationUpdateRequest(
            List.of(),
            List.of(new UserDataScopeRequest("STORE", "ALL", List.of())))))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_MANAGER_SCOPE_INVALID"));

    AuthUser finance = user(5L, "finance2", "FINANCE", null, 4L);
    stubAuthorizationSnapshot(finance);
    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        5L,
        new UserAuthorizationUpdateRequest(
            List.of(),
            List.of(new UserDataScopeRequest("FINANCE", "STORE_LIST", List.of("missing"))))))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("DATA_SCOPE_STORE_INVALID"));
  }

  @Test
  void storeManagerCreationPersistsRequiredOwnStoreScopes() {
    when(authRepository.userExists(1L, "manager2")).thenReturn(false);
    when(passwordService.hash("secure-password")).thenReturn("password-hash");
    when(authorizationService.roleTemplatePermissions(1L, "STORE_MANAGER"))
        .thenReturn(Set.of(PermissionCodes.STORE_READ));
    AuthUser created = user(9L, "manager2", "STORE_MANAGER", "rg1", 1L);
    when(authRepository.findByUsername(1L, "manager2")).thenReturn(Optional.of(created));
    when(authRepository.storeScope(1L, 9L, "STORE_MANAGER", "rg1"))
        .thenReturn(List.of("rg1"));
    when(authRepository.assignedStoreScope(1L, 9L)).thenReturn(List.of("rg1"));
    when(authorizationService.effectivePermissions(created)).thenReturn(Set.of(PermissionCodes.STORE_READ));
    when(dataScopeService.dataScopes(created)).thenReturn(Map.of(
        DataScopeDomains.STORE, new DataScope(DataScopeModes.OWN_STORE, List.of("rg1"))));

    UserResponse response = service.create(
        boss,
        new UserCreateRequest(
            "manager2", "二店店长", "STORE_MANAGER", "rg1", List.of("rg1"), "secure-password")
    );

    verify(authRepository).createUserRequiringPasswordChange(
        1L, "manager2", "password-hash", "二店店长", "STORE_MANAGER", "rg1");
    verify(dataScopeService).replaceAssignments(
        eq(1L),
        eq(9L),
        org.mockito.ArgumentMatchers.argThat(scopes -> scopes.size() == 6
            && scopes.stream().anyMatch(scope -> DataScopeDomains.STORE.equals(scope.domainCode())
                && DataScopeModes.OWN_STORE.equals(scope.mode()))
            && scopes.stream().allMatch(scope -> DataScopeModes.OWN_STORE.equals(scope.mode()))),
        eq(1L)
    );
    assertThat(response.role()).isEqualTo("STORE_MANAGER");
    assertThat(response.storeScope()).containsExactly("rg1");
  }

  @Test
  void supervisorCreationPersistsConfiguredKnowledgeBaseStoreListAndReturnsActualScope() {
    when(authRepository.userExists(1L, "supervisor-stores")).thenReturn(false);
    when(passwordService.hash("secure-password")).thenReturn("password-hash");
    AuthUser created = user(12L, "supervisor-stores", "SUPERVISOR", null, 1L);
    when(authRepository.findByUsername(1L, "supervisor-stores")).thenReturn(Optional.of(created));
    when(authRepository.assignedStoreScope(1L, 12L)).thenReturn(List.of("rg1", "rg2"));
    when(authRepository.storeScope(1L, 12L, "SUPERVISOR", null)).thenReturn(List.of("all"));
    when(authorizationService.effectivePermissions(created))
        .thenReturn(Set.of(PermissionCodes.OPERATIONS_DASHBOARD_READ));
    when(dataScopeService.dataScopes(created)).thenReturn(Map.of(
        DataScopeDomains.STORE, DataScope.all()));

    UserResponse response = service.create(
        boss,
        new UserCreateRequest(
            "supervisor-stores",
            "双店督导",
            "SUPERVISOR",
            null,
            List.of("rg1", "rg2"),
            "secure-password")
    );

    verify(authRepository).createUserRequiringPasswordChange(
        1L, "supervisor-stores", "password-hash", "双店督导", "SUPERVISOR", null);
    verify(authRepository).replaceStoreScope(1L, 12L, List.of("rg1", "rg2"));
    verify(dataScopeService).replaceAssignments(
        eq(1L),
        eq(12L),
        org.mockito.ArgumentMatchers.argThat(scopes -> scopes.size() == 7
            && scopes.stream()
                .filter(scope -> Set.of(
                    DataScopeDomains.STORE,
                    DataScopeDomains.INSPECTION,
                    DataScopeDomains.EXAM,
                    DataScopeDomains.PLATFORM).contains(scope.domainCode()))
                .allMatch(scope -> DataScopeModes.STORE_LIST.equals(scope.mode())
                    && scope.storeIds().equals(List.of("rg1", "rg2")))
            && scopes.stream()
                .filter(scope -> !Set.of(
                    DataScopeDomains.STORE,
                    DataScopeDomains.INSPECTION,
                    DataScopeDomains.EXAM,
                    DataScopeDomains.PLATFORM).contains(scope.domainCode()))
                .allMatch(scope -> DataScopeModes.NONE.equals(scope.mode()))),
        eq(1L)
    );
    assertThat(response.storeId()).isNull();
    assertThat(response.storeScope()).containsExactly("rg1", "rg2");
  }

  @Test
  void enabledStoreManagerMayDenyStoreReadButCannotRemoveOwnStoreScope() {
    AuthUser manager = user(10L, "manager3", "STORE_MANAGER", "rg1", 4L);
    stubAuthorizationSnapshot(manager);
    when(authRepository.assignedStoreScope(1L, 10L)).thenReturn(List.of("rg1"));
    when(dataScopeService.dataScopes(manager)).thenReturn(Map.of(
        DataScopeDomains.STORE, new DataScope(DataScopeModes.OWN_STORE, List.of("rg1"))));
    when(authorizationService.effectivePermissions(manager)).thenReturn(
        Set.of(PermissionCodes.STORE_READ), Set.of());
    when(authorizationService.incrementPermissionVersionAndDeleteTokens(1L, 10L)).thenReturn(5L);

    UserAuthorizationResponse response = service.updateAuthorization(
        boss,
        10L,
        new UserAuthorizationUpdateRequest(
            List.of(new UserPermissionOverrideRequest(PermissionCodes.STORE_READ, "DENY")),
            List.of(new UserDataScopeRequest("STORE", "OWN_STORE", List.of()))));

    assertThat(response.effectivePermissions()).isEmpty();
    assertThat(response.defaultWorkspace()).isEqualTo("/no-permission");
    assertThat(response.effectivePermissionStatus()).isEqualTo("NO_WORKSPACE");
    verify(authorizationService).replaceUserOverrides(
        1L,
        10L,
        List.of(new UserPermissionOverride(PermissionCodes.STORE_READ, PermissionEffect.DENY)),
        1L
    );

    assertThatThrownBy(() -> service.updateAuthorization(
        boss,
        10L,
        new UserAuthorizationUpdateRequest(
            List.of(),
            List.of(new UserDataScopeRequest("STORE", "NONE", List.of())))))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_MANAGER_STORE_SCOPE_REQUIRED"));

    verify(authorizationService, times(1)).incrementPermissionVersionAndDeleteTokens(1L, 10L);
  }

  @Test
  void roleOrLegacyStoreRangeChangeIncrementsPermissionVersion() {
    AuthUser finance = user(6L, "finance3", "FINANCE", null, 9L);
    AuthUser manager = user(6L, "finance3", "STORE_MANAGER", "rg1", 10L);
    when(authRepository.user(1L, 6L)).thenReturn(Optional.of(finance), Optional.of(manager));
    when(authRepository.assignedStoreScope(1L, 6L)).thenReturn(
        List.of(), List.of("rg1"), List.of("rg1"));
    when(authRepository.storeScope(1L, 6L, "STORE_MANAGER", "rg1")).thenReturn(List.of("rg1"));
    when(authorizationService.roleTemplatePermissions(1L, "STORE_MANAGER"))
        .thenReturn(Set.of(PermissionCodes.STORE_READ));
    when(authorizationService.effectivePermissions(manager)).thenReturn(Set.of(PermissionCodes.STORE_READ));
    when(dataScopeService.dataScopes(manager)).thenReturn(Map.of(
        DataScopeDomains.STORE, new DataScope(DataScopeModes.OWN_STORE, List.of("rg1"))));
    when(authorizationService.incrementPermissionVersionAndDeleteTokens(1L, 6L)).thenReturn(10L);

    UserResponse response = service.update(
        boss,
        6L,
        new UserUpdateRequest("门店经理", "STORE_MANAGER", "rg1", List.of("rg1"), true)
    );

    verify(authRepository).updateUser(1L, 6L, "门店经理", "STORE_MANAGER", "rg1", true);
    verify(dataScopeService).replaceAssignments(
        eq(1L), eq(6L), org.mockito.ArgumentMatchers.argThat(scopes -> scopes.size() == 6
            && scopes.stream().allMatch(scope -> "OWN_STORE".equals(scope.mode()))), eq(1L));
    verify(authorizationService).incrementPermissionVersionAndDeleteTokens(1L, 6L);
    assertThat(response.role()).isEqualTo("STORE_MANAGER");
    ArgumentCaptor<AuditLogRequest> audit = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(eq(boss), audit.capture());
    assertThat(audit.getValue().beforeJson()).contains("FINANCE", "permissionVersion\":9");
    assertThat(audit.getValue().afterJson()).contains("STORE_MANAGER", "permissionVersion\":10");
  }

  @Test
  void accountCreationRequiresAtLeastOneUsableWorkspace() {
    when(authRepository.userExists(eq(1L), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
    when(passwordService.hash("secure-password")).thenReturn("password-hash");
    AuthUser warehouse = user(20L, "warehouse2", "WAREHOUSE", null, 1L);
    AuthUser operations = user(21L, "supervisor2", "SUPERVISOR", null, 1L);
    AuthUser employee = user(22L, "employee2", "EMPLOYEE", null, 1L);
    when(authRepository.findByUsername(1L, "warehouse2")).thenReturn(Optional.of(warehouse));
    when(authRepository.findByUsername(1L, "supervisor2")).thenReturn(Optional.of(operations));
    when(authRepository.findByUsername(1L, "employee2")).thenReturn(Optional.of(employee));
    when(authorizationService.effectivePermissions(warehouse))
        .thenReturn(Set.of(PermissionCodes.WAREHOUSE_CENTRAL_READ));
    when(dataScopeService.dataScopes(warehouse)).thenReturn(Map.of(
        DataScopeDomains.WAREHOUSE,
        new DataScope(DataScopeModes.CENTRAL_WAREHOUSE, List.of())));
    when(authorizationService.effectivePermissions(employee)).thenReturn(Set.of(PermissionCodes.EXAM_LEARN));
    when(dataScopeService.dataScopes(employee)).thenReturn(Map.of(
        DataScopeDomains.EXAM, new DataScope(DataScopeModes.SELF, List.of())));

    service.create(boss, new UserCreateRequest(
        "warehouse2", "仓库", "WAREHOUSE", null, List.of(), "secure-password"));
    assertThatThrownBy(() -> service.create(boss, new UserCreateRequest(
        "supervisor2", "督导", "SUPERVISOR", null, List.of(), "secure-password")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("ACCOUNT_WORKSPACE_REQUIRED"));
    service.create(boss, new UserCreateRequest(
        "employee2", "学员", "EMPLOYEE", null, List.of(), "secure-password"));

    verify(authRepository).createUserRequiringPasswordChange(
        1L, "warehouse2", "password-hash", "仓库", "WAREHOUSE", null);
    verify(authRepository).createUserRequiringPasswordChange(
        1L, "supervisor2", "password-hash", "督导", "SUPERVISOR", null);
    verify(authRepository).createUserRequiringPasswordChange(
        1L, "employee2", "password-hash", "学员", "EMPLOYEE", null);

    for (String legacyRole : List.of("OPERATIONS", "OPS")) {
      assertThatThrownBy(() -> service.create(boss, new UserCreateRequest(
          "legacy-" + legacyRole.toLowerCase(), "历史运营", legacyRole, null, List.of(), "secure-password")))
          .isInstanceOf(BusinessException.class)
          .satisfies(error -> assertThat(((BusinessException) error).getCode())
              .isEqualTo("ROLE_LEGACY_REJECTED"));
    }

    verify(authRepository, never()).createUserRequiringPasswordChange(
        eq(1L), org.mockito.ArgumentMatchers.startsWith("legacy-"), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void oldUpdateRejectsReenablingAccountWithoutUsableWorkspace() {
    AuthUser disabled = new AuthUser(
        30L, 1L, "测试企业", "supervisor3", "hash", "督导", "SUPERVISOR", null, false, 2L);
    AuthUser reenabled = new AuthUser(
        30L, 1L, "测试企业", "supervisor3", "hash", "督导", "SUPERVISOR", null, true, 2L);
    when(authRepository.user(1L, 30L)).thenReturn(Optional.of(disabled), Optional.of(reenabled));
    when(authRepository.assignedStoreScope(1L, 30L)).thenReturn(List.of());
    when(authorizationService.effectivePermissions(reenabled)).thenReturn(Set.of());
    when(dataScopeService.dataScopes(reenabled)).thenReturn(Map.of());

    assertThatThrownBy(() -> service.update(
        boss,
        30L,
        new UserUpdateRequest("督导", "SUPERVISOR", null, List.of(), true)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("ACCOUNT_WORKSPACE_REQUIRED"));

    verify(authorizationService, never()).incrementPermissionVersionAndDeleteTokens(1L, 30L);
  }

  @Test
  void oldUpdateRejectsEnabledRoleChangeWithoutUsableWorkspace() {
    AuthUser finance = user(31L, "finance4", "FINANCE", null, 2L);
    AuthUser operations = user(31L, "finance4", "SUPERVISOR", null, 2L);
    when(authRepository.user(1L, 31L)).thenReturn(Optional.of(finance), Optional.of(operations));
    when(authRepository.assignedStoreScope(1L, 31L)).thenReturn(List.of());
    when(authorizationService.effectivePermissions(operations)).thenReturn(Set.of());
    when(dataScopeService.dataScopes(operations)).thenReturn(Map.of());

    assertThatThrownBy(() -> service.update(
        boss,
        31L,
        new UserUpdateRequest("督导", "SUPERVISOR", null, List.of(), true)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("ACCOUNT_WORKSPACE_REQUIRED"));

    verify(authorizationService, never()).incrementPermissionVersionAndDeleteTokens(1L, 31L);
  }

  @Test
  void unifiedAccessProfileReplacesEveryAuthorizationPartAndInvalidatesOnce() {
    AuthUser manager = user(24L, "manager4", "STORE_MANAGER", "rg1", 6L);
    when(authRepository.user(1L, 24L)).thenReturn(Optional.of(manager));
    when(authRepository.assignedStoreScope(1L, 24L)).thenReturn(List.of("rg1"));
    when(authRepository.storeScope(1L, 24L, "STORE_MANAGER", "rg1")).thenReturn(List.of("rg1"));
    when(authorizationService.roleTemplatePermissions(1L, "STORE_MANAGER"))
        .thenReturn(Set.of(PermissionCodes.STORE_READ));
    when(authorizationService.userOverrides(1L, 24L)).thenReturn(
        List.of(),
        List.of(new UserPermissionOverride(PermissionCodes.STORE_READ, PermissionEffect.ALLOW))
    );
    when(authorizationService.effectivePermissions(manager)).thenReturn(Set.of(PermissionCodes.STORE_READ));
    when(dataScopeService.assignmentsForUser(1L, 24L)).thenReturn(List.of(
        new DataScopeAssignment(DataScopeDomains.STORE, DataScopeModes.OWN_STORE, List.of())));
    when(dataScopeService.dataScopes(manager)).thenReturn(Map.of(
        DataScopeDomains.STORE, new DataScope(DataScopeModes.OWN_STORE, List.of("rg1"))));
    when(authorizationService.incrementPermissionVersionAndDeleteTokens(1L, 24L)).thenReturn(7L);

    UserAccessProfileResponse response = service.updateAccessProfile(
        boss,
        24L,
        new UserAccessProfileUpdateRequest(
            "四店店长",
            "STORE_MANAGER",
            "rg1",
            List.of("rg1"),
            true,
            List.of(new UserPermissionOverrideRequest(PermissionCodes.STORE_READ, "ALLOW")),
            List.of(new UserDataScopeRequest(DataScopeDomains.STORE, DataScopeModes.OWN_STORE, List.of()))
        )
    );

    verify(authRepository).updateUser(1L, 24L, "四店店长", "STORE_MANAGER", "rg1", true);
    verify(authRepository).replaceStoreScope(1L, 24L, List.of("rg1"));
    verify(authorizationService).replaceUserOverrides(
        1L, 24L,
        List.of(new UserPermissionOverride(PermissionCodes.STORE_READ, PermissionEffect.ALLOW)),
        1L);
    verify(dataScopeService).replaceAssignments(
        eq(1L), eq(24L), org.mockito.ArgumentMatchers.argThat(scopes -> scopes.size() == 7), eq(1L));
    verify(authorizationService, times(1)).incrementPermissionVersionAndDeleteTokens(1L, 24L);
    assertThat(response.authorization().permissionVersion()).isEqualTo(7L);
    assertThat(response.authorization().defaultWorkspace()).isEqualTo("/store");
  }

  @Test
  void unifiedSupervisorAccessProfilePersistsAndReturnsKnowledgeBaseStoreList() {
    AuthUser supervisor = user(25L, "supervisor-profile", "SUPERVISOR", null, 6L);
    when(authRepository.user(1L, 25L)).thenReturn(Optional.of(supervisor));
    when(authRepository.assignedStoreScope(1L, 25L)).thenReturn(List.of("rg1", "rg2"));
    when(authRepository.storeScope(1L, 25L, "SUPERVISOR", null)).thenReturn(List.of("all"));
    when(authorizationService.roleTemplatePermissions(1L, "SUPERVISOR"))
        .thenReturn(Set.of(PermissionCodes.OPERATIONS_DASHBOARD_READ));
    when(authorizationService.userOverrides(1L, 25L)).thenReturn(List.of());
    when(authorizationService.effectivePermissions(supervisor))
        .thenReturn(Set.of(PermissionCodes.OPERATIONS_DASHBOARD_READ));
    when(dataScopeService.assignmentsForUser(1L, 25L)).thenReturn(
        List.of(new DataScopeAssignment(
            DataScopeDomains.STORE, DataScopeModes.STORE_LIST, List.of("rg1"))),
        List.of(new DataScopeAssignment(
            DataScopeDomains.STORE, DataScopeModes.STORE_LIST, List.of("rg1", "rg2"))));
    when(dataScopeService.dataScopes(supervisor)).thenReturn(Map.of(
        DataScopeDomains.STORE, DataScope.all()));
    when(authorizationService.incrementPermissionVersionAndDeleteTokens(1L, 25L)).thenReturn(7L);

    UserAccessProfileResponse response = service.updateAccessProfile(
        boss,
        25L,
        new UserAccessProfileUpdateRequest(
            "双店督导",
            "SUPERVISOR",
            null,
            List.of("rg1", "rg2"),
            true,
            List.of(),
            List.of(new UserDataScopeRequest(
                DataScopeDomains.STORE,
                DataScopeModes.STORE_LIST,
                List.of("rg1", "rg2")))
        )
    );

    verify(authRepository).updateUser(1L, 25L, "双店督导", "SUPERVISOR", null, true);
    verify(authRepository).replaceStoreScope(1L, 25L, List.of("rg1", "rg2"));
    verify(dataScopeService).replaceAssignments(
        eq(1L),
        eq(25L),
        org.mockito.ArgumentMatchers.argThat(scopes -> scopes.size() == 7
            && scopes.stream().anyMatch(scope -> DataScopeDomains.STORE.equals(scope.domainCode())
                && DataScopeModes.STORE_LIST.equals(scope.mode())
                && scope.storeIds().equals(List.of("rg1", "rg2")))),
        eq(1L));
    assertThat(response.user().storeId()).isNull();
    assertThat(response.user().storeScope()).containsExactly("rg1", "rg2");
    assertThat(response.authorization().dataScopes())
        .anySatisfy(scope -> {
          assertThat(scope.domainCode()).isEqualTo(DataScopeDomains.STORE);
          assertThat(scope.mode()).isEqualTo(DataScopeModes.STORE_LIST);
          assertThat(scope.storeIds()).containsExactly("rg1", "rg2");
        });
  }

  @Test
  void unifiedSupervisorAccessProfileRejectsMismatchedAccountAndDataScopes() {
    AuthUser supervisor = user(26L, "supervisor-mismatch", "SUPERVISOR", null, 6L);
    stubAuthorizationSnapshot(supervisor);
    when(authRepository.assignedStoreScope(1L, 26L)).thenReturn(List.of("rg1"));

    assertThatThrownBy(() -> service.updateAccessProfile(
        boss,
        26L,
        new UserAccessProfileUpdateRequest(
            "范围冲突督导",
            "SUPERVISOR",
            null,
            List.of("rg1"),
            true,
            List.of(),
            List.of(new UserDataScopeRequest(
                DataScopeDomains.STORE,
                DataScopeModes.STORE_LIST,
                List.of("rg2")))
        )
    )).isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("SUPERVISOR_STORE_SCOPE_MISMATCH"));

    verify(authRepository, never()).updateUser(
        eq(1L), eq(26L), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyBoolean());
    verify(dataScopeService, never()).replaceAssignments(
        eq(1L), eq(26L), org.mockito.ArgumentMatchers.anyList(), eq(1L));
  }

  @Test
  void storeManagerAccountMustBindExactlyOneStore() {
    AuthUser warehouse = user(23L, "warehouse3", "WAREHOUSE", null, 1L);
    when(authRepository.user(1L, 23L)).thenReturn(Optional.of(warehouse));
    when(authRepository.assignedStoreScope(1L, 23L)).thenReturn(List.of());

    assertThatThrownBy(() -> service.update(
        boss,
        23L,
        new UserUpdateRequest(
            "跨店店长", "STORE_MANAGER", "rg1", List.of("rg1", "rg2"), true)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_MANAGER_SINGLE_STORE_REQUIRED"));

    verify(authRepository, never()).updateUser(
        eq(1L), eq(23L), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyBoolean());
  }

  private void stubAuthorizationSnapshot(AuthUser target) {
    when(authRepository.user(1L, target.id())).thenReturn(Optional.of(target));
    when(authorizationService.roleTemplatePermissions(1L, target.role())).thenReturn(
        "STORE_MANAGER".equals(target.role()) ? Set.of(PermissionCodes.STORE_READ) : Set.of());
    when(dataScopeService.assignmentsForUser(1L, target.id())).thenReturn(List.of());
    when(authorizationService.userOverrides(1L, target.id())).thenReturn(List.of());
    when(authorizationService.effectivePermissions(target)).thenReturn(Set.of());
  }

  private AuthUser user(long id, String username, String role, String storeId, long permissionVersion) {
    return new AuthUser(
        id, 1L, "测试企业", username, "hash", username, role, storeId, true, permissionVersion);
  }

  private PermissionCatalogEntry catalogEntry(String permissionCode) {
    return new PermissionCatalogEntry(
        permissionCode, "TEST", permissionCode, permissionCode, "LOW", true, 1);
  }
}
