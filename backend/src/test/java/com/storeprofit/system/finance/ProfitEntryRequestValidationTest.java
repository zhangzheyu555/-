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
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactlyInAnyOrder("storeId", "brandId");
  }

  @Test
  void selectedStoreAndBrandPassRequestValidation() {
    assertThat(validator.validate(request("rg1", 1L))).isEmpty();
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
