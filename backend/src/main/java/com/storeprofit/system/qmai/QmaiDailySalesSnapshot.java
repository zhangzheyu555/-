package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One successfully fetched QMAI shop day.
 *
 * <p>The snapshot is persisted as one daily total plus zero or more product rows in a single
 * database transaction. A failed fetch never creates this value, so callers can leave the
 * previously persisted day untouched.
 */
record QmaiDailySalesSnapshot(
    String qmaiShopId,
    String storeId,
    LocalDate businessDate,
    int sourceRowCount,
    BigDecimal receivableAmount,
    BigDecimal receivedAmount,
    BigDecimal costAmount,
    BigDecimal refundAmount,
    List<Product> products
) {
  record Product(
      String productKey,
      String productId,
      String skuId,
      String itemName,
      String categoryName,
      BigDecimal quantity,
      BigDecimal refundQuantity,
      BigDecimal receivableAmount,
      BigDecimal receivedAmount,
      BigDecimal costAmount,
      BigDecimal refundAmount
  ) {}
}
