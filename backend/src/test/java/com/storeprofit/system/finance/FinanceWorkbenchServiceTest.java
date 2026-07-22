package com.storeprofit.system.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.expense.ExpenseClaimResponse;
import com.storeprofit.system.expense.ExpenseReviewRequest;
import com.storeprofit.system.expense.ExpenseService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.salary.SalaryRecordResponse;
import com.storeprofit.system.salary.SalaryService;
import com.storeprofit.system.todo.RoleTodoActionRepository;
import com.storeprofit.system.todo.RoleTodoAiSummaryResponse;
import com.storeprofit.system.todo.RoleTodoAudience;
import com.storeprofit.system.todo.RoleTodoCompletionRequest;
import com.storeprofit.system.todo.RoleTodoItemResponse;
import com.storeprofit.system.todo.RoleTodoQuery;
import com.storeprofit.system.todo.RoleTodoResponse;
import com.storeprofit.system.todo.RoleTodoService;
import com.storeprofit.system.todo.RoleTodoStatResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinanceWorkbenchServiceTest {
  private final FinanceService financeService = mock(FinanceService.class);
  private final ExpenseService expenseService = mock(ExpenseService.class);
  private final SalaryService salaryService = mock(SalaryService.class);
  private final RoleTodoService roleTodoService = mock(RoleTodoService.class);
  private final RoleTodoActionRepository actionRepository = mock(RoleTodoActionRepository.class);
  private final FinanceWorkbenchService service = new FinanceWorkbenchService(
      financeService,
      expenseService,
      salaryService,
      roleTodoService,
      actionRepository
  );
  private final AuthUser finance = new AuthUser(2L, 1L, "default", "finance", "", "财务", "FINANCE", null, true);
  private final AuthUser storeManager = new AuthUser(3L, 1L, "default", "rg1", "", "店长", "STORE_MANAGER", "rg1", true);

  @Test
  void workbenchAggregatesFinanceOnlyData() {
    when(roleTodoService.todos(eq(finance), eq(RoleTodoAudience.FINANCE), any(RoleTodoQuery.class))).thenReturn(new RoleTodoResponse(
        "财务",
        "MySQL结构化数据 / 后端标准接口",
        "2026-07-09T10:00:00+08:00",
        List.of(new RoleTodoStatResponse("PENDING", 1)),
        new RoleTodoAiSummaryResponse("RULE", "财务有 1 条待办", ""),
        List.of(todo("expense-exp1", "报销待审核：荆州之星店", "PENDING", "expense_claim", false))
    ));
    when(actionRepository.completedActions(1L)).thenReturn(Map.of());
    when(financeService.entries(finance, "2026-07", null, null)).thenReturn(List.of(profitRisk()));
    when(expenseService.claims(finance, "2026-07", null, null, null)).thenReturn(List.of(expense("待审核")));
    when(salaryService.records(finance, "2026-07", null, null)).thenReturn(List.of(salary()));

    FinanceWorkbenchResponse result = service.workbench(finance, "2026-07", null, null);

    assertThat(result.todayFocus().pendingExpenseCount()).isEqualTo(1);
    assertThat(result.todayFocus().profitRiskStoreCount()).isEqualTo(1);
    assertThat(result.todayFocus().salaryCheckCount()).isEqualTo(1);
    assertThat(result.needMyAction()).extracting(RoleTodoItemResponse::dataSource)
        .contains("expense_claim", "salary_record");
    assertThat(result.needMyAction()).noneMatch(item -> "warehouse_requisition".equals(item.dataSource()));
    assertThat(result.profitRisks()).hasSize(1);
  }

  @Test
  void storeManagerCannotOpenFinanceWorkbench() {
    assertThatThrownBy(() -> service.workbench(storeManager, "2026-07", null, null))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void permissionBasedWorkbenchOmitsPersonallyRevokedModules() {
    AccessControlService accessControl = mock(AccessControlService.class);
    FinanceWorkbenchService permissionService = new FinanceWorkbenchService(
        financeService, expenseService, salaryService, roleTodoService, actionRepository, accessControl);
    when(financeService.entries(finance, "2026-07", null, null)).thenReturn(List.of());
    when(accessControl.hasPermission(finance, PermissionCodes.TODO_READ)).thenReturn(false);
    when(accessControl.hasPermission(finance, PermissionCodes.EXPENSE_READ)).thenReturn(false);
    when(accessControl.hasPermission(finance, PermissionCodes.SALARY_READ)).thenReturn(false);

    FinanceWorkbenchResponse result = permissionService.workbench(finance, "2026-07", null, null);

    verify(accessControl).requireFinanceRead(finance);
    verify(roleTodoService, never()).todos(eq(finance), eq(RoleTodoAudience.FINANCE), any(RoleTodoQuery.class));
    verify(expenseService, never()).claims(eq(finance), eq("2026-07"), any(), any(), any());
    verify(salaryService, never()).records(eq(finance), eq("2026-07"), any(), any());
    assertThat(result.expenseReviews()).isEmpty();
    assertThat(result.salaryChecks()).isEmpty();
  }

  @Test
  void requestInfoUsesExpenseReviewFlow() {
    when(expenseService.reject(finance, "exp1", new ExpenseReviewRequest("要求补充资料：请补票据")))
        .thenReturn(expense("已驳回"));

    FinanceTodoActionResponse response = service.requestInfo(
        finance,
        "expense-exp1",
        new FinanceTodoActionRequest("请补票据")
    );

    assertThat(response.action()).isEqualTo("要求补充资料");
    assertThat(response.status()).isEqualTo("已处理");
    verify(expenseService).reject(finance, "exp1", new ExpenseReviewRequest("要求补充资料：请补票据"));
  }

  @Test
  void requestInfoForProfitRiskWritesTodoActionWithoutClosingTodo() {
    FinanceTodoActionResponse response = service.requestInfo(
        finance,
        "profit-risk-rg1-2026-07",
        new FinanceTodoActionRequest("请门店说明成本异常原因")
    );

    assertThat(response.action()).isEqualTo("要求补充资料");
    assertThat(response.status()).isEqualTo("待补资料");
    verify(actionRepository).saveAction(any(RoleTodoActionRepository.RoleTodoActionRecord.class));
    verify(actionRepository).saveOperationLog(any(RoleTodoActionRepository.RoleTodoOperationLogRecord.class));
  }

  @Test
  void completingGeneratedSalaryCheckWritesTodoAction() {
    service.complete(finance, "salary-check-sal1", new RoleTodoCompletionRequest("工资已核对", List.of()));

    verify(actionRepository).saveAction(any(RoleTodoActionRepository.RoleTodoActionRecord.class));
    verify(actionRepository).saveOperationLog(any(RoleTodoActionRepository.RoleTodoOperationLogRecord.class));
  }

  private RoleTodoItemResponse todo(String id, String title, String status, String dataSource, boolean escalated) {
    return new RoleTodoItemResponse(
        id,
        title,
        "请财务处理",
        status,
        80,
        "苹果奶茶",
        "rg1",
        "荆州之星店",
        "2026-07",
        "财务",
        "2026-07-09T18:00:00+08:00",
        "报销",
        id,
        "待财务审核",
        escalated,
        dataSource,
        "2026-07-09T10:00:00+08:00",
        "2026-07-09T09:00:00+08:00",
        null
    );
  }

  private ExpenseClaimResponse expense(String status) {
    return new ExpenseClaimResponse(
        "exp1",
        "rg1",
        "rg1",
        "荆州之星店",
        1L,
        "苹果奶茶",
        "2026-07",
        new BigDecimal("128"),
        "交通费",
        "打车",
        status,
        "https://example.com/a.jpg",
        3L,
        null,
        LocalDateTime.of(2026, 7, 9, 10, 0)
    );
  }

  private ProfitEntryResponse profitRisk() {
    return new ProfitEntryResponse(
        1L,
        "rg1",
        "rg1",
        "荆州之星店",
        1L,
        "苹果奶茶",
        "荆州",
        "店长",
        "2026-07",
        new BigDecimal("1000"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        new BigDecimal("1000"),
        new BigDecimal("400"),
        new BigDecimal("100"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        new BigDecimal("500"),
        new BigDecimal("0.5"),
        new BigDecimal("500"),
        new BigDecimal("0.5"),
        new BigDecimal("100"),
        new BigDecimal("450"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        new BigDecimal("550"),
        new BigDecimal("-50"),
        new BigDecimal("-0.05"),
        "亏损",
        "测试"
    );
  }

  private SalaryRecordResponse salary() {
    return new SalaryRecordResponse(
        "sal1",
        "rg1",
        "rg1",
        "荆州之星店",
        1L,
        "苹果奶茶",
        "2026-07",
        "emp-1",
        "张三",
        "店长",
        "全职",
        "满勤",
        new BigDecimal("6800"),
        new BigDecimal("6600"),
        new BigDecimal("176"),
        BigDecimal.ZERO,
        new BigDecimal("176"),
        BigDecimal.ZERO,
        "",
        new BigDecimal("3000"),
        new BigDecimal("800"),
        new BigDecimal("1000"),
        new BigDecimal("300"),
        new BigDecimal("200"),
        new BigDecimal("1500"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "DRAFT",
        null,
        null,
        null,
        null,
        null,
        1
    );
  }
}
