package com.storeprofit.system.finance;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProfitEntryRequestValidationTest {
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void manualEntryRequiresOneStoreAndOneRealBrand() {
    ProfitEntryRequest missingScope = request("", null);

    assertThat(validator.validate(missingScope))
        .anySatisfy(violation -> {
          assertThat(violation.getPropertyPath().toString()).isEqualTo("storeId");
          assertThat(violation.getMessage()).isEqualTo("门店不能为空");
        })
        .anySatisfy(violation -> {
          assertThat(violation.getPropertyPath().toString()).isEqualTo("brandId");
          assertThat(violation.getMessage()).isEqualTo("品牌不能为空");
        });
  }

  @Test
  void selectedStoreAndBrandPassRequestValidation() {
    assertThat(validator.validate(request("rg1", 1L))).isEmpty();
  }

  @Test
  void rejectsNegativeAmountsBeforeTheyReachThePersistenceLayer() {
    ProfitEntryRequest negativeMaterial = new ProfitEntryRequest(
        "rg1", "2026-07", new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
        new BigDecimal("-0.01"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, "validation", 1L);

    assertThat(validator.validate(negativeMaterial))
        .anySatisfy(violation -> {
          assertThat(violation.getPropertyPath().toString()).isEqualTo("material");
          assertThat(violation.getMessage()).isEqualTo("金额不能小于 0");
        });
  }

  @Test
  void rejectsOverPrecisionAmountsWithAChineseMessage() {
    ProfitEntryRequest overflowSales = new ProfitEntryRequest(
        "rg1", "2026-07", new BigDecimal("1000000000000"), BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, "validation", 1L);

    assertThat(validator.validate(overflowSales))
        .anySatisfy(violation -> {
          assertThat(violation.getPropertyPath().toString()).isEqualTo("sales");
          assertThat(violation.getMessage()).isEqualTo("金额格式不正确，最多12位整数和2位小数");
        });
  }

  private ProfitEntryRequest request(String storeId, Long brandId) {
    return new ProfitEntryRequest(
        storeId, "2026-07", new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, "validation", brandId
    );
  }
}
