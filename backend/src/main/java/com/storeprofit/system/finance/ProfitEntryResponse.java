package com.storeprofit.system.finance;

import java.math.BigDecimal;

public record ProfitEntryResponse(
    Long id,
    String storeId,
    String storeCode,
    String storeName,
    Long brandId,
    String brandName,
    String area,
    String manager,
    String month,
    BigDecimal sales,
    BigDecimal refund,
    BigDecimal discount,
    BigDecimal income,
    BigDecimal material,
    BigDecimal packaging,
    BigDecimal loss,
    BigDecimal costOther,
    BigDecimal costSum,
    BigDecimal costRatio,
    BigDecimal gross,
    BigDecimal grossMargin,
    BigDecimal rent,
    BigDecimal labor,
    BigDecimal utility,
    BigDecimal property,
    BigDecimal commission,
    BigDecimal promo,
    BigDecimal repair,
    BigDecimal equip,
    BigDecimal expOther,
    BigDecimal expenseSum,
    BigDecimal net,
    BigDecimal margin,
    String risk,
    String note
) {
}
