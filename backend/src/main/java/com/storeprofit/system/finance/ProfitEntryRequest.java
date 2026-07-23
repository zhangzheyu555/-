package com.storeprofit.system.finance;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProfitEntryRequest(
    @NotBlank(message = "门店不能为空") String storeId,
    @NotBlank(message = "月份不能为空") String month,
    // 列类型为 DECIMAL(14,2)：整数位超过 12 位会溢出直接落 500，这里在入参层拦成 400。
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal sales,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal refund,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal discount,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal material,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal packaging,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal loss,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal costOther,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal rent,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal labor,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal utility,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal property,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal commission,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal meituan,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal eleme,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal douyin,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal amap,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal promo,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal repair,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal equip,
    @Digits(integer = 12, fraction = 2, message = "金额格式不正确，最多12位整数和2位小数") @DecimalMin(value = "0", message = "金额不能小于 0") BigDecimal expOther,
    String note,
    @NotNull(message = "品牌不能为空") Long brandId
) {
  public ProfitEntryRequest(
      String storeId, String month, BigDecimal sales, BigDecimal refund, BigDecimal discount,
      BigDecimal material, BigDecimal packaging, BigDecimal loss, BigDecimal costOther,
      BigDecimal rent, BigDecimal labor, BigDecimal utility, BigDecimal property,
      BigDecimal commission, BigDecimal promo, BigDecimal repair, BigDecimal equip,
      BigDecimal expOther, String note, Long brandId
  ) {
    this(storeId, month, sales, refund, discount, material, packaging, loss, costOther,
        rent, labor, utility, property, commission, null, null, null, null,
        promo, repair, equip, expOther, note, brandId);
  }

  public ProfitEntryRequest(
      String storeId,
      String month,
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
    this(storeId, month, sales, refund, discount, material, packaging, loss, costOther,
        rent, labor, utility, property, commission, null, null, null, null,
        promo, repair, equip, expOther, note, null);
  }
}
