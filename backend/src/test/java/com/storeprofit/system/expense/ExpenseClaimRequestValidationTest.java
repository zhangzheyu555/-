package com.storeprofit.system.expense;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExpenseClaimRequestValidationTest {
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void requiresAnExplicitStoreForEveryExpenseCreateOrUpdate() {
    ExpenseClaimRequest request = new ExpenseClaimRequest(
        "", "2026-08", "2026-08-15", new BigDecimal("1.00"), "交通", "测试", null);

    assertThat(validator.validate(request))
        .anySatisfy(violation -> {
          assertThat(violation.getPropertyPath().toString()).isEqualTo("storeId");
          assertThat(violation.getMessage()).isEqualTo("报销门店不能为空");
        });
  }
}
