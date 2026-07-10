package com.storeprofit.system.todo;

import java.util.Map;

public record RoleTodoActionResponse(
    String target,
    String label,
    Map<String, Object> params
) {
}
