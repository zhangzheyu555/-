package com.storeprofit.system.expense;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ExpenseClaimRequest(
    @NotBlank(message = "报销门店不能为空") String storeId,
    @NotBlank(message = "报销月份不能为空") String month,
    @NotBlank(message = "报销日期不能为空") String expenseDate,
    // 列为 DECIMAL(14,2)，超精度在入参层拦成 400 而非落库 500。
    @NotNull(message = "报销金额不能为空")
    @DecimalMin(value = "0.01", message = "报销金额必须大于0")
    @Digits(integer = 12, fraction = 2, message = "报销金额最多12位整数和2位小数") BigDecimal amount,
    @NotBlank(message = "报销类别不能为空") String category,
    @NotBlank(message = "报销说明不能为空") String reason,
    String imageUrl
) {
}
