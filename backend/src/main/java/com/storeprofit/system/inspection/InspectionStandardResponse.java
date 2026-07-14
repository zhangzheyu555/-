package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InspectionStandardResponse(
    String title,
    BigDecimal fullScore,
    String version,
    LocalDate effectiveDate,
    List<InspectionStandardItemResponse> items,
    Long id,
    BigDecimal passScore,
    boolean valid,
    boolean saveAllowed,
    String validationError,
    List<InspectionStandardDiagnostic> diagnostics,
    List<InspectionStandardCategoryStats> categoryStats,
    InspectionStandardRiskStats riskStats
) {
  public InspectionStandardResponse {
    items = items == null ? List.of() : List.copyOf(items);
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    categoryStats = categoryStats == null ? List.of() : List.copyOf(categoryStats);
    riskStats = riskStats == null ? new InspectionStandardRiskStats(0, 0) : riskStats;
  }

  public InspectionStandardResponse(
      String title,
      BigDecimal fullScore,
      String version,
      LocalDate effectiveDate,
      List<InspectionStandardItemResponse> items
  ) {
    this(title, fullScore, version, effectiveDate, items, null, new BigDecimal("180.00"),
        true, true, null, List.of(), List.of(), new InspectionStandardRiskStats(0, 0));
  }

  public static InspectionStandardResponse empty() {
    return new InspectionStandardResponse(
        "全门店通用标准", BigDecimal.valueOf(200), "", null, List.of(), null, BigDecimal.valueOf(180),
        false, false, "当前没有启用的巡检标准",
        List.of(new InspectionStandardDiagnostic(
            null, null, null, null, null, null, "当前没有启用的巡检标准")),
        List.of(), new InspectionStandardRiskStats(0, 0));
  }
}
