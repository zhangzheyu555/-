package com.storeprofit.system.salary;

import java.util.List;

public record SalaryGenerateReport(
    int generated,
    int skipped,
    int errors,
    List<SalarySkipDetail> skipDetails
) {
  public record SalarySkipDetail(
      String employeeId,
      String employeeName,
      String reason
  ) {}
}
