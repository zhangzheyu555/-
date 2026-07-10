package com.storeprofit.system.todo;

public record RoleTodoEscalationRequest(
    String reason,
    String severity
) {
}
