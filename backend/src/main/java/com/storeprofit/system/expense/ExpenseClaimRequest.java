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
  /**
   * Source-compatible constructor for callers created before expense dates were captured.
   * Legacy monthly claims are anchored to the first day of their selected month; normal request
   * validation still verifies that the resulting date belongs to that month.
   */
  public ExpenseClaimRequest(
      String storeId,
      String month,
      BigDecimal amount,
      String category,
      String reason,
      String imageUrl
  ) {
    this(storeId, month, defaultExpenseDate(month), amount, category, reason, imageUrl);
  }

  private static String defaultExpenseDate(String month) {
    if (month == null || month.isBlank()) {
      return null;
    }
    return month.trim() + "-01";
  }
}
