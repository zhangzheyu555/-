package com.storeprofit.system.dailyloss;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Client input deliberately excludes unit price and amount; the server snapshots both. */
public record DailyLossCreateRequest(
    @NotBlank String storeId,
    @NotNull LocalDate lossDate,
    @NotNull Long itemId,
    @NotNull BigDecimal lossQuantity,
    @NotBlank String lossReason
) {
}
