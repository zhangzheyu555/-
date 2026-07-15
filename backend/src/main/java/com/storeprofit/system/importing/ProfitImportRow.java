package com.storeprofit.system.importing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProfitImportRow(
    String rowId,
    String storeId,
    String storeName,
    String month,
    BigDecimal confidence,
    Map<String, BigDecimal> values,
    List<String> warnings,
    List<String> errors,
    boolean existing,
    String status,
    Map<String, BigDecimal> existingValues
) {
  public ProfitImportRow {
    values = values == null ? Map.of() : Map.copyOf(values);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    errors = errors == null ? List.of() : List.copyOf(errors);
    existingValues = existingValues == null ? Map.of() : Map.copyOf(existingValues);
  }

  /**
   * Keeps the long-standing import response shape source-compatible while the preview flow adds
   * an immutable before-value snapshot for its explicit single-record overwrite confirmation.
   */
  public ProfitImportRow(
      String rowId,
      String storeId,
      String storeName,
      String month,
      BigDecimal confidence,
      Map<String, BigDecimal> values,
      List<String> warnings,
      List<String> errors,
      boolean existing,
      String status
  ) {
    this(rowId, storeId, storeName, month, confidence, values, warnings, errors, existing, status, Map.of());
  }
}
