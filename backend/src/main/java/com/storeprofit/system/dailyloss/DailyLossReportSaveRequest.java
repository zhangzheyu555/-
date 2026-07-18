package com.storeprofit.system.dailyloss;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record DailyLossReportSaveRequest(
    @NotBlank String storeId,
    @NotNull LocalDate lossDate,
    List<DailyLossReportLineRequest> details
) {
}
