package com.storeprofit.system.employeeassistant;

/** Safe service availability response. It never includes upstream addresses, tokens, or details. */
public record EmployeeAssistantStatusResponse(
    boolean enabled,
    boolean configured,
    EmployeeAssistantState state,
    String message,
    boolean knowledgeAvailable,
    boolean canAsk
) {
}
