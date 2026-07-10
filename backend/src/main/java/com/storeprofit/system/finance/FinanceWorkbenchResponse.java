package com.storeprofit.system.finance;

import com.storeprofit.system.expense.ExpenseClaimResponse;
import com.storeprofit.system.todo.RoleTodoItemResponse;
import java.util.List;

public record FinanceWorkbenchResponse(
    String roleName,
    String dataSource,
    String updatedAt,
    String month,
    FinanceWorkbenchFocusResponse todayFocus,
    List<RoleTodoItemResponse> needMyAction,
    List<ProfitEntryResponse> profitRisks,
    List<ExpenseClaimResponse> expenseReviews,
    List<FinanceSalaryCheckResponse> salaryChecks,
    List<FinanceDataCheckResponse> dataChecks,
    List<RoleTodoItemResponse> doneReview,
    List<String> assistantPrompts
) {
}
