package com.storeprofit.system.importing;

import java.util.List;

public record ProfitImportJobConfirmRequest(
    boolean confirmMonthConflict,
    List<RowDecision> rows
) {
  public record RowDecision(String rowId, boolean overwrite) {
  }
}
