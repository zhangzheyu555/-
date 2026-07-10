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
    String status
) {
}
