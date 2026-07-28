package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Orchestrates idempotent QMAI daily and monthly historical sales synchronization. */
@Service
public class QmaiSyncService {
  private static final Logger log = LoggerFactory.getLogger(QmaiSyncService.class);
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final int LEASE_MINUTES = 120;

  private final QmaiConfigService configService;
  private final QmaiOrderService orderService;
  private final QmaiSyncRepository repository;
  private final Executor executor;

  public QmaiSyncService(
      QmaiConfigService configService,
      QmaiOrderService orderService,
      QmaiSyncRepository repository,
      @Qualifier("qmaiSyncExecutor") Executor executor
  ) {
    this.configService = configService;
    this.orderService = orderService;
    this.repository = repository;
    this.executor = executor;
  }

  /** Starts a user-requested month backfill; the current month is capped at yesterday. */
  public BatchView startMonth(long tenantId, String brand, String month,
      Collection<String> allowedStoreIds, Long actorId, String actorName) {
    YearMonth target = parseMonth(month);
    LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
    LocalDate from = target.atDay(1);
    LocalDate to = target.atEndOfMonth();
    if (to.isAfter(yesterday)) {
      to = yesterday;
    }
    if (from.isAfter(to)) {
      throw new BusinessException(
          "QMAI_SYNC_DATE_INVALID", "只能补取截至昨天的企迈营业额", HttpStatus.BAD_REQUEST);
    }
    return start(tenantId, brand, from, to, allowedStoreIds, actorId, actorName, true);
  }

  /** Starts an exact-day repair; future/today data is intentionally never considered final. */
  public BatchView startDate(long tenantId, String brand, String businessDate,
      Collection<String> allowedStoreIds, Long actorId, String actorName) {
    LocalDate date = parseDate(businessDate);
    if (date.isAfter(LocalDate.now(ZONE).minusDays(1))) {
      throw new BusinessException(
          "QMAI_SYNC_DATE_INVALID", "只能补取截至昨天的企迈营业额", HttpStatus.BAD_REQUEST);
    }
    return start(tenantId, brand, date, date, allowedStoreIds, actorId, actorName, true);
  }

  /** Used only by the 05:00 scheduler and records a system actor in the durable batch. */
  public BatchView startScheduledDate(long tenantId, String brand, LocalDate date) {
    if (date == null || date.isAfter(LocalDate.now(ZONE).minusDays(1))) {
      throw new IllegalArgumentException("定时同步日期必须是已结束的历史日期");
    }
    return start(tenantId, brand, date, date, null, null, "系统定时任务", false);
  }

  public BatchView latest(long tenantId, String brand, String month) {
    String normalizedBrand = QmaiConfigService.normBrand(brand);
    String targetMonth = parseMonth(month).toString();
    return repository.latest(tenantId, normalizedBrand, targetMonth)
        .map(this::view)
        .orElse(null);
  }

  public BatchView get(long tenantId, String brand, long batchId) {
    String normalizedBrand = QmaiConfigService.normBrand(brand);
    return repository.find(tenantId, normalizedBrand, batchId)
        .map(this::view)
        .orElseThrow(() -> new BusinessException(
            "QMAI_SYNC_BATCH_NOT_FOUND", "企迈同步批次不存在", HttpStatus.NOT_FOUND));
  }

  private BatchView start(long tenantId, String rawBrand, LocalDate from, LocalDate to,
      Collection<String> allowedStoreIds, Long actorId, String actorName, boolean async) {
    String brand = QmaiConfigService.normBrand(rawBrand);
    QmaiConfigService.EffectiveConfig cfg = configService.resolve(tenantId, brand);
    if (!cfg.isConfigured()) {
      throw new BusinessException(
          "QMAI_UNCONFIGURED", "企迈凭证未配置齐全，无法补取历史数据",
          HttpStatus.SERVICE_UNAVAILABLE);
    }
    List<QmaiProperties.ShopMapping> shops = mappedShops(
        tenantId, brand, cfg, allowedStoreIds);
    String targetMonth = YearMonth.from(from).toString();
    String leaseToken = UUID.randomUUID().toString();
    if (!repository.claimLease(tenantId, brand, leaseToken, LEASE_MINUTES)) {
      throw new BusinessException(
          "QMAI_SYNC_BUSY", "企迈营业额正在同步，请稍后查看进度", HttpStatus.CONFLICT);
    }
    repository.failStaleActiveBatches(tenantId, brand);

    int dayCount = Math.toIntExact(from.datesUntil(to.plusDays(1)).count());
    int totalTasks = Math.multiplyExact(dayCount, shops.size());
    long batchId;
    try {
      batchId = repository.createBatch(
          tenantId, brand, targetMonth, actorId, safeActorName(actorName), totalTasks);
    } catch (RuntimeException ex) {
      repository.releaseLease(tenantId, brand, leaseToken);
      throw ex;
    }
    if (async) {
      try {
        executor.execute(() -> runBatch(
            batchId, tenantId, brand, cfg, from, to, shops, leaseToken));
      } catch (RejectedExecutionException ex) {
        repository.failQueued(batchId, "同步任务队列已满，请稍后重试");
        repository.releaseLease(tenantId, brand, leaseToken);
        throw new BusinessException(
            "QMAI_SYNC_BUSY", "同步任务较多，请稍后重试", HttpStatus.TOO_MANY_REQUESTS);
      }
    } else {
      runBatch(batchId, tenantId, brand, cfg, from, to, shops, leaseToken);
    }
    return repository.find(tenantId, brand, batchId).map(this::view)
        .orElseThrow(() -> new IllegalStateException("企迈同步批次不存在"));
  }

  private void runBatch(long batchId, long tenantId, String brand,
      QmaiConfigService.EffectiveConfig cfg, LocalDate from, LocalDate to,
      List<QmaiProperties.ShopMapping> shops, String leaseToken) {
    List<String> failures = new ArrayList<>();
    try {
      repository.markRunning(batchId);
      for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
        for (QmaiProperties.ShopMapping shop : shops) {
          if (!repository.renewLease(
              tenantId, brand, leaseToken, LEASE_MINUTES)) {
            throw new IllegalStateException("企迈同步租约已失效");
          }
          try {
            QmaiDailySalesSnapshot snapshot =
                orderService.fetchDailyShop(cfg, shop, date);
            repository.replaceDay(tenantId, brand, batchId, snapshot);
            repository.markTaskSucceeded(batchId, snapshot.products().size());
          } catch (RuntimeException ex) {
            repository.markTaskFailed(batchId);
            failures.add(date + " " + displayShop(shop) + " 拉取失败");
            log.warn("QMAI sales sync task failed. tenantId={}, brand={}, shop={}, date={}",
                tenantId, brand, shop.shopCode(), date, ex);
          }
        }
      }
      QmaiSyncRepository.BatchRow row = repository.find(tenantId, brand, batchId)
          .orElseThrow(() -> new IllegalStateException("企迈同步批次不存在"));
      int succeeded = row.completedTasks() - row.failedTasks();
      String status = row.failedTasks() == 0
          ? "SUCCEEDED"
          : succeeded == 0 ? "FAILED" : "PARTIAL";
      repository.finish(batchId, status, summarizedFailures(failures));
    } catch (RuntimeException ex) {
      log.error("QMAI sales sync batch failed. tenantId={}, brand={}, batchId={}",
          tenantId, brand, batchId, ex);
      repository.failQueued(batchId, "企迈营业额同步任务异常终止，原有历史数据未被清空");
    } finally {
      repository.releaseLease(tenantId, brand, leaseToken);
    }
  }

  private List<QmaiProperties.ShopMapping> mappedShops(long tenantId, String brand,
      QmaiConfigService.EffectiveConfig cfg, Collection<String> allowedStoreIds) {
    // qmai_platform_config.shops is canonical; qmai_store_mapping is only a normalized mirror.
    // Never let stale legacy mirror rows override a configuration saved from the platform page.
    List<QmaiProperties.ShopMapping> source = cfg.resolveShops(allowedStoreIds);
    if (source.isEmpty()) {
      throw new BusinessException(
          "QMAI_SHOP_MAPPING_REQUIRED",
          "请先将企迈门店映射到系统门店，再补取历史数据",
          HttpStatus.BAD_REQUEST);
    }
    Set<String> identities = new LinkedHashSet<>();
    java.util.Map<String, String> qmaiToStore = new LinkedHashMap<>();
    java.util.Map<String, String> storeToQmai = new LinkedHashMap<>();
    List<QmaiProperties.ShopMapping> validated = new ArrayList<>();
    for (QmaiProperties.ShopMapping shop : source) {
      if (shop == null || shop.shopCode() == null || shop.shopCode().isBlank()
          || shop.storeId() == null || shop.storeId().isBlank()) {
        throw new BusinessException(
            "QMAI_SHOP_MAPPING_REQUIRED",
            "企迈门店映射不完整，请补充对应的系统门店",
            HttpStatus.BAD_REQUEST);
      }
      String storeId = shop.storeId().trim();
      if (allowedStoreIds != null && !allowedStoreIds.contains(storeId)) {
        continue;
      }
      if (!repository.storeExists(tenantId, storeId)) {
        throw new BusinessException(
            "QMAI_SHOP_MAPPING_INVALID",
            "企迈门店映射的系统门店不存在，请先修正映射",
            HttpStatus.BAD_REQUEST);
      }
      String qmaiShopId = shop.shopCode().trim();
      String previousStore = qmaiToStore.putIfAbsent(qmaiShopId, storeId);
      String previousShop = storeToQmai.putIfAbsent(storeId, qmaiShopId);
      if ((previousStore != null && !previousStore.equals(storeId))
          || (previousShop != null && !previousShop.equals(qmaiShopId))) {
        throw new BusinessException(
            "QMAI_SHOP_MAPPING_INVALID",
            "企迈门店与系统门店必须一一对应，请修正重复映射",
            HttpStatus.BAD_REQUEST);
      }
      String identity = qmaiShopId + "|" + storeId;
      if (identities.add(identity)) {
        validated.add(new QmaiProperties.ShopMapping(
            qmaiShopId, safeShopName(shop), storeId));
      }
    }
    if (validated.isEmpty()) {
      throw new BusinessException(
          "FORBIDDEN", "当前账号没有可同步的企迈门店范围", HttpStatus.FORBIDDEN);
    }
    return List.copyOf(validated);
  }

  private YearMonth parseMonth(String raw) {
    try {
      return YearMonth.parse(raw == null || raw.isBlank()
          ? YearMonth.now(ZONE).toString() : raw.trim());
    } catch (RuntimeException ex) {
      throw new BusinessException(
          "QMAI_MONTH_INVALID", "月份格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private LocalDate parseDate(String raw) {
    try {
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("empty");
      }
      return LocalDate.parse(raw.trim());
    } catch (RuntimeException ex) {
      throw new BusinessException(
          "QMAI_DATE_INVALID", "日期格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private BatchView view(QmaiSyncRepository.BatchRow row) {
    return new BatchView(
        row.id(), row.brand(), row.targetMonth(), row.status(),
        row.totalTasks(), row.completedTasks(), row.failedTasks(),
        row.dailyRows(), row.productRows(), row.errorSummary(),
        statusMessage(row.status()), row.requestedByName(),
        row.createdAt(), row.startedAt(), row.finishedAt());
  }

  private String statusMessage(String status) {
    return switch (status) {
      case "QUEUED" -> "历史数据补取任务已排队";
      case "RUNNING" -> "正在补取企迈历史数据";
      case "SUCCEEDED" -> "企迈历史数据补取完成";
      case "PARTIAL" -> "部分日期补取失败，失败日期已保留原数据";
      case "FAILED" -> "企迈历史数据补取失败，失败日期已保留原数据";
      default -> "企迈历史数据同步状态未知";
    };
  }

  private String summarizedFailures(List<String> failures) {
    if (failures == null || failures.isEmpty()) {
      return null;
    }
    String joined = String.join("；", failures);
    return joined.length() <= 1000 ? joined : joined.substring(0, 997) + "...";
  }

  private String safeActorName(String actorName) {
    if (actorName == null || actorName.isBlank()) {
      return "系统定时任务";
    }
    String value = actorName.trim();
    return value.length() <= 120 ? value : value.substring(0, 120);
  }

  private String displayShop(QmaiProperties.ShopMapping shop) {
    String name = safeShopName(shop);
    return name + "(" + shop.shopCode() + ")";
  }

  private String safeShopName(QmaiProperties.ShopMapping shop) {
    if (shop.shopName() == null || shop.shopName().isBlank()) {
      return shop.shopCode().trim();
    }
    String value = shop.shopName().trim();
    return value.length() <= 160 ? value : value.substring(0, 160);
  }

  public record BatchView(
      long id,
      String brand,
      String targetMonth,
      String status,
      int totalTasks,
      int completedTasks,
      int failedTasks,
      int dailyRows,
      int productRows,
      String errorSummary,
      String message,
      String requestedByName,
      LocalDateTime createdAt,
      LocalDateTime startedAt,
      LocalDateTime finishedAt
  ) {}
}
