package com.storeprofit.system.expense;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ExpenseClaimRequest(
    @NotBlank String storeId,
    @NotBlank String month,
    @NotNull BigDecimal amount,
    String category,
    String reason,
    String imageUrl
) {
}
