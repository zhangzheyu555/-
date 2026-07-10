package com.storeprofit.system.finance;

public record FinanceTodoActionResponse(
    String todoId,
    String action,
    String status,
    String message
) {
}
