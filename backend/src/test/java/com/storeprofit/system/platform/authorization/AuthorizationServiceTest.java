package com.storeprofit.system.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorizationServiceTest {
  private final AuthorizationRepository repository = mock(AuthorizationRepository.class);
  private final AuthorizationService service = new AuthorizationService(repository);

  @Test
  void effectivePermissionsApplyAllowThenDenyWithDenyWinning() {
    AuthUser finance = user(8L, "FINANCE");
    when(repository.roleTemplatePermissions(1L, "FINANCE"))
        .thenReturn(Set.of(PermissionCodes.FINANCE_PROFIT_READ, PermissionCodes.FINANCE_PROFIT_WRITE));
    when(repository.enabledPermissionCodes()).thenReturn(Set.of(
        PermissionCodes.FINANCE_PROFIT_READ,
        PermissionCodes.FINANCE_PROFIT_WRITE,
        PermissionCodes.FINANCE_EXPORT));
    when(repository.userOverrides(1L, 8L)).thenReturn(List.of(
        new UserPermissionOverride(PermissionCodes.FINANCE_EXPORT, PermissionEffect.ALLOW),
        new UserPermissionOverride(PermissionCodes.FINANCE_PROFIT_WRITE, PermissionEffect.DENY)
    ));

    assertThat(service.effectivePermissions(finance))
        .containsExactlyInAnyOrder(PermissionCodes.FINANCE_PROFIT_READ, PermissionCodes.FINANCE_EXPORT)
        .doesNotContain(PermissionCodes.FINANCE_PROFIT_WRITE);
  }

  @Test
  void bossKeepsStablePermissionsWhenCatalogIsEmptyAndOwnsEveryPermissionCheck() {
    AuthUser boss = user(1L, "BOSS");
    when(repository.enabledPermissionCodes()).thenReturn(Set.of());

    assertThat(service.effectivePermissions(boss))
        .containsAll(PermissionCodes.ALL)
        .contains(PermissionCodes.STORE_READ, PermissionCodes.SYSTEM_DASHBOARD_READ);
    assertThat(service.hasPermission(boss, "future.permission.not-yet-catalogued")).isTrue();
  }

  @Test
  void storeManageIsNeverExposedToNonBossEvenWithLegacyTemplateAndPersonalAllow() {
    AuthUser manager = user(8L, "STORE_MANAGER");
    when(repository.roleTemplatePermissions(1L, "STORE_MANAGER"))
        .thenReturn(Set.of(PermissionCodes.STORE_READ, PermissionCodes.STORE_MANAGE));
    when(repository.enabledPermissionCodes()).thenReturn(Set.of(
        PermissionCodes.STORE_READ, PermissionCodes.STORE_MANAGE));
    when(repository.userOverrides(1L, 8L)).thenReturn(List.of(
        new UserPermissionOverride(PermissionCodes.STORE_MANAGE, PermissionEffect.ALLOW)));

    assertThat(service.roleTemplatePermissions(1L, "STORE_MANAGER"))
        .contains(PermissionCodes.STORE_READ)
        .doesNotContain(PermissionCodes.STORE_MANAGE);
    assertThat(service.effectivePermissions(manager))
        .contains(PermissionCodes.STORE_READ)
        .doesNotContain(PermissionCodes.STORE_MANAGE);
  }

  @Test
  void financeImportIsNeverExposedToNonFinanceEvenWithTemplateAndPersonalAllow() {
    AuthUser manager = user(8L, "STORE_MANAGER");
    AuthUser finance = user(9L, "FINANCE");
    when(repository.roleTemplatePermissions(1L, "STORE_MANAGER"))
        .thenReturn(Set.of(PermissionCodes.FINANCE_PROFIT_IMPORT));
    when(repository.roleTemplatePermissions(1L, "FINANCE"))
        .thenReturn(Set.of(PermissionCodes.FINANCE_PROFIT_IMPORT));
    when(repository.enabledPermissionCodes()).thenReturn(Set.of(PermissionCodes.FINANCE_PROFIT_IMPORT));
    when(repository.userOverrides(1L, 8L)).thenReturn(List.of(
        new UserPermissionOverride(PermissionCodes.FINANCE_PROFIT_IMPORT, PermissionEffect.ALLOW)));
    when(repository.userOverrides(1L, 9L)).thenReturn(List.of());

    assertThat(service.roleTemplatePermissions(1L, "STORE_MANAGER"))
        .doesNotContain(PermissionCodes.FINANCE_PROFIT_IMPORT);
    assertThat(service.effectivePermissions(manager))
        .doesNotContain(PermissionCodes.FINANCE_PROFIT_IMPORT);
    assertThat(service.effectivePermissions(finance))
        .contains(PermissionCodes.FINANCE_PROFIT_IMPORT);
  }

  @Test
  void bossSessionPermissionSetAlsoIncludesDatabaseCatalogExtensions() {
    AuthUser boss = user(1L, "BOSS");
    when(repository.enabledPermissionCodes()).thenReturn(Set.of("future.permission.catalogued"));

    assertThat(service.effectivePermissions(boss))
        .containsAll(PermissionCodes.ALL)
        .contains("future.permission.catalogued");
    assertThat(service.roleTemplatePermissions(1L, "BOSS"))
        .containsAll(PermissionCodes.ALL)
        .contains("future.permission.catalogued");
  }

  @Test
  void employeeHardCeilingKeepsOnlyLearningAndEmployeeAssistantPermissions() {
    AuthUser employee = user(9L, "EMPLOYEE");
    when(repository.roleTemplatePermissions(1L, "EMPLOYEE"))
        .thenReturn(Set.of(
            PermissionCodes.EXAM_LEARN,
            PermissionCodes.EMPLOYEE_ASSISTANT_USE,
            PermissionCodes.SALARY_READ
        ));
    when(repository.enabledPermissionCodes()).thenReturn(Set.of(
        PermissionCodes.EXAM_LEARN,
        PermissionCodes.EMPLOYEE_ASSISTANT_USE,
        PermissionCodes.SALARY_READ,
        PermissionCodes.PLATFORM_MANAGE
    ));
    when(repository.userOverrides(1L, 9L)).thenReturn(List.of(
        new UserPermissionOverride(PermissionCodes.PLATFORM_MANAGE, PermissionEffect.ALLOW)
    ));

    assertThat(service.effectivePermissions(employee)).containsExactlyInAnyOrder(
        PermissionCodes.EXAM_LEARN,
        PermissionCodes.EMPLOYEE_ASSISTANT_USE
    );
  }

  @Test
  void supervisorInheritsOperationsCapabilitiesButNotBossOnlyPermissions() {
    AuthUser supervisor = user(10L, "SUPERVISOR");
    when(repository.roleTemplatePermissions(1L, "SUPERVISOR"))
        .thenReturn(Set.of(
            PermissionCodes.INSPECTION_READ,
            PermissionCodes.INSPECTION_MANAGE,
            PermissionCodes.ATTACHMENT_READ,
            PermissionCodes.OPERATIONS_DASHBOARD_READ,
            PermissionCodes.PLATFORM_MANAGE,
            PermissionCodes.INVENTORY_MANAGE,
            PermissionCodes.EXAM_MANAGE,
            PermissionCodes.FINANCE_PROFIT_WRITE,
            PermissionCodes.SALARY_EDIT,
            PermissionCodes.WAREHOUSE_CENTRAL_MANAGE
        ));
    when(repository.enabledPermissionCodes()).thenReturn(Set.of(
        PermissionCodes.INSPECTION_READ,
        PermissionCodes.INSPECTION_MANAGE,
        PermissionCodes.ATTACHMENT_READ,
        PermissionCodes.TODO_READ,
        PermissionCodes.OPERATIONS_DASHBOARD_READ,
        PermissionCodes.PLATFORM_MANAGE,
        PermissionCodes.INVENTORY_MANAGE,
        PermissionCodes.EXAM_MANAGE,
        PermissionCodes.FINANCE_PROFIT_WRITE,
        PermissionCodes.SALARY_EDIT,
        PermissionCodes.WAREHOUSE_CENTRAL_MANAGE
    ));
    when(repository.userOverrides(1L, 10L)).thenReturn(List.of(
        new UserPermissionOverride(PermissionCodes.TODO_READ, PermissionEffect.ALLOW),
        new UserPermissionOverride(PermissionCodes.PLATFORM_MANAGE, PermissionEffect.ALLOW)
    ));

    assertThat(service.roleTemplatePermissions(1L, "SUPERVISOR"))
        .contains(PermissionCodes.INSPECTION_READ, PermissionCodes.INSPECTION_MANAGE, PermissionCodes.ATTACHMENT_READ,
            PermissionCodes.OPERATIONS_DASHBOARD_READ,
            PermissionCodes.PLATFORM_MANAGE,
            PermissionCodes.INVENTORY_MANAGE,
            PermissionCodes.EXAM_MANAGE)
        .doesNotContain(PermissionCodes.FINANCE_PROFIT_WRITE, PermissionCodes.SALARY_EDIT,
            PermissionCodes.WAREHOUSE_CENTRAL_MANAGE, PermissionCodes.STORE_MANAGE,
            PermissionCodes.SYSTEM_USER_MANAGE);
    assertThat(service.effectivePermissions(supervisor))
        .contains(PermissionCodes.INSPECTION_READ, PermissionCodes.INSPECTION_MANAGE, PermissionCodes.TODO_READ,
            PermissionCodes.OPERATIONS_DASHBOARD_READ,
            PermissionCodes.PLATFORM_MANAGE,
            PermissionCodes.INVENTORY_MANAGE,
            PermissionCodes.EXAM_MANAGE)
        .doesNotContain(PermissionCodes.FINANCE_PROFIT_WRITE, PermissionCodes.SALARY_EDIT,
            PermissionCodes.WAREHOUSE_CENTRAL_MANAGE, PermissionCodes.STORE_MANAGE,
            PermissionCodes.SYSTEM_USER_MANAGE);
    assertThat(AuthorizationService.legacyTemplatePermissions("SUPERVISOR"))
        .contains(PermissionCodes.INSPECTION_READ, PermissionCodes.INSPECTION_MANAGE, PermissionCodes.TODO_TRANSITION,
            PermissionCodes.OPERATIONS_DASHBOARD_READ, PermissionCodes.PLATFORM_MANAGE);
  }

  @Test
  void legacyOperationsResolvesToSupervisorTemplate() {
    AuthUser operations = user(11L, "OPERATIONS");
    when(repository.roleTemplatePermissions(1L, "SUPERVISOR"))
        .thenReturn(Set.of(
            PermissionCodes.OPERATIONS_DASHBOARD_READ,
            PermissionCodes.PLATFORM_MANAGE,
            PermissionCodes.INVENTORY_MANAGE,
            PermissionCodes.EXAM_MANAGE,
            PermissionCodes.TODO_READ
        ));
    when(repository.enabledPermissionCodes()).thenReturn(Set.of(
        PermissionCodes.OPERATIONS_DASHBOARD_READ,
        PermissionCodes.PLATFORM_MANAGE,
        PermissionCodes.INVENTORY_MANAGE,
        PermissionCodes.EXAM_MANAGE,
        PermissionCodes.TODO_READ
    ));
    when(repository.userOverrides(1L, 11L)).thenReturn(List.of());

    assertThat(service.effectivePermissions(operations))
        .contains(
            PermissionCodes.OPERATIONS_DASHBOARD_READ,
            PermissionCodes.PLATFORM_MANAGE,
            PermissionCodes.INVENTORY_MANAGE,
            PermissionCodes.EXAM_MANAGE,
            PermissionCodes.TODO_READ
        );
    assertThat(AuthorizationService.legacyTemplatePermissions("OPERATIONS"))
        .contains(
            PermissionCodes.OPERATIONS_DASHBOARD_READ,
            PermissionCodes.PLATFORM_MANAGE,
            PermissionCodes.INVENTORY_MANAGE,
            PermissionCodes.EXAM_MANAGE
        );
  }

  @Test
  void unknownOverridePermissionIsRejectedBeforePersistence() {
    when(repository.enabledPermissionCodes()).thenReturn(Set.of(PermissionCodes.EXAM_LEARN));

    assertThatThrownBy(() -> service.replaceUserOverrides(
        1L,
        9L,
        List.of(new UserPermissionOverride("unknown.permission", PermissionEffect.ALLOW)),
        1L
    )).isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("PERMISSION_INVALID"));
  }

  private AuthUser user(long id, String role) {
    return new AuthUser(id, 1L, "测试租户", "user-" + id, "hash", "测试账号", role, null, true, 3L);
  }
}
