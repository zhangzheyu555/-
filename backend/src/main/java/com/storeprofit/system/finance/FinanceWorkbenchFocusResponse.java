package com.storeprofit.system.finance;

public record FinanceWorkbenchFocusResponse(
    int pendingExpenseCount,
    int profitRiskStoreCount,
    int salaryCheckCount,
    int escalatedToBossCount,
    String summary
) {
}
