package com.storeprofit.system.finance;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record ProfitEntryRequest(
    @NotBlank String storeId,
    @NotBlank String month,
    // 列类型为 DECIMAL(14,2)：整数位超过 12 位会溢出直接落 500，这里在入参层拦成 400。
    @Digits(integer = 12, fraction = 2) BigDecimal sales,
    @Digits(integer = 12, fraction = 2) BigDecimal refund,
    @Digits(integer = 12, fraction = 2) BigDecimal discount,
    @Digits(integer = 12, fraction = 2) BigDecimal material,
    @Digits(integer = 12, fraction = 2) BigDecimal packaging,
    @Digits(integer = 12, fraction = 2) BigDecimal loss,
    @Digits(integer = 12, fraction = 2) BigDecimal costOther,
    @Digits(integer = 12, fraction = 2) BigDecimal rent,
    @Digits(integer = 12, fraction = 2) BigDecimal labor,
    @Digits(integer = 12, fraction = 2) BigDecimal utility,
    @Digits(integer = 12, fraction = 2) BigDecimal property,
    @Digits(integer = 12, fraction = 2) BigDecimal commission,
    @Digits(integer = 12, fraction = 2) BigDecimal promo,
    @Digits(integer = 12, fraction = 2) BigDecimal repair,
    @Digits(integer = 12, fraction = 2) BigDecimal equip,
    @Digits(integer = 12, fraction = 2) BigDecimal expOther,
    String note
) {
}
