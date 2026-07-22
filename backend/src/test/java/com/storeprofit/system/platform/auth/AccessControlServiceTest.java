package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import org.springframework.http.HttpStatus;

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
  void supervisorCannotReadFinanceData() {
    AuthUser supervisor = user("SUPERVISOR", null);

    assertThatThrownBy(() -> service.requireFinanceRead(supervisor))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void storeManagersCanNoLongerWriteProfitDataEvenWhenLegacyPermissionExists() {
    AuthUser manager = user("STORE_MANAGER", "rg1");
    AuthUser finance = user("FINANCE", null);
    AuthUser boss = user("BOSS", null);
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    AccessControlService importAccess = new AccessControlService(
        authService, authRepository, auditRepository, authorizationService, null);

    // STORE_MANAGER with a stale personal ALLOW must still be rejected.
    when(authorizationService.hasPermission(manager, PermissionCodes.FINANCE_PROFIT_WRITE)).thenReturn(true);
    assertThatThrownBy(() -> service.requireFinanceWrite(manager))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException forbidden = (BusinessException) error;
          assertThat(forbidden.getCode()).isEqualTo("FORBIDDEN");
          assertThat(forbidden.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });

    // FINANCE and BOSS retain full profit write access.
    service.requireFinanceWrite(finance);
    service.requireFinanceWrite(boss);

    verify(auditRepository, atLeastOnce()).writePermissionDenied(
        any(AuthUser.class),
        any(String.class),
        any(String.class),
        any(String.class),
        isNull(),
        isNull(),
        any(String.class)
    );
  }

  @Test
  void storeManagerCannotExportOperatingDataEvenWithPersonalPermission() {
    AuthUser manager = user("STORE_MANAGER", "rg1");
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    AccessControlService exportAccess = new AccessControlService(
        authService, authRepository, auditRepository, authorizationService, null);
    when(authorizationService.hasPermission(manager, PermissionCodes.FINANCE_EXPORT)).thenReturn(true);

    assertThatThrownBy(() -> exportAccess.requireDataExport(manager, "rg1", "2026-07"))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("FORBIDDEN");
          assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });

    verify(auditRepository).writePermissionDenied(
        org.mockito.ArgumentMatchers.eq(manager),
        org.mockito.ArgumentMatchers.eq("导出经营数据"),
        org.mockito.ArgumentMatchers.eq("API"),
        org.mockito.ArgumentMatchers.eq(PermissionCodes.FINANCE_EXPORT),
        org.mockito.ArgumentMatchers.eq("rg1"),
        org.mockito.ArgumentMatchers.eq("2026-07"),
        org.mockito.ArgumentMatchers.contains("仅限财务或老板")
    );
  }

  @Test
  void rejectedFinanceWritesKeepTheRequestedStoreAndMonthInTheAuditRecord() {
    AuthUser supervisor = user("SUPERVISOR", null);

    assertThatThrownBy(() -> service.requireFinanceWrite(supervisor, "rg2", "2026-07"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(auditRepository).writePermissionDenied(
        org.mockito.ArgumentMatchers.eq(supervisor),
        org.mockito.ArgumentMatchers.eq("录入经营数据"),
        org.mockito.ArgumentMatchers.eq("API"),
        org.mockito.ArgumentMatchers.eq(PermissionCodes.FINANCE_PROFIT_WRITE),
        org.mockito.ArgumentMatchers.eq("rg2"),
        org.mockito.ArgumentMatchers.eq("2026-07"),
        org.mockito.ArgumentMatchers.contains("账号不具备权限")
    );
  }

  @Test
  void rejectedSalaryEditKeepsTheRequestedRecordStoreAndPeriodInTheAuditRecord() {
    AuthUser supervisor = user("SUPERVISOR", "rg1");

    assertThatThrownBy(() -> service.requireSalaryEdit(
        supervisor, "salary-401", "rg2", "2026-10"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(auditRepository).writePermissionDenied(
        org.mockito.ArgumentMatchers.eq(supervisor),
        org.mockito.ArgumentMatchers.eq("录入工资数据"),
        org.mockito.ArgumentMatchers.eq("salary_record"),
        org.mockito.ArgumentMatchers.eq("salary-401"),
        org.mockito.ArgumentMatchers.eq("rg2"),
        org.mockito.ArgumentMatchers.eq("2026-10"),
        org.mockito.ArgumentMatchers.contains(PermissionCodes.SALARY_EDIT)
    );
  }

  @Test
  void storeManagersCanNoLongerDeleteProfitData() {
    AuthUser manager = user("STORE_MANAGER", "rg1");
    assertThatThrownBy(() -> service.requireFinanceDelete(manager))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException forbidden = (BusinessException) error;
          assertThat(forbidden.getCode()).isEqualTo("FORBIDDEN");
          assertThat(forbidden.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });
  }

  @Test
  void batchImportIsLimitedToFinanceOrBossAfterStoreManagersLoseProfitWritePermission() {
    AuthUser manager = user("STORE_MANAGER", "rg1");
    AuthUser finance = user("FINANCE", null);
    AuthUser boss = user("BOSS", null);
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    AccessControlService importAccess = new AccessControlService(
        authService, authRepository, auditRepository, authorizationService, null);

    when(authorizationService.hasPermission(manager, PermissionCodes.FINANCE_PROFIT_IMPORT)).thenReturn(true);

    assertThatThrownBy(() -> importAccess.requireFinanceImport(manager))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException forbidden = (BusinessException) error;
          assertThat(forbidden.getCode()).isEqualTo("FORBIDDEN");
          assertThat(forbidden.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });

    service.requireFinanceImport(finance);
    service.requireFinanceImport(boss);
    verify(auditRepository).writePermissionDenied(
        any(AuthUser.class),
        any(String.class),
        any(String.class),
        any(String.class),
        isNull(),
        isNull(),
        any(String.class)
    );
  }

  @Test
  void importChecksFinanceWriteBeforeAFileOrPreviewJobCanBeAccepted() {
    AuthUser finance = user("FINANCE", null);
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    AccessControlService importAccess = new AccessControlService(
        authService, authRepository, auditRepository, authorizationService, null);
    when(authorizationService.hasPermission(finance, PermissionCodes.FINANCE_PROFIT_IMPORT)).thenReturn(true);
    when(authorizationService.hasPermission(finance, PermissionCodes.FINANCE_PROFIT_WRITE)).thenReturn(false);

    assertThatThrownBy(() -> importAccess.requireFinanceImport(finance))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException forbidden = (BusinessException) error;
          assertThat(forbidden.getCode()).isEqualTo("FORBIDDEN");
          assertThat(forbidden.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });

    verify(auditRepository).writePermissionDenied(
        any(AuthUser.class),
        any(String.class),
        any(String.class),
        any(String.class),
        isNull(),
        isNull(),
        any(String.class)
    );
  }

  @Test
  void bossCanAccessAnyStore() {
    AuthUser boss = user("BOSS", null);

    service.requireStoreAccess(boss, "rg2", "查看利润数据");

    assertThat(service.canAccessStore(boss, "rg2")).isTrue();
  }

  @Test
  void onlyBossCanMaintainStoreRecordsEvenWhenLegacyRolesUsedStoreManage() {
    AuthUser manager = user("STORE_MANAGER", "rg1");
    AuthUser boss = user("BOSS", null);

    assertThatThrownBy(() -> service.requireStoreManage(manager))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException forbidden = (BusinessException) error;
          assertThat(forbidden.getCode()).isEqualTo("FORBIDDEN");
          assertThat(forbidden.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });

    verify(auditRepository).writePermissionDenied(
        any(AuthUser.class),
        any(String.class),
        any(String.class),
        any(String.class),
        isNull(),
        any(String.class)
    );
    service.requireStoreManage(boss);
    service.requireStoreRead(manager);
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
  void expenseAttachmentDenialKeepsDocumentStoreAndMonthInTheAuditRecord() {
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    AccessControlService expenseAccess = new AccessControlService(
        authService,
        authRepository,
        auditRepository,
        authorizationService,
        mock(DataScopeService.class)
    );
    AuthUser supervisor = user("SUPERVISOR", "rg1");
    when(authorizationService.hasPermission(supervisor, PermissionCodes.ATTACHMENT_WRITE)).thenReturn(true);
    when(authorizationService.hasPermission(supervisor, PermissionCodes.EXPENSE_CREATE)).thenReturn(true);

    assertThatThrownBy(() -> expenseAccess.requireExpenseAttachmentWrite(
        supervisor, "exp-401", "rg2", "2026-07"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(auditRepository).writePermissionDenied(
        org.mockito.ArgumentMatchers.eq(supervisor),
        org.mockito.ArgumentMatchers.eq("录入报销数据"),
        org.mockito.ArgumentMatchers.eq("expense_claim"),
        org.mockito.ArgumentMatchers.eq("exp-401"),
        org.mockito.ArgumentMatchers.eq("rg2"),
        org.mockito.ArgumentMatchers.eq("2026-07"),
        org.mockito.ArgumentMatchers.contains("仅限财务、店长或老板")
    );
  }

  @Test
  void forgedExpenseAttachmentStoreIsRejectedWithDocumentContext() {
    AuthUser finance = user("FINANCE", "rg1");

    assertThatThrownBy(() -> service.requireExpenseDocumentStore(
        finance, "exp-forged", "rg2", "rg1", "2026-07", "上传报销凭证"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(auditRepository).writePermissionDenied(
        org.mockito.ArgumentMatchers.eq(finance),
        org.mockito.ArgumentMatchers.eq("上传报销凭证"),
        org.mockito.ArgumentMatchers.eq("expense_claim"),
        org.mockito.ArgumentMatchers.eq("exp-forged"),
        org.mockito.ArgumentMatchers.eq("rg2"),
        org.mockito.ArgumentMatchers.eq("2026-07"),
        org.mockito.ArgumentMatchers.contains("门店不一致")
    );
  }

  @Test
  void expenseReviewRemainsFinanceOrBossOnlyEvenWhenSupervisorHasAStalePersonalAllow() {
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    AccessControlService expenseAccess = new AccessControlService(
        authService,
        authRepository,
        auditRepository,
        authorizationService,
        mock(DataScopeService.class)
    );
    AuthUser supervisor = user("SUPERVISOR", "rg1");
    AuthUser finance = user("FINANCE", null);
    when(authorizationService.hasPermission(supervisor, PermissionCodes.EXPENSE_REVIEW)).thenReturn(true);
    when(authorizationService.hasPermission(finance, PermissionCodes.EXPENSE_REVIEW)).thenReturn(true);

    assertThatThrownBy(() -> expenseAccess.requireExpenseReview(supervisor))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException forbidden = (BusinessException) error;
          assertThat(forbidden.getCode()).isEqualTo("FORBIDDEN");
          assertThat(forbidden.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });
    expenseAccess.requireExpenseReview(finance);
    expenseAccess.requireExpenseReview(user("BOSS", null));

    verify(auditRepository).writePermissionDenied(
        org.mockito.ArgumentMatchers.eq(supervisor),
        org.mockito.ArgumentMatchers.eq("审核报销数据"),
        org.mockito.ArgumentMatchers.eq("expense_claim"),
        isNull(),
        isNull(),
        isNull(),
        org.mockito.ArgumentMatchers.contains("仅限财务或老板")
    );
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
  void legacyOperationsAliasesNormalizeToFormalSupervisor() {
    assertThat(AccessControlService.canonicalRole("SUPERVISOR")).isEqualTo("SUPERVISOR");
    assertThat(AccessControlService.canonicalRole("OPS")).isEqualTo("SUPERVISOR");
    assertThat(AccessControlService.hasAnyRole(user("SUPERVISOR", "rg1"), "OPERATIONS")).isTrue();
    assertThat(AccessControlService.hasAnyRole(user("SUPERVISOR", "rg1"), "SUPERVISOR")).isTrue();
  }

  @Test
  void dailyLossQueryAllowsStoreManagerWhileExcelExportRemainsBossSupervisorOnly() {
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    AccessControlService dailyLossAccess = new AccessControlService(
        authService,
        authRepository,
        auditRepository,
        authorizationService,
        mock(DataScopeService.class)
    );
    AuthUser boss = user("BOSS", null);
    AuthUser finance = user("FINANCE", null);
    AuthUser manager = user("STORE_MANAGER", "rg1");
    AuthUser supervisor = user("SUPERVISOR", null);
    AuthUser warehouse = user("WAREHOUSE", null);
    AuthUser employee = user("EMPLOYEE", "rg1");
    when(authorizationService.hasPermission(supervisor, PermissionCodes.DAILY_LOSS_READ)).thenReturn(true);
    when(authorizationService.hasPermission(supervisor, PermissionCodes.DAILY_LOSS_EXPORT)).thenReturn(true);
    when(authorizationService.hasPermission(manager, PermissionCodes.DAILY_LOSS_READ)).thenReturn(true);
    when(authorizationService.hasPermission(manager, PermissionCodes.DAILY_LOSS_EXPORT)).thenReturn(true);

    dailyLossAccess.requireDailyLossRead(boss);
    dailyLossAccess.requireDailyLossExport(boss);
    dailyLossAccess.requireDailyLossRead(supervisor);
    dailyLossAccess.requireDailyLossExport(supervisor);
    dailyLossAccess.requireDailyLossRead(manager);

    assertThatThrownBy(() -> dailyLossAccess.requireDailyLossExport(manager))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("FORBIDDEN");
          assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });

    for (AuthUser denied : List.of(finance, warehouse, employee)) {
      when(authorizationService.hasPermission(denied, PermissionCodes.DAILY_LOSS_READ)).thenReturn(true);
      when(authorizationService.hasPermission(denied, PermissionCodes.DAILY_LOSS_EXPORT)).thenReturn(true);

      assertThatThrownBy(() -> dailyLossAccess.requireDailyLossRead(denied))
          .isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo("FORBIDDEN");
            assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
          });
      assertThatThrownBy(() -> dailyLossAccess.requireDailyLossExport(denied))
          .isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo("FORBIDDEN");
            assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
      });
    }

    verify(auditRepository, times(7)).writePermissionDenied(
        any(AuthUser.class),
        any(String.class),
        org.mockito.ArgumentMatchers.eq("API"),
        any(String.class),
        isNull(),
        any(String.class)
    );
    verify(auditRepository).writePermissionDenied(
        org.mockito.ArgumentMatchers.eq(manager),
        org.mockito.ArgumentMatchers.eq("导出每日报损"),
        org.mockito.ArgumentMatchers.eq("API"),
        org.mockito.ArgumentMatchers.eq(PermissionCodes.DAILY_LOSS_EXPORT),
        isNull(),
        org.mockito.ArgumentMatchers.contains("仅限督导或老板")
    );
    verify(auditRepository).writePermissionDenied(
        org.mockito.ArgumentMatchers.eq(finance),
        org.mockito.ArgumentMatchers.eq("导出每日报损"),
        org.mockito.ArgumentMatchers.eq("API"),
        org.mockito.ArgumentMatchers.eq(PermissionCodes.DAILY_LOSS_EXPORT),
        isNull(),
        org.mockito.ArgumentMatchers.contains("仅限督导或老板")
    );
  }

  @Test
  void employeeCanUseOwnLearningAndAssistantButCannotEnterHighRiskModules() {
    AuthUser employee = user("EMPLOYEE", "rg1");

    service.requireExamRead(employee);
    service.requireEmployeeAssistantUse(employee);
    service.requireEmployeeWorkbench(employee);

    assertThatThrownBy(() -> service.requireFinanceRead(employee))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    assertThatThrownBy(() -> service.requireWarehouseRead(employee))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    assertThatThrownBy(() -> service.requirePlatformManage(employee))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    assertThatThrownBy(() -> service.requireUserManagementWrite(employee))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void nonEmployeeRoleCannotOpenEmployeeWorkbenchEvenWithExamPermission() {
    AuthUser manager = user("STORE_MANAGER", "rg1");

    assertThatThrownBy(() -> service.requireEmployeeWorkbench(manager))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
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
