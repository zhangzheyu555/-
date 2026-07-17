package com.storeprofit.system.employee;

import java.util.List;

public record EmployeeWorkbenchResponse(
    Profile profile,
    Store store,
    List<WorkItem> workItems,
    WorkSummary workSummary,
    AssistantEntry assistant
) {
  public record Profile(
      long userId,
      String displayName,
      String role
  ) {
  }

  public record Store(
      String storeId,
      String storeName,
      String brandName
  ) {
  }

  public record WorkItem(
      String id,
      String type,
      String title,
      String description,
      String status,
      String priority,
      String actionText,
      String route
  ) {
  }

  public record WorkSummary(
      int total,
      int pending,
      int overdue,
      int completed,
      int retakePending
  ) {
  }

  public record AssistantEntry(
      boolean enabled,
      String state,
      String message,
      String route
  ) {
  }
}
