package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SalaryRecordRequestValidationTest {
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void birthdayBenefitCannotBeNegative() {
    SalaryRecordRequest request = new SalaryRecordRequest(
        "store-1", "2026-05", "employee-1", "测试员工", "营业员", null,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, new BigDecimal("-0.01"), BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
    );

    assertThat(validator.validate(request))
        .anySatisfy(violation -> {
          assertThat(violation.getPropertyPath().toString()).isEqualTo("birthdayBenefit");
          assertThat(violation.getMessage()).isEqualTo("员工福利（生日）不能小于0");
        });
  }

  @Test
  void seniorityCannotBeNegative() {
    SalaryRecordRequest request = requestWithAdjustments(new BigDecimal("-0.01"), BigDecimal.ZERO);

    assertThat(validator.validate(request))
        .anySatisfy(violation -> {
          assertThat(violation.getPropertyPath().toString()).isEqualTo("seniority");
          assertThat(violation.getMessage()).isEqualTo("工龄工资不能小于0");
        });
  }

  @Test
  void lateNightCannotBeNegative() {
    SalaryRecordRequest request = requestWithAdjustments(BigDecimal.ZERO, new BigDecimal("-0.01"));

    assertThat(validator.validate(request))
        .anySatisfy(violation -> {
          assertThat(violation.getPropertyPath().toString()).isEqualTo("lateNight");
          assertThat(violation.getMessage()).isEqualTo("深夜加班金额不能小于0");
        });
  }

  private SalaryRecordRequest requestWithAdjustments(BigDecimal seniority, BigDecimal lateNight) {
    return new SalaryRecordRequest(
        "store-1", "2026-05", "employee-1", "测试员工", "营业员", null,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        seniority, BigDecimal.ZERO, lateNight, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
    );
  }
}
