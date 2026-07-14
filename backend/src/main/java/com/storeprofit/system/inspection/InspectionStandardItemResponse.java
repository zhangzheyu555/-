package com.storeprofit.system.inspection;

import java.math.BigDecimal;

public record InspectionStandardItemResponse(
    long id,
    String dimension,
    String code,
    String title,
    String description,
    BigDecimal suggestedScore,
    boolean redLine,
    boolean enabled,
    int sortOrder,
    String checkMethod,
    String riskLevel
) {
  public InspectionStandardItemResponse(
      long id,
      String dimension,
      String code,
      String title,
      String description,
      BigDecimal suggestedScore,
      boolean redLine,
      boolean enabled,
      int sortOrder
  ) {
    this(id, dimension, code, title, description, suggestedScore, redLine, enabled, sortOrder,
        null, redLine ? "RED" : "NORMAL");
  }
}
