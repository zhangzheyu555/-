package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InspectionStandardResponse(
    String title,
    BigDecimal fullScore,
    String version,
    LocalDate effectiveDate,
    List<InspectionStandardItemResponse> items
) {
  public static InspectionStandardResponse empty() {
    return new InspectionStandardResponse("全门店通用标准", BigDecimal.valueOf(100), "", null, List.of());
  }
}
