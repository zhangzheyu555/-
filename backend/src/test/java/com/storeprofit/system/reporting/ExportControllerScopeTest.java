package com.storeprofit.system.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.expense.ExpenseService;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.salary.SalaryRecordResponse;
import com.storeprofit.system.salary.SalaryService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
  void profitAndExpenseExportsUseResolvedScopeAndAuditTheActualStore() {
    AuthUser finance = new AuthUser(
        6L, 1L, "测试企业", "finance", "hash", "财务", "FINANCE", null, true, 1L);
    BusinessScope scope = new BusinessScope("rg1", "门店 A", 9L, "测试品牌", null);
    when(authService.requireUser("Bearer token")).thenReturn(finance);
    when(businessScopeResolver.resolve(
        finance, DataScopeDomains.FINANCE, "rg1", 9L, "导出利润排行", "2026-07"))
        .thenReturn(scope);
    when(businessScopeResolver.resolve(
        finance, DataScopeDomains.FINANCE, "rg1", 9L, "导出报销记录", "2026-07"))
        .thenReturn(scope);
    when(financeService.entries(finance, "2026-07", 9L, "rg1"))
        .thenReturn(List.of());
    when(expenseService.claims(finance, "2026-07", 9L, "rg1", null))
        .thenReturn(List.of());

    controller.profitRankingCsv("Bearer token", "2026-07", 9L, "rg1");
    controller.expensesCsv("Bearer token", "2026-07", 9L, "rg1");

    verify(accessControl, times(2)).requireDataExport(finance, "rg1", "2026-07");
    verify(financeService).entries(finance, "2026-07", 9L, "rg1");
    verify(expenseService).claims(finance, "2026-07", 9L, "rg1", null);
    ArgumentCaptor<AuditLogRequest> logs = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository, times(2)).writeLog(eq(finance), logs.capture());
    assertThat(logs.getAllValues()).allSatisfy(log -> {
      assertThat(log.storeId()).isEqualTo("rg1");
      assertThat(log.month()).isEqualTo("2026-07");
      assertThat(log.reason()).contains("门店=rg1", "品牌=9");
    });
  }

  @Test
  void salaryExportRejectsForgedStoreBeforeQuery() {
    when(authService.requireUser("Bearer token")).thenReturn(manager);
    when(businessScopeResolver.resolve(
        manager,
        DataScopeDomains.SALARY,
        "other-store",
        10L,
        "导出工资记录",
        "2026-07"
    )).thenThrow(new BusinessException(
        "FORBIDDEN", "当前账号只能访问绑定门店的数据", HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> controller.salariesCsv(
        "Bearer token", "2026-07", 10L, "other-store"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("FORBIDDEN");
          org.assertj.core.api.Assertions.assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });
  }

  @Test
  void invalidMonthWritesARejectedExportAuditWithoutQueryingBusinessData() {
    when(authService.requireUser("Bearer token")).thenReturn(manager);

    assertThatThrownBy(() -> controller.profitRankingCsv(
        "Bearer token", "2026-99", 9L, "rg1"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("EXPORT_MONTH_INVALID");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });

    ArgumentCaptor<AuditLogRequest> audit = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(eq(manager), audit.capture());
    assertThat(audit.getValue())
        .extracting(AuditLogRequest::action, AuditLogRequest::targetId,
            AuditLogRequest::storeId, AuditLogRequest::month, AuditLogRequest::reason)
        .containsExactly("导出利润排行", "profit-ranking", "rg1", null, "导出失败：月份格式不正确");
    verify(financeService, org.mockito.Mockito.never())
        .entries(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void salaryCsvUsesNetPayAndNeutralizesSpreadsheetFormulas() {
    SalaryRecordResponse record = new SalaryRecordResponse(
        "salary-1", "rg1", "RG001", "门店 A", 9L, "测试品牌", "2026-07",
        "EMP001", "=1+1", "店员", "正常", "26天",
        money("1200.00"), money("999.50"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, null, money("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO,
        "APPROVED", null, null, null, null, null, 0);

    String csv = ExportController.toSalaryCsv(List.of(record));

    assertThat(csv).contains("\"'=1+1\"");
    assertThat(csv).contains(",999.50,\"已完成\",");
    assertThat(csv).doesNotContain(",1200.00,\"已完成\",");
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }
}
