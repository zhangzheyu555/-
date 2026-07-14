package com.storeprofit.system.salary;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonProperty;

public record SalaryEmployeePageResponse(
    List<SalaryRecordResponse> content,
    int total,
    int page,
    int size,
    int totalPages,
    SalarySummaryResponse summary,
    Map<String, Integer> statusCounts,
    BigDecimal workHoursTotal,
    BigDecimal vacationBalanceTotal
) {
  @JsonProperty("totalElements")
  public int totalElements() {
    return total;
  }

  @JsonProperty("rows")
  public List<SalaryRecordResponse> rows() {
    return content;
  }
}
