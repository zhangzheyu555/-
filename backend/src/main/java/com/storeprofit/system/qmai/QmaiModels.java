package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class QmaiModels {
  public static final String DEFAULT_BRAND = "ruguo";

  private QmaiModels() {
  }

  public record MappingRequest(String qmaiShopId, String qmaiShopName, String storeId) {
  }

  public record ConfigRequest(Boolean enabled, String displayName, List<MappingRequest> mappings) {
  }

  public record ShopMapping(String qmaiShopId, String qmaiShopName, String storeId, String storeName) {
  }

  public record ConfigResponse(
      boolean credentialConfigured,
      boolean enabled,
      String displayName,
      List<ShopMapping> mappings,
      BatchResponse latestBatch
  ) {
  }

  public record DiscoveredShop(String qmaiShopId, String qmaiShopName) {
  }

  public record BatchResponse(
      long id,
      String month,
      String status,
      int totalTasks,
      int completedTasks,
      int failedTasks,
      int dailyRows,
      int productRows,
      String errorSummary,
      String requestedByName,
      String createdAt,
      String startedAt,
      String finishedAt
  ) {
  }

  public record StoreSummary(
      String storeId,
      String storeName,
      int activeDays,
      int sourceRows,
      BigDecimal receivable,
      BigDecimal received,
      BigDecimal cost,
      BigDecimal refund,
      BigDecimal grossProfit,
      BigDecimal grossMargin,
      boolean lowMargin
  ) {
  }

  public record ProductSummary(
      String storeId,
      String storeName,
      String productKey,
      String productId,
      String skuId,
      String itemName,
      String categoryName,
      BigDecimal quantity,
      BigDecimal refundQuantity,
      BigDecimal receivable,
      BigDecimal received,
      BigDecimal cost,
      BigDecimal refund
  ) {
  }

  public record SummaryResponse(
      String month,
      String dataStatus,
      String lastSyncedAt,
      BigDecimal receivable,
      BigDecimal received,
      BigDecimal cost,
      BigDecimal refund,
      BigDecimal grossProfit,
      BigDecimal grossMargin,
      List<StoreSummary> stores,
      List<ProductSummary> products,
      BatchResponse latestBatch
  ) {
  }

  record DailySnapshot(
      String qmaiShopId,
      String storeId,
      LocalDate businessDate,
      int sourceRows,
      BigDecimal receivable,
      BigDecimal received,
      BigDecimal cost,
      BigDecimal refund,
      List<ProductSnapshot> products
  ) {
  }

  record ProductSnapshot(
      String productKey,
      String productId,
      String skuId,
      String itemName,
      String categoryName,
      BigDecimal quantity,
      BigDecimal refundQuantity,
      BigDecimal receivable,
      BigDecimal received,
      BigDecimal cost,
      BigDecimal refund
  ) {
  }
}
