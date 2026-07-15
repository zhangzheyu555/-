package com.storeprofit.system.importing;

import java.util.List;

/** Confirmation decisions can only refer to persisted preview-row ids. */
public record ProfitImportJobConfirmRequest(List<RowDecision> rows) {
  public record RowDecision(String rowId, boolean overwrite) {
  }
}
