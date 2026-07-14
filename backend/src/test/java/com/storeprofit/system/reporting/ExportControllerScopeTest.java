package com.storeprofit.system.reporting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.expense.ExpenseService;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.salary.SalaryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ExportControllerScopeTest {
  private final AuthService authService = mock(AuthService.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final FinanceService financeService = mock(FinanceService.class);
  private final ExpenseService expenseService = mock(ExpenseService.class);
  private final SalaryService salaryService = mock(SalaryService.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final BusinessScopeResolver businessScopeResolver = mock(BusinessScopeResolver.class);
  private final ExportController controller = new ExportController(
      authService, accessControl, financeService, expenseService, salaryService,
      auditRepository, businessScopeResolver);
  private final AuthUser manager = new AuthUser(
      7L, 1L, "测试企业", "rg1", "hash", "店长", "STORE_MANAGER", "rg1", true, 2L);

  @Test
  void profitAndExpenseExportsForwardStoreIdInsteadOfIgnoringIt() {
    when(authService.requireUser("Bearer token")).thenReturn(manager);
    when(financeService.entries(manager, "2026-07", 9L, "other-store"))
        .thenReturn(List.of());
    when(expenseService.claims(manager, "2026-07", 9L, "other-store", null))
        .thenReturn(List.of());

    controller.profitRankingCsv("Bearer token", "2026-07", 9L, "other-store");
    controller.expensesCsv("Bearer token", "2026-07", 9L, "other-store");

    verify(financeService).entries(manager, "2026-07", 9L, "other-store");
    verify(expenseService).claims(manager, "2026-07", 9L, "other-store", null);
  }

  @Test
  void salaryExportRejectsForgedStoreBeforeQuery() {
    when(authService.requireUser("Bearer token")).thenReturn(manager);
    when(businessScopeResolver.resolve(
        manager,
        com.storeprofit.system.platform.authorization.DataScopeDomains.SALARY,
        "other-store",
        10L,
        "导出工资记录"
    )).thenThrow(new BusinessException(
        "FORBIDDEN", "当前账号只能访问绑定门店的数据", HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> controller.salariesCsv(
        "Bearer token", "2026-07", 10L, "other-store"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("FORBIDDEN");
          org.assertj.core.api.Assertions.assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });
  }
}
