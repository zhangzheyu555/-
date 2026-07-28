package com.storeprofit.system.dailyloss;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record DailyLossReportLineRequest(
    @NotNull Long itemConfigId,
    @NotNull BigDecimal lossQuantity,
    String lossReason,
    String peelState
) {
  public DailyLossReportLineRequest(Long itemConfigId, BigDecimal lossQuantity, String lossReason) {
    this(itemConfigId, lossQuantity, lossReason, null);
  }
}
