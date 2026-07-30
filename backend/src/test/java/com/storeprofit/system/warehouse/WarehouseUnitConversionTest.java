package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WarehouseUnitConversionTest {

  @Test
  void convertsPurchaseBoxesToStockPiecesAndKeepsTheTotalAmount() {
    BigDecimal factor = WarehouseUnitConversion.factor("箱", "件", "1箱=12件")
        .orElseThrow();

    BigDecimal stockQuantity = WarehouseUnitConversion.stockQuantity(
        new BigDecimal("20"), factor);
    BigDecimal stockUnitCost = WarehouseUnitConversion.stockUnitCost(
        new BigDecimal("12"), factor);

    assertThat(factor).isEqualByComparingTo("12");
    assertThat(stockQuantity).isEqualByComparingTo("240");
    assertThat(stockUnitCost).isEqualByComparingTo("1");
    assertThat(stockQuantity.multiply(stockUnitCost)).isEqualByComparingTo("240");
  }

  @Test
  void supportsPerPurchaseUnitNotationAndFullWidthEquals() {
    assertThat(WarehouseUnitConversion.factor("箱", "件", "12件/箱"))
        .contains(new BigDecimal("12.00000000"));
    assertThat(WarehouseUnitConversion.factor("箱", "件", "1箱＝12件"))
        .contains(new BigDecimal("12.00000000"));
  }

  @Test
  void rejectsMissingConversionWhenUnitsDiffer() {
    assertThat(WarehouseUnitConversion.factor("箱", "件", "")).isEmpty();
  }
}
