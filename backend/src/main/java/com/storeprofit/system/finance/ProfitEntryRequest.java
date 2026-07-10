package com.storeprofit.system.finance;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record ProfitEntryRequest(
    @NotBlank String storeId,
    @NotBlank String month,
    BigDecimal sales,
    BigDecimal refund,
    BigDecimal discount,
    BigDecimal material,
    BigDecimal packaging,
    BigDecimal loss,
    BigDecimal costOther,
    BigDecimal rent,
    BigDecimal labor,
    BigDecimal utility,
    BigDecimal property,
    BigDecimal commission,
    BigDecimal promo,
    BigDecimal repair,
    BigDecimal equip,
    BigDecimal expOther,
    String note
) {
}
