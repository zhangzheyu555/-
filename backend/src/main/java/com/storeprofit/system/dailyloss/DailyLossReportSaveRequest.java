package com.storeprofit.system.dailyloss;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DailyLossReportSaveRequest(
    @NotBlank String storeId,
    @NotNull LocalDate lossDate,
    List<DailyLossReportLineRequest> details,
    BigDecimal supplierCompensationAmount
) {
  public DailyLossReportSaveRequest(String storeId, LocalDate lossDate, List<DailyLossReportLineRequest> details) {
    this(storeId, lossDate, details, BigDecimal.ZERO);
  }
}
