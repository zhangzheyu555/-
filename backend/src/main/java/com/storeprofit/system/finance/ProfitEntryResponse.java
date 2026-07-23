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
    BigDecimal meituan,
    BigDecimal eleme,
    BigDecimal douyin,
    BigDecimal amap,
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
  public ProfitEntryResponse(
      Long id, String storeId, String storeCode, String storeName, Long brandId,
      String brandName, String area, String manager, String month,
      BigDecimal sales, BigDecimal refund, BigDecimal discount, BigDecimal income,
      BigDecimal material, BigDecimal packaging, BigDecimal loss, BigDecimal costOther,
      BigDecimal costSum, BigDecimal costRatio, BigDecimal gross, BigDecimal grossMargin,
      BigDecimal rent, BigDecimal labor, BigDecimal utility, BigDecimal property,
      BigDecimal commission, BigDecimal promo, BigDecimal repair, BigDecimal equip,
      BigDecimal expOther, BigDecimal expenseSum, BigDecimal net, BigDecimal margin,
      String risk, String note
  ) {
    this(id, storeId, storeCode, storeName, brandId, brandName, area, manager, month,
        sales, refund, discount, income, material, packaging, loss, costOther,
        costSum, costRatio, gross, grossMargin, rent, labor, utility, property,
        commission, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        promo, repair, equip, expOther, expenseSum, net, margin, risk, note);
  }
}
