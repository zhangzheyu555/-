package com.storeprofit.system.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.todo.RoleTodoCompletionRequest;
import java.util.List;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FinanceControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final FinanceService financeService = mock(FinanceService.class);
  private final FinanceWorkbenchService financeWorkbenchService = mock(FinanceWorkbenchService.class);
  private final FinanceController controller = new FinanceController(authService, financeService, financeWorkbenchService);
  private final AuthUser boss = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);

  @Test
  void saveAndDeleteUseAuthenticatedUser() {
    ProfitEntryRequest request = new ProfitEntryRequest(
        "rg1",
        "2026-12",
        new BigDecimal("1234.56"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "api-test"
    );
    when(authService.requireUser("Bearer token")).thenReturn(boss);

    ApiResponse<Void> saved = controller.save("Bearer token", request);
    ApiResponse<Void> deleted = controller.delete("Bearer token", "rg1", "2026-12");

    assertThat(saved.success()).isTrue();
    assertThat(deleted.success()).isTrue();
    verify(authService, times(2)).requireUser("Bearer token");
    verify(financeService).save(boss, request);
    verify(financeService).delete(boss, "rg1", "2026-12");
  }

  @Test
  void workbenchAndTodoActionsUseAuthenticatedUser() {
    FinanceWorkbenchResponse workbench = new FinanceWorkbenchResponse(
        "财务",
        "MySQL结构化数据 / 后端标准接口",
        "2026-07-09T10:00:00+08:00",
        "2026-07",
        new FinanceWorkbenchFocusResponse(0, 0, 0, 0, "今日无事项"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of()
    );
    FinanceTodoActionResponse rejected = new FinanceTodoActionResponse("expense-1", "已驳回", "已处理", "票据缺失");
    RoleTodoCompletionRequest completion = new RoleTodoCompletionRequest("已核对", List.of());
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(financeWorkbenchService.workbench(boss, "2026-07", 1L, "rg1")).thenReturn(workbench);
    when(financeWorkbenchService.reject(boss, "expense-1", new FinanceTodoActionRequest("票据缺失"))).thenReturn(rejected);

    assertThat(controller.workbench("Bearer token", "2026-07", 1L, "rg1").data()).isSameAs(workbench);
    assertThat(controller.completeTodo("Bearer token", "profit-risk-rg1-2026-07", completion).success()).isTrue();
    assertThat(controller.rejectTodo("Bearer token", "expense-1", new FinanceTodoActionRequest("票据缺失")).data())
        .isSameAs(rejected);

    verify(financeWorkbenchService).workbench(boss, "2026-07", 1L, "rg1");
    verify(financeWorkbenchService).complete(boss, "profit-risk-rg1-2026-07", completion);
    verify(financeWorkbenchService).reject(boss, "expense-1", new FinanceTodoActionRequest("票据缺失"));
  }
}
