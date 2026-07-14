package com.storeprofit.system.inspection;

import java.util.List;

record InspectionStandardValidation(
    boolean valid,
    String validationError,
    List<InspectionStandardDiagnostic> diagnostics,
    List<InspectionStandardCategoryStats> categoryStats,
    InspectionStandardRiskStats riskStats
) {
  InspectionStandardValidation {
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    categoryStats = categoryStats == null ? List.of() : List.copyOf(categoryStats);
  }
}
