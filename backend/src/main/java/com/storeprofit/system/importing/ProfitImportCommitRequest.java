package com.storeprofit.system.importing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProfitImportCommitRequest(
    List<Row> rows
) {
  public record Row(
      String rowId,
      String storeId,
      String month,
      boolean overwrite,
      Map<String, BigDecimal> values,
      String note
  ) {
  }
}
