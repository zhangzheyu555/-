package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AccessControlServiceTest {
  private final AuthService authService = mock(AuthService.class);
  private final AuthRepository authRepository = mock(AuthRepository.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final AccessControlService service = new AccessControlService(authService, authRepository, auditRepository);

  @Test
  void storeManagerCannotAccessAnotherStoreAndDenialIsAudited() {
    AuthUser manager = user("STORE_MANAGER", "rg1");
    when(authRepository.assignedStoreScope(1L, 10L)).thenReturn(List.of("rg1"));

    assertThatThrownBy(() -> service.requireStoreAccess(manager, "rg2", "查看利润数据"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(auditRepository).writePermissionDenied(
        any(AuthUser.class),
        any(String.class),
        any(String.class),
        any(String.class),
        any(String.class),
        any(String.class)
    );
  }

  @Test
  void operationsRoleCannotReadFinanceData() {
    AuthUser operations = user("OPERATIONS", null);

    assertThatThrownBy(() -> service.requireFinanceRead(operations))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void bossCanAccessAnyStore() {
    AuthUser boss = user("BOSS", null);

    service.requireStoreAccess(boss, "rg2", "查看利润数据");

    assertThat(service.canAccessStore(boss, "rg2")).isTrue();
  }

  @Test
  void warehouseAndSupervisorCannotSubmitExpenseSupplements() {
    assertThatThrownBy(() -> service.requireExpenseWrite(user("WAREHOUSE", "rg1")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> service.requireExpenseWrite(user("SUPERVISOR", "rg1")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void bossHasWildcardPermissionAcrossBusinessModules() {
    AuthUser boss = user("BOSS", null);

    service.requireFinanceWrite(boss);
    service.requireExpenseReview(boss);
    service.requireSalaryReview(boss);
    service.requireInspectionManage(boss);
    service.requirePlatformAccess(boss);
    service.requireExamManage(boss);
    service.requireDataExport(boss);
    service.requireUserManagementWrite(boss);

    assertThat(AccessControlService.hasAnyRole(boss, Set.of())).isTrue();
    assertThat(service.allowedStoreIds(boss)).containsExactly("all");
  }

  @Test
  void legacyHighestRoleCodesAreCanonicalizedToBoss() {
    assertThat(AccessControlService.canonicalRole("ADMIN")).isEqualTo("BOSS");
    assertThat(AccessControlService.canonicalRole("OWNER")).isEqualTo("BOSS");
    assertThat(AccessControlService.isBoss(user("ADMIN", null))).isTrue();
    assertThat(AccessControlService.isBoss(user("OWNER", null))).isTrue();
  }

  @Test
  void ordinaryRoleCannotManageUserPermissions() {
    AuthUser finance = user("FINANCE", null);

    assertThatThrownBy(() -> service.requireUserManagementWrite(finance))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void personalPermissionOverrideCannotBypassBossOnlyAccountManagementBoundary() {
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    AccessControlService bossOnlyService = new AccessControlService(
        authService,
        authRepository,
        auditRepository,
        authorizationService,
        mock(DataScopeService.class)
    );
    AuthUser finance = user("FINANCE", null);
    when(authorizationService.hasPermission(finance, PermissionCodes.SYSTEM_USER_MANAGE)).thenReturn(true);

    assertThatThrownBy(() -> bossOnlyService.requireUserManagementWrite(finance))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(10L, 1L, "默认企业", "user", "", "测试账号", role, storeId, true);
  }
}
