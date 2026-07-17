package com.storeprofit.system.employee;

import java.math.BigDecimal;
import java.util.List;

public record EmployeeProfileResponse(
    Profile profile,
    Store store,
    Archive archive,
    Salary salary,
    List<ChecklistItem> checklist
) {
  public record Profile(
      long userId,
      String username,
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

  public record Archive(
      boolean linked,
      String employeeId,
      String name,
      String position,
      String employmentType,
      String status,
      String hireDate,
      BigDecimal baseSalary,
      String message
  ) {
  }

  public record Salary(
      boolean available,
      String recordId,
      String month,
      String status,
      String statusLabel,
      String employeeId,
      String employeeName,
      String position,
      String attendance,
      BigDecimal base,
      BigDecimal gross,
      BigDecimal netPay,
      BigDecimal commission,
      BigDecimal overtime,
      BigDecimal performance,
      BigDecimal deductUniform,
      BigDecimal returnUniform,
      BigDecimal vacationLeft,
      String vacationNote,
      String reviewedAt,
      String paidAt,
      String message
  ) {
  }

  public record ChecklistItem(
      String key,
      String title,
      String description,
      String state,
      String severity
  ) {
  }
}
