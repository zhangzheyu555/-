package com.storeprofit.system.expense;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ExpenseClaimRequest(
    @NotBlank String storeId,
    @NotBlank String month,
    // 列为 DECIMAL(14,2)，超精度在入参层拦成 400 而非落库 500。
    @NotNull @Digits(integer = 12, fraction = 2) BigDecimal amount,
    String category,
    String reason,
    String imageUrl
) {
}
