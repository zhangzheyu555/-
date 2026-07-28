package com.storeprofit.system.salary;

import java.util.List;

public record SalaryGenerateReport(
    int generated,
    int skipped,
    int errors,
    List<SalarySkipDetail> skipDetails,
    List<SalaryCandidate> candidates
) {
  public SalaryGenerateReport(
      int generated,
      int skipped,
      int errors,
      List<SalarySkipDetail> skipDetails
  ) {
    this(generated, skipped, errors, skipDetails, List.of());
  }

  public record SalarySkipDetail(
      String employeeId,
      String employeeName,
      String reason,
      String storeId,
      String storeName
  ) {
    public SalarySkipDetail(
        String employeeId,
        String employeeName,
        String reason
    ) {
      this(employeeId, employeeName, reason, null, null);
    }
  }

  public record SalaryCandidate(
      String employeeId,
      String employeeName,
      String position,
      String storeId,
      String storeName
  ) {
    public SalaryCandidate(
        String employeeId,
        String employeeName,
        String position
    ) {
      this(employeeId, employeeName, position, null, null);
    }
  }
}
