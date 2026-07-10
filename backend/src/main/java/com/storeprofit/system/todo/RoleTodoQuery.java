package com.storeprofit.system.todo;

import java.util.Locale;

public record RoleTodoQuery(
    boolean includeDone,
    String status,
    int limit,
    Long brandId,
    String storeId
) {
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  public RoleTodoQuery {
    status = blankToNull(status);
    if (status != null) {
      status = status.toUpperCase(Locale.ROOT);
    }
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }
    limit = Math.min(limit, MAX_LIMIT);
    storeId = blankToNull(storeId);
  }

  public static RoleTodoQuery defaults() {
    return new RoleTodoQuery(false, null, DEFAULT_LIMIT, null, null);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
