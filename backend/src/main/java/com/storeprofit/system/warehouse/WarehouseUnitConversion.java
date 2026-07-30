package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the material master conversion between the purchasing unit and the stock unit.
 *
 * <p>The purchase order keeps quantities and prices in the purchasing unit. Stock batches,
 * inventory and movements use the stock unit. For example, {@code 1箱=12件} means that a
 * purchase of 20 boxes is received as 240 pieces and a box price of 12 yuan becomes a stock
 * cost of 1 yuan per piece.</p>
 */
final class WarehouseUnitConversion {
  private static final BigDecimal ONE = BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP);

  private WarehouseUnitConversion() {
  }

  static Optional<BigDecimal> factor(
      String purchaseUnit,
      String stockUnit,
      String conversionText
  ) {
    String purchase = normalizeUnit(purchaseUnit);
    String stock = normalizeUnit(stockUnit);
    if (purchase.isBlank() || stock.isBlank() || purchase.equals(stock)) {
      return Optional.of(ONE);
    }
    String text = normalizeText(conversionText);
    if (text.isBlank()) {
      return Optional.empty();
    }

    Optional<BigDecimal> equation = equationFactor(text, purchase, stock);
    if (equation.isPresent()) {
      return equation;
    }
    return perUnitFactor(text, purchase, stock);
  }

  static BigDecimal stockQuantity(BigDecimal purchaseQuantity, BigDecimal factor) {
    return purchaseQuantity.multiply(factor).setScale(2, RoundingMode.HALF_UP);
  }

  static BigDecimal stockUnitCost(BigDecimal purchaseUnitCost, BigDecimal factor) {
    return purchaseUnitCost.divide(factor, 4, RoundingMode.HALF_UP);
  }

  private static Optional<BigDecimal> equationFactor(
      String text,
      String purchaseUnit,
      String stockUnit
  ) {
    Pattern pattern = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)" + Pattern.quote(purchaseUnit)
            + "[=＝](\\d+(?:\\.\\d+)?)" + Pattern.quote(stockUnit)
    );
    Matcher matcher = pattern.matcher(text);
    if (!matcher.find()) {
      return Optional.empty();
    }
    return ratio(matcher.group(1), matcher.group(2));
  }

  private static Optional<BigDecimal> perUnitFactor(
      String text,
      String purchaseUnit,
      String stockUnit
  ) {
    Pattern pattern = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)" + Pattern.quote(stockUnit)
            + "(?:[/／]|每)" + Pattern.quote(purchaseUnit)
    );
    Matcher matcher = pattern.matcher(text);
    if (!matcher.find()) {
      return Optional.empty();
    }
    BigDecimal value = new BigDecimal(matcher.group(1));
    return value.signum() > 0
        ? Optional.of(value.setScale(8, RoundingMode.HALF_UP))
        : Optional.empty();
  }

  private static Optional<BigDecimal> ratio(String purchaseQuantity, String stockQuantity) {
    BigDecimal purchase = new BigDecimal(purchaseQuantity);
    BigDecimal stock = new BigDecimal(stockQuantity);
    if (purchase.signum() <= 0 || stock.signum() <= 0) {
      return Optional.empty();
    }
    return Optional.of(stock.divide(purchase, 8, RoundingMode.HALF_UP));
  }

  private static String normalizeUnit(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "").trim();
  }

  private static String normalizeText(String value) {
    return value == null
        ? ""
        : value.replace("：", ":").replaceAll("\\s+", "").trim();
  }
}
