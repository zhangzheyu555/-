package com.storeprofit.system.salary;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record SalaryGenerateRequest(
    String storeId,
    @NotBlank String month,
    List<String> employeeIds
) {
  public SalaryGenerateRequest(String storeId, String month) {
    this(storeId, month, null);
  }
}
