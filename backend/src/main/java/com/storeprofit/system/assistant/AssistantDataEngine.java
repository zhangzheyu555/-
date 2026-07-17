package com.storeprofit.system.assistant;

import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.inspection.InspectionRecordResponse;
import com.storeprofit.system.inspection.InspectionService;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Builds permission-filtered, non-sensitive operating metrics from MySQL. */
@Component
public class AssistantDataEngine {
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final String MONTHLY_FINANCE_SOURCE_VERSION = "profit-entry-monthly-v1";
  public static final String CALCULATION_VERSION = "operating-snapshot-v4-inspection-200";
  private final FinanceService financeService;
  private final InspectionService inspectionService;
  private final AccessControlService accessControl;
  private final OrganizationRepository organizationRepository;
  private final Clock clock;

  @Autowired
  public AssistantDataEngine(
      FinanceService financeService,
      InspectionService inspectionService,
      AccessControlService accessControl,
      OrganizationRepository organizationRepository
  ) {
    this(financeService, inspectionService, accessControl, organizationRepository, Clock.system(BUSINESS_ZONE));
  }

  AssistantDataEngine(
      FinanceService financeService,
      InspectionService inspectionService,
      AccessControlService accessControl,
      OrganizationRepository organizationRepository,
      Clock clock
  ) {
    this.financeService = financeService;
    this.inspectionService = inspectionService;
    this.accessControl = accessControl;
    this.organizationRepository = organizationRepository;
    this.clock = clock == null ? Clock.system(BUSINESS_ZONE) : clock;
  }

  /** Compatibility constructor retained for focused authorization tests. */
  public AssistantDataEngine(
      FinanceService financeService,
      InspectionService inspectionService,
      AccessControlService accessControl
  ) {
    this(financeService, inspectionService, accessControl, null);
  }

  /** Compatibility constructor retained for focused tests that provide inspection data. */
  public AssistantDataEngine(FinanceService financeService, InspectionService inspectionService) {
    this(financeService, inspectionService, null, null);
  }

  /** Compatibility constructor retained for focused tests. */
  public AssistantDataEngine(FinanceService financeService) {
    this(financeService, null, null, null);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Result build(AuthUser user, AssistantChatRequest request, String question) {
    String month = resolveMonth(user, request, question);
    String storeId = resolveStoreId(user, request);

    // FinanceService enforces tenant and data-scope access. A cross-store request stops here with 403.
    List<ProfitEntryResponse> currentRows = financeService.entries(user, month, null, blankToNull(storeId));
    List<ProfitEntryResponse> benchmarkRows = financeService.entries(user, month, null, null);
    Aggregate current = Aggregate.of(currentRows);

    String storeName = storeId.isBlank() ? "" : resolveStoreName(user, storeId, currentRows);
    String dataScope = storeId.isBlank() ? "全部授权门店" : (storeName.isBlank() ? storeId : storeName);
    YearMonth selected = YearMonth.parse(month);
    List<ProfitEntryResponse> previousRows = financeService.entries(
        user, selected.minusMonths(1).toString(), null, blankToNull(storeId));
    Aggregate previous = Aggregate.of(previousRows);
    List<ProfitEntryResponse> previousYearRows = financeService.entries(
        user, selected.minusYears(1).toString(), null, blankToNull(storeId));
    Aggregate previousYear = Aggregate.of(previousYearRows);
    Instant updatedAt = Instant.now(clock);
    String sourceInputVersion = sha256(
        user.tenantId() + "|" + month + "|" + storeId + "|" + safeRows(currentRows) + "|" + safeRows(previousRows)
    );
    OperatingSnapshot snapshot = operatingSnapshot(
        user,
        selected,
        storeId,
        storeName,
        dataScope,
        currentRows,
        previousRows,
        current,
        previous,
        updatedAt,
        sourceInputVersion
    );

    List<AssistantChatResponse.Metric> metrics = new ArrayList<>();
    metrics.add(moneyMetric("sales", "营业额", current.sales, null, ""));
    metrics.add(moneyMetric("refund", "退款金额", current.refund, null, ""));
    metrics.add(moneyMetric("discount", "优惠金额", current.discount, null, ""));
    metrics.add(moneyMetric("income", "实收收入", current.income, null, ""));
    metrics.add(moneyMetric("material", "原材料成本", current.material, null, ""));
    metrics.add(moneyMetric("packaging", "包材成本", current.packaging, null, ""));
    metrics.add(moneyMetric("loss", "损耗成本", current.loss, null, ""));
    metrics.add(moneyMetric("costOther", "其他成本", current.costOther, null, ""));
    metrics.add(moneyMetric("cost", "成本合计", current.cost, null, ""));
    metrics.add(moneyMetric("gross", "毛利润", current.gross, null, ""));
    metrics.add(moneyMetric("rent", "房租", current.rent, null, ""));
    metrics.add(moneyMetric("labor", "人工费用", current.labor, null, ""));
    metrics.add(moneyMetric("utility", "水电费", current.utility, null, ""));
    metrics.add(moneyMetric("property", "物业费", current.property, null, ""));
    metrics.add(moneyMetric("commission", "平台佣金", current.commission, null, ""));
    metrics.add(moneyMetric("promo", "推广费", current.promo, null, ""));
    metrics.add(moneyMetric("repair", "维修费", current.repair, null, ""));
    metrics.add(moneyMetric("equipment", "设备费", current.equipment, null, ""));
    metrics.add(moneyMetric("expenseOther", "其他费用", current.expenseOther, null, ""));
    metrics.add(moneyMetric("expense", "费用合计", current.expense, null, ""));
    metrics.add(moneyMetric(
        "net",
        "净利润",
        snapshot.netProfit(),
        snapshot.capabilities().canCompare()
            ? changeRate(snapshot.netProfit(), snapshot.previousComparablePeriod().netProfit(), true)
            : null,
        snapshot.comparisonBasis().available() ? "较可比期" : snapshot.comparisonBasis().explanation()
    ));
    metrics.add(percentMetric("margin", "净利率", snapshot.netMargin(), null, ""));
    metrics.add(percentMetric("materialRate", "原材料成本率", ratio(current.material, current.income), null, ""));
    metrics.add(percentMetric("laborRate", "人工成本率", ratio(current.labor, current.income), null, ""));
    metrics.add(percentMetric("expenseRate", "费用率", ratio(current.expense, current.income), null, ""));

    if (snapshot.capabilities().canCompare()) {
      metrics.add(percentMetric(
          "momRevenueChange",
          "实收收入环比",
          changeRate(snapshot.revenue(), snapshot.previousComparablePeriod().revenue(), true),
          null,
          "同门店、同天数、同月度口径"
      ));
      metrics.add(percentMetric(
          "momNetChange",
          "净利润环比",
          changeRate(snapshot.netProfit(), snapshot.previousComparablePeriod().netProfit(), true),
          null,
          "同门店、同天数、同月度口径"
      ));
    }

    if (!storeId.isBlank() && !currentRows.isEmpty()) {
      ProfitEntryResponse selectedEntry = currentRows.getFirst();
      List<ProfitEntryResponse> brandRows = financeService.entries(user, month, selectedEntry.brandId(), null);
      metrics.add(moneyMetric("brandAverageNet", "同品牌门店平均净利润", averageNet(brandRows), null, "同品牌"));
      metrics.add(percentMetric("brandAverageMargin", "同品牌门店平均净利率", averageMargin(brandRows), null, "同品牌"));
      metrics.add(moneyMetric("allStoreAverageNet", "全部门店平均净利润", averageNet(benchmarkRows), null, "授权范围"));
      metrics.add(percentMetric("allStoreAverageMargin", "全部门店平均净利率", averageMargin(benchmarkRows), null, "授权范围"));
    }

    List<TrendPoint> trend = new ArrayList<>();
    for (int offset = 2; offset >= 0; offset--) {
      String trendMonth = selected.minusMonths(offset).toString();
      Aggregate aggregate = aggregateFor(user, trendMonth, storeId);
      if (aggregate.hasData) {
        trend.add(new TrendPoint(trendMonth, aggregate.sales, aggregate.income, aggregate.net, aggregate.margin()));
      }
    }

    List<String> limitations = new ArrayList<>();
    if (!current.hasData) limitations.add("所选月份暂无经营数据");
    if (!snapshot.capabilities().canCompare()) limitations.add(snapshot.comparisonBasis().explanation());
    if (!previousYear.hasData) limitations.add("缺少去年同期数据，无法计算同比");
    if (trend.size() < 3) limitations.add("近三个月数据不足，趋势判断可信度受限");
    limitations.addAll(snapshot.dataQuality().notices());

    InspectionSummary inspectionSummary = inspectionSummary(user, storeId);
    if (!inspectionSummary.serviceAvailable()) {
      limitations.add("巡检数据服务不可用，无法读取巡检摘要");
    } else if (!inspectionSummary.readAuthorized()) {
      limitations.add("当前账号无巡检读取权限，经营快照未包含巡检数据");
    } else if (!inspectionSummary.hasData()) {
      limitations.add("当前授权范围内暂无巡检记录");
    } else {
      appendInspectionMetrics(metrics, inspectionSummary);
    }

    List<String> anomalies = new ArrayList<>();
    if (current.hasData && current.net.compareTo(BigDecimal.ZERO) < 0) anomalies.add("净利润为负");
    if (current.hasData && current.margin().compareTo(new BigDecimal("0.05")) < 0) anomalies.add("净利率低于5%");
    if (current.hasData && ratio(current.material, current.income).compareTo(new BigDecimal("0.40")) > 0) {
      anomalies.add("原材料成本率高于40%");
    }
    if (current.hasData && ratio(current.labor, current.income).compareTo(new BigDecimal("0.20")) > 0) {
      anomalies.add("人工成本率高于20%");
    }

    String summary = localSummary(dataScope, month, snapshot);
    AssistantChatResponse.LocalData preliminary = new AssistantChatResponse.LocalData(
        summary,
        metrics,
        month,
        dataScope,
        "MySQL 8 财务库",
        "",
        CALCULATION_VERSION,
        updatedAt
    );
    String modelContext = modelContext(snapshot, preliminary, trend, anomalies, limitations, inspectionSummary);
    String dataVersion = sha256(snapshot.snapshotId() + "|" + modelContext);
    AssistantChatResponse.LocalData localData = new AssistantChatResponse.LocalData(
        summary, metrics, month, dataScope, "MySQL 8 财务库", dataVersion,
        CALCULATION_VERSION, updatedAt
    );
    return new Result(localData, modelContext, dataVersion, storeId, storeName, month, limitations, snapshot);
  }

  private OperatingSnapshot operatingSnapshot(
      AuthUser user,
      YearMonth selected,
      String selectedStoreId,
      String selectedStoreName,
      String scopeLabel,
      List<ProfitEntryResponse> currentRows,
      List<ProfitEntryResponse> previousRows,
      Aggregate current,
      Aggregate previous,
      Instant generatedAt,
      String sourceInputVersion
  ) {
    LocalDate queryDate = LocalDate.now(clock);
    boolean isMTD = selected.equals(YearMonth.from(queryDate));
    LocalDate periodStart = selected.atDay(1);
    LocalDate periodEnd = isMTD ? queryDate : selected.atEndOfMonth();
    LocalDate asOf = isMTD ? null : periodEnd;

    List<ScopeStore> expectedStores = expectedStores(user, selectedStoreId, periodEnd, currentRows);
    List<String> expectedIds = expectedStores.stream().map(ScopeStore::id).toList();
    List<String> expectedNames = expectedStores.stream().map(ScopeStore::name).toList();
    Set<String> reportedIds = currentRows == null ? Set.of() : currentRows.stream()
        .filter(Objects::nonNull)
        .map(ProfitEntryResponse::storeId)
        .filter(value -> value != null && !value.isBlank())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<ScopeStore> missingStores = expectedStores.stream()
        .filter(store -> !reportedIds.contains(store.id()))
        .toList();
    int expectedCount = expectedStores.size();
    int reportedCount = expectedCount == 0
        ? reportedIds.size()
        : (int) expectedIds.stream().filter(reportedIds::contains).count();
    BigDecimal coverageRate = expectedCount == 0
        ? BigDecimal.ZERO
        : BigDecimal.valueOf(reportedCount).divide(BigDecimal.valueOf(expectedCount), 4, RoundingMode.HALF_UP);
    boolean coverageComplete = expectedCount > 0 && missingStores.isEmpty();

    List<String> missingFields = new ArrayList<>();
    // The monthly source has no fields for these accounting items. Null in the snapshot means
    // unknown, never a silent zero value.
    missingFields.add("otherIncomeExpense");
    missingFields.add("tax");
    if (isMTD) missingFields.add("businessAsOf");
    // FinanceService currently exposes only monthly aggregates. Natural calendar days
    // cannot stand in for same-store, same-operating-day evidence.
    missingFields.add("dailyOperatingCoverage");
    if (current.hasData && current.income.compareTo(BigDecimal.ZERO) > 0 && current.cost.compareTo(BigDecimal.ZERO) == 0) {
      missingFields.add("costOfSalesDetail");
    }
    if (current.hasData && current.income.compareTo(BigDecimal.ZERO) > 0 && current.expense.compareTo(BigDecimal.ZERO) == 0) {
      missingFields.add("operatingExpenseDetail");
    }
    if (expectedCount == 0) {
      missingFields.add("expectedStoreReportingRule");
    }

    Set<String> previousReportedIds = previousRows == null ? Set.of() : previousRows.stream()
        .filter(Objects::nonNull)
        .map(ProfitEntryResponse::storeId)
        .filter(value -> value != null && !value.isBlank())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    boolean sameStoreScope = coverageComplete && !expectedIds.isEmpty()
        && previousReportedIds.containsAll(expectedIds)
        && expectedIds.containsAll(previousReportedIds);
    boolean sameDayCount = !isMTD && selected.lengthOfMonth() == selected.minusMonths(1).lengthOfMonth();
    boolean sameOperatingDays = false;
    boolean comparable = current.hasData && sameStoreScope && sameDayCount && sameOperatingDays;
    String comparisonExplanation;
    if (isMTD) {
      comparisonExplanation = "当前月仅有月度汇总，未记录同营业日和业务截至日，不能与完整上月直接环比。";
    } else if (!coverageComplete) {
      comparisonExplanation = "应报门店尚未全部报数，不能进行同门店比较。";
    } else if (!sameStoreScope) {
      comparisonExplanation = "比较期门店范围不一致，不能进行同门店比较。";
    } else if (!sameOperatingDays) {
      comparisonExplanation = "月度汇总尚未提供同营业日覆盖证据，已禁用环比。";
    } else if (!sameDayCount) {
      comparisonExplanation = "两个完整月的自然日天数不同，当前月度表没有同营业日口径，已禁用环比。";
    } else if (!current.hasData) {
      comparisonExplanation = "当前范围没有可验证的经营数据。";
    } else {
      comparisonExplanation = "同门店、同月度经营口径且自然日天数一致。";
    }
    LocalDate previousStart = selected.minusMonths(1).atDay(1);
    LocalDate previousEnd = selected.minusMonths(1).atEndOfMonth();
    OperatingSnapshot.PreviousComparablePeriod previousComparable = new OperatingSnapshot.PreviousComparablePeriod(
        comparable,
        previousStart,
        previousEnd,
        expectedIds,
        comparable ? previous.income : null,
        comparable ? previous.net : null,
        comparable ? previous.margin() : null,
        comparable ? "" : comparisonExplanation
    );
    OperatingSnapshot.ComparisonBasis comparisonBasis = new OperatingSnapshot.ComparisonBasis(
        comparable,
        sameStoreScope,
        true,
        sameOperatingDays,
        sameDayCount,
        comparisonExplanation
    );

    BigDecimal operatingProfit = current.income.subtract(current.cost).subtract(current.expense);
    BigDecimal unclassifiedDifference = current.net.subtract(operatingProfit);
    BigDecimal netProfit = operatingProfit.add(unclassifiedDifference);
    BigDecimal netMargin = ratio(netProfit, current.income);
    boolean canComputeKPI = current.hasData;
    boolean canAttributeCause = canComputeKPI
        && coverageComplete
        && !isMTD
        && current.income.compareTo(BigDecimal.ZERO) > 0
        && current.cost.compareTo(BigDecimal.ZERO) > 0
        && current.expense.compareTo(BigDecimal.ZERO) > 0;
    OperatingSnapshot.Capabilities capabilities = new OperatingSnapshot.Capabilities(
        canComputeKPI,
        comparable,
        canAttributeCause,
        canAttributeCause
    );

    List<String> qualityNotices = new ArrayList<>();
    if (!canComputeKPI) qualityNotices.add("所选范围没有可验证的月度经营数据。");
    if (!coverageComplete) qualityNotices.add("门店覆盖未完成；已展示的金额仅代表已报门店。");
    if (isMTD) qualityNotices.add("当前月展示的是查询日的月度快照，不等同于已核验的业务截至日。");
    qualityNotices.add("当前利润为月度经营利润口径；其他损益和税费尚未建模，不能当作零。 ");
    qualityNotices.add("月度汇总未提供同营业日覆盖证据，环比已禁用。");
    if (missingFields.contains("costOfSalesDetail") || missingFields.contains("operatingExpenseDetail")) {
      qualityNotices.add("成本或费用为零值，当前表无法区分真实零值与未录入明细；已限制原因归因。 ");
    }
    String qualityLevel = !canComputeKPI ? "INSUFFICIENT" : (coverageComplete && !isMTD ? "PARTIAL" : "PARTIAL");

    String sourceVersion = MONTHLY_FINANCE_SOURCE_VERSION + ":" + sha256(
        safeRows(currentRows) + "|" + safeRows(previousRows) + "|" + String.join(",", expectedIds)
    ).substring(0, 20);
    String asOfKey = asOf == null ? "UNKNOWN" : asOf.toString();
    String snapshotId = sha256(String.join("|",
        environmentName(),
        String.valueOf(user.tenantId()),
        String.join(",", expectedIds),
        selected.toString(),
        asOfKey,
        sourceVersion,
        CALCULATION_VERSION,
        sourceInputVersion == null ? "" : sourceInputVersion
    ));
    OperatingSnapshot.ProfitBridge bridge = new OperatingSnapshot.ProfitBridge(
        "MONTHLY_OPERATING_PROFIT_PRE_TAX",
        current.sales,
        current.refund,
        current.discount,
        current.income,
        current.cost,
        current.expense,
        null,
        null,
        unclassifiedDifference,
        netProfit
    );
    return new OperatingSnapshot(
        snapshotId,
        generatedAt,
        asOf,
        periodStart,
        periodEnd,
        isMTD,
        new OperatingSnapshot.StoreScope(scopeLabel, expectedIds, expectedNames),
        new OperatingSnapshot.StoreCoverage(
            expectedCount,
            reportedCount,
            missingStores.stream().map(ScopeStore::id).toList(),
            missingStores.stream().map(ScopeStore::name).toList(),
            List.of(),
            false,
            coverageRate
        ),
        current.income,
        current.cost,
        current.expense,
        null,
        null,
        netProfit,
        netMargin,
        previousComparable,
        comparisonBasis,
        bridge,
        capabilities,
        new OperatingSnapshot.DataQuality(qualityLevel, qualityNotices, false, asOf != null),
        missingFields,
        sourceVersion
    );
  }

  private List<ScopeStore> expectedStores(
      AuthUser user,
      String selectedStoreId,
      LocalDate periodEnd,
      List<ProfitEntryResponse> currentRows
  ) {
    LinkedHashMap<String, ScopeStore> stores = new LinkedHashMap<>();
    if (organizationRepository != null) {
      DataScope scope = financeScope(user);
      for (StoreResponse store : organizationRepository.stores(user.tenantId(), scope)) {
        if (!isExpectedStore(store, periodEnd)) continue;
        if (selectedStoreId != null && !selectedStoreId.isBlank() && !selectedStoreId.equals(store.id())) continue;
        stores.put(store.id(), new ScopeStore(store.id(), firstNonBlank(store.name(), store.id())));
      }
    }
    // Focused unit tests intentionally use no organization repository. In that case only the
    // reporting rows are known; we do not invent missing stores.
    if (organizationRepository == null && stores.isEmpty() && currentRows != null) {
      for (ProfitEntryResponse row : currentRows) {
        if (row == null || row.storeId() == null || row.storeId().isBlank()) continue;
        if (selectedStoreId != null && !selectedStoreId.isBlank() && !selectedStoreId.equals(row.storeId())) continue;
        stores.put(row.storeId(), new ScopeStore(row.storeId(), firstNonBlank(row.storeName(), row.storeId())));
      }
    }
    return stores.values().stream().sorted(Comparator.comparing(ScopeStore::id)).toList();
  }

  private DataScope financeScope(AuthUser user) {
    if (accessControl != null) return accessControl.dataScope(user, DataScopeDomains.FINANCE);
    if ("STORE_MANAGER".equals(user.role()) && user.storeId() != null && !user.storeId().isBlank()) {
      return new DataScope(DataScopeModes.OWN_STORE, List.of(user.storeId()));
    }
    return DataScope.all();
  }

  private boolean isExpectedStore(StoreResponse store, LocalDate periodEnd) {
    if (store == null || store.id() == null || store.id().isBlank()) return false;
    String status = store.status() == null ? "" : store.status().trim().toUpperCase(Locale.ROOT);
    if (!("营业中".equals(store.status()) || "ACTIVE".equals(status) || "OPEN".equals(status))) return false;
    if (store.openDate() == null || store.openDate().isBlank()) return true;
    try {
      return !LocalDate.parse(store.openDate()).isAfter(periodEnd);
    } catch (Exception ignored) {
      // An invalid open date cannot prove that a store was expected in the selected period.
      return false;
    }
  }

  private String safeRows(List<ProfitEntryResponse> rows) {
    if (rows == null || rows.isEmpty()) return "";
    return rows.stream().filter(Objects::nonNull)
        .sorted(Comparator.comparing(ProfitEntryResponse::storeId, Comparator.nullsLast(String::compareTo)))
        .map(Object::toString)
        .reduce("", (left, right) -> left + "|" + right);
  }

  private String environmentName() {
    String configured = System.getProperty("app.env");
    if (configured == null || configured.isBlank()) configured = System.getenv("APP_ENV");
    return configured == null || configured.isBlank() ? "LOCAL" : configured.trim().toUpperCase(Locale.ROOT);
  }

  private record ScopeStore(String id, String name) {}

  private Aggregate aggregateFor(AuthUser user, String month, String storeId) {
    return Aggregate.of(financeService.entries(user, month, null, blankToNull(storeId)));
  }

  private InspectionSummary inspectionSummary(AuthUser user, String storeId) {
    if (inspectionService == null) {
      return InspectionSummary.unavailable();
    }
    if (accessControl != null
        && !accessControl.hasPermission(user, PermissionCodes.INSPECTION_READ)) {
      return InspectionSummary.unauthorized();
    }
    List<InspectionRecordResponse> records = inspectionService.records(
        user, null, null, null, blankToNull(storeId), null);
    if (records == null || records.isEmpty()) {
      return InspectionSummary.empty();
    }
    List<InspectionRecordResponse> canonicalRecords = records.stream()
        .filter(Objects::nonNull)
        .toList();
    if (canonicalRecords.isEmpty()) {
      return InspectionSummary.empty();
    }
    InspectionRecordResponse latest = canonicalRecords.stream()
        .max(Comparator
            .comparing(InspectionRecordResponse::inspectionDate,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(InspectionRecordResponse::id,
                Comparator.nullsFirst(Comparator.naturalOrder())))
        .orElseThrow();
    BigDecimal averageScore = canonicalRecords.stream()
        .map(InspectionRecordResponse::displayScore)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(canonicalRecords.size()), 2, RoundingMode.HALF_UP);
    long failedCount = canonicalRecords.stream()
        .filter(record -> !record.displayPassed())
        .count();
    long redLineCount = canonicalRecords.stream()
        .filter(record -> "RED_LINE_FAILED".equals(record.displayResultCode()))
        .count();
    return new InspectionSummary(
        true,
        true,
        canonicalRecords.size(),
        averageScore,
        failedCount,
        redLineCount,
        latest.inspectionDate(),
        firstNonBlank(latest.storeName(), latest.storeId()),
        latest.displayScore(),
        latest.maxScore(),
        latest.passScore(),
        latest.displayPassed(),
        latest.displayResultCode()
    );
  }

  private void appendInspectionMetrics(
      List<AssistantChatResponse.Metric> metrics,
      InspectionSummary summary
  ) {
    String latestScope = joinNonBlank(summary.latestStoreName(), summary.latestInspectionDate());
    metrics.add(scoreMetric(
        "inspectionLatestScore", "最近巡检得分", summary.latestScore(),
        "满分" + score(summary.maxScore()) + (latestScope.isBlank() ? "" : " · " + latestScope)));
    metrics.add(scoreMetric(
        "inspectionMaxScore", "巡检满分", summary.maxScore(), "统一200分制"));
    metrics.add(scoreMetric(
        "inspectionPassScore", "巡检合格线", summary.passScore(), "统一合格线"));
    metrics.add(new AssistantChatResponse.Metric(
        "inspectionLatestPassed",
        "最近巡检结果",
        summary.latestPassed() ? BigDecimal.ONE : BigDecimal.ZERO,
        "BOOLEAN",
        summary.latestPassed() ? "合格" : "不合格",
        null,
        "resultCode=" + summary.latestResultCode()
    ));
    metrics.add(new AssistantChatResponse.Metric(
        "inspectionAverageScore",
        "授权范围巡检平均分",
        summary.averageScore(),
        "SCORE",
        score(summary.averageScore()) + " / " + score(summary.maxScore()),
        null,
        "共" + summary.recordCount() + "条"
    ));
    metrics.add(countMetric(
        "inspectionFailedCount", "巡检不合格数", summary.failedCount(), "后端统一判定"));
    metrics.add(countMetric(
        "inspectionRedLineCount", "巡检红线数", summary.redLineCount(), "resultCode=RED_LINE_FAILED"));
  }

  private String resolveMonth(AuthUser user, AssistantChatRequest request, String question) {
    if (request.month() != null && !request.month().isBlank()) {
      return YearMonth.parse(request.month()).toString();
    }
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("(?:(20\\d{2})[-/.年])?\\s*(1[0-2]|0?[1-9])\\s*月")
        .matcher(question == null ? "" : question);
    if (matcher.find()) {
      int year = matcher.group(1) == null ? YearMonth.now().getYear() : Integer.parseInt(matcher.group(1));
      return YearMonth.of(year, Integer.parseInt(matcher.group(2))).toString();
    }
    return financeService.months(user).stream()
        .filter(value -> value != null && value.matches("\\d{4}-\\d{2}"))
        .findFirst()
        .orElse(YearMonth.now().toString());
  }

  private String resolveStoreId(AuthUser user, AssistantChatRequest request) {
    if (request.storeId() != null && !request.storeId().isBlank()) return request.storeId().trim();
    if ("STORE_MANAGER".equals(user.role()) && user.storeId() != null) return user.storeId().trim();
    return "";
  }

  private String resolveStoreName(AuthUser user, String storeId, List<ProfitEntryResponse> currentRows) {
    if (!currentRows.isEmpty()) return currentRows.getFirst().storeName();
    return financeService.months(user).stream()
        .filter(value -> value != null && value.matches("\\d{4}-\\d{2}"))
        .limit(18)
        .map(month -> financeService.entries(user, month, null, storeId))
        .filter(rows -> !rows.isEmpty())
        .map(rows -> rows.getFirst().storeName())
        .filter(Objects::nonNull)
        .findFirst()
        .orElse("");
  }

  private String localSummary(String scope, String month, OperatingSnapshot snapshot) {
    if (!snapshot.capabilities().canComputeKPI()) return scope + "在" + month + "暂无经营数据。";
    return scope + " " + month + "：实收收入" + money(snapshot.revenue())
        + "，成本" + money(snapshot.costOfSales())
        + "，费用" + money(snapshot.operatingExpense())
        + "，经营利润" + money(snapshot.netProfit())
        + "，经营利润率" + percent(snapshot.netMargin()) + "。";
  }

  private String modelContext(
      OperatingSnapshot snapshot,
      AssistantChatResponse.LocalData localData,
      List<TrendPoint> trend,
      List<String> anomalies,
      List<String> limitations,
      InspectionSummary inspectionSummary
  ) {
    StringBuilder value = new StringBuilder();
    value.append("经营快照规则：所有金额、数据质量和可比性仅以本次只读快照为准。\n");
    value.append("数据期间：").append(localData.dataPeriod()).append('\n');
    value.append("数据范围：").append(localData.dataScope()).append('\n');
    value.append("经营快照核心：实收收入").append(money(snapshot.revenue()))
        .append("，成本").append(money(snapshot.costOfSales()))
        .append("，费用").append(money(snapshot.operatingExpense()))
        .append("，经营利润").append(money(snapshot.netProfit()))
        .append("，经营利润率").append(percent(snapshot.netMargin())).append('\n');
    value.append("数据截至日期：")
        .append(snapshot.asOf() == null ? "未记录（不可将查询日期当作经营截止日）" : snapshot.asOf())
        .append('\n');
    value.append("比较状态：").append(snapshot.comparisonBasis().explanation()).append('\n');
    value.append("数据来源：").append(localData.source()).append('\n');
    value.append("计算口径版本：").append(CALCULATION_VERSION).append('\n');
    value.append("结构化指标：\n");
    localData.metrics().forEach(metric -> value.append("- ").append(metric.label()).append("：")
        .append(metric.displayValue())
        .append(metric.changeRate() == null ? "" : "（变化率" + percent(metric.changeRate()) + "）")
        .append('\n'));
    value.append("近三个月趋势：\n");
    trend.stream().sorted(Comparator.comparing(TrendPoint::month)).forEach(point -> value
        .append("- ").append(point.month()).append("：营业额").append(money(point.sales()))
        .append("，实收").append(money(point.income()))
        .append("，净利润").append(money(point.net()))
        .append("，净利率").append(percent(point.margin())).append('\n'));
    value.append("巡检摘要（仅限当前账号授权范围，结果使用后端统一口径）：\n");
    if (!inspectionSummary.serviceAvailable()) {
      value.append("- 巡检数据服务不可用。\n");
    } else if (!inspectionSummary.readAuthorized()) {
      value.append("- 当前账号无巡检读取权限，未查询巡检数据。\n");
    } else if (!inspectionSummary.hasData()) {
      value.append("- 暂无巡检记录。\n");
    } else {
      value.append("- 最近巡检：日期=").append(firstNonBlank(
              inspectionSummary.latestInspectionDate(), "未记录"))
          .append("；门店=").append(firstNonBlank(inspectionSummary.latestStoreName(), "未记录"))
          .append("；score=").append(score(inspectionSummary.latestScore()))
          .append("；maxScore=").append(score(inspectionSummary.maxScore()))
          .append("；passScore=").append(score(inspectionSummary.passScore()))
          .append("；passed=").append(inspectionSummary.latestPassed())
          .append("；resultCode=").append(inspectionSummary.latestResultCode())
          .append('\n');
      value.append("- 授权范围汇总：recordCount=").append(inspectionSummary.recordCount())
          .append("；averageScore=").append(score(inspectionSummary.averageScore()))
          .append("/").append(score(inspectionSummary.maxScore()))
          .append("；failedCount=").append(inspectionSummary.failedCount())
          .append("；redLineCount=").append(inspectionSummary.redLineCount())
          .append('\n');
      value.append("- 巡检数据来源：MySQL 8 inspection_record，经 InspectionService 权限和门店范围过滤。\n");
    }
    value.append("规则识别异常：").append(anomalies.isEmpty() ? "无" : String.join("；", anomalies)).append('\n');
    value.append("数据限制：").append(limitations.isEmpty() ? "无" : String.join("；", limitations));
    return value.toString();
  }

  private AssistantChatResponse.Metric moneyMetric(
      String key,
      String label,
      BigDecimal value,
      BigDecimal changeRate,
      String comparison
  ) {
    return new AssistantChatResponse.Metric(key, label, safe(value), "CNY", money(value), changeRate, comparison);
  }

  private AssistantChatResponse.Metric percentMetric(
      String key,
      String label,
      BigDecimal value,
      BigDecimal changeRate,
      String comparison
  ) {
    return new AssistantChatResponse.Metric(key, label, safe(value), "PERCENT", percent(value), changeRate, comparison);
  }

  private AssistantChatResponse.Metric scoreMetric(
      String key,
      String label,
      BigDecimal value,
      String comparison
  ) {
    return new AssistantChatResponse.Metric(
        key, label, safe(value), "SCORE", score(value), null, comparison);
  }

  private AssistantChatResponse.Metric countMetric(
      String key,
      String label,
      long value,
      String comparison
  ) {
    return new AssistantChatResponse.Metric(
        key,
        label,
        BigDecimal.valueOf(value),
        "COUNT",
        value + "条",
        null,
        comparison
    );
  }

  private BigDecimal averageNet(List<ProfitEntryResponse> rows) {
    if (rows == null || rows.isEmpty()) return BigDecimal.ZERO;
    BigDecimal total = rows.stream().map(ProfitEntryResponse::net).map(this::safe).reduce(BigDecimal.ZERO, BigDecimal::add);
    return total.divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
  }

  private BigDecimal averageMargin(List<ProfitEntryResponse> rows) {
    if (rows == null || rows.isEmpty()) return BigDecimal.ZERO;
    BigDecimal total = rows.stream().map(ProfitEntryResponse::margin).map(this::safe)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return total.divide(BigDecimal.valueOf(rows.size()), 4, RoundingMode.HALF_UP);
  }

  private BigDecimal changeRate(BigDecimal current, BigDecimal previous, boolean previousAvailable) {
    if (!previousAvailable || safe(previous).compareTo(BigDecimal.ZERO) == 0) return null;
    return safe(current).subtract(safe(previous)).divide(safe(previous).abs(), 4, RoundingMode.HALF_UP);
  }

  private BigDecimal ratio(BigDecimal value, BigDecimal base) {
    if (safe(base).compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
    return safe(value).divide(safe(base), 4, RoundingMode.HALF_UP);
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String money(BigDecimal value) {
    return "¥" + safe(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private String percent(BigDecimal value) {
    return safe(value).multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
  }

  private String score(BigDecimal value) {
    return safe(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private String firstNonBlank(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first.trim();
  }

  private String joinNonBlank(String... values) {
    List<String> parts = new ArrayList<>();
    if (values != null) {
      for (String value : values) {
        if (value != null && !value.isBlank()) parts.add(value.trim());
      }
    }
    return String.join(" · ", parts);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("无法生成助手数据版本", ex);
    }
  }

  public record Result(
      AssistantChatResponse.LocalData localData,
      String modelContext,
      String dataVersion,
      String storeId,
      String storeName,
      String month,
      List<String> limitations,
      OperatingSnapshot snapshot
  ) {
    public Result {
      limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    /** Compatibility constructor for focused tests and integrations that predate snapshots. */
    public Result(
        AssistantChatResponse.LocalData localData,
        String modelContext,
        String dataVersion,
        String storeId,
        String storeName,
        String month,
        List<String> limitations
    ) {
      this(localData, modelContext, dataVersion, storeId, storeName, month, limitations, null);
    }
  }

  private record InspectionSummary(
      boolean serviceAvailable,
      boolean readAuthorized,
      int recordCount,
      BigDecimal averageScore,
      long failedCount,
      long redLineCount,
      String latestInspectionDate,
      String latestStoreName,
      BigDecimal latestScore,
      BigDecimal maxScore,
      BigDecimal passScore,
      boolean latestPassed,
      String latestResultCode
  ) {
    private boolean hasData() {
      return recordCount > 0;
    }

    private static InspectionSummary unavailable() {
      return new InspectionSummary(
          false, false, 0, BigDecimal.ZERO, 0, 0,
          null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, "UNAVAILABLE");
    }

    private static InspectionSummary unauthorized() {
      return new InspectionSummary(
          true, false, 0, BigDecimal.ZERO, 0, 0,
          null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, "UNAUTHORIZED");
    }

    private static InspectionSummary empty() {
      return new InspectionSummary(
          true, true, 0, BigDecimal.ZERO, 0, 0,
          null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, "NO_DATA");
    }
  }

  private record TrendPoint(
      String month,
      BigDecimal sales,
      BigDecimal income,
      BigDecimal net,
      BigDecimal margin
  ) {}

  private static final class Aggregate {
    private boolean hasData;
    private BigDecimal sales = BigDecimal.ZERO;
    private BigDecimal refund = BigDecimal.ZERO;
    private BigDecimal discount = BigDecimal.ZERO;
    private BigDecimal income = BigDecimal.ZERO;
    private BigDecimal cost = BigDecimal.ZERO;
    private BigDecimal gross = BigDecimal.ZERO;
    private BigDecimal expense = BigDecimal.ZERO;
    private BigDecimal net = BigDecimal.ZERO;
    private BigDecimal material = BigDecimal.ZERO;
    private BigDecimal packaging = BigDecimal.ZERO;
    private BigDecimal loss = BigDecimal.ZERO;
    private BigDecimal costOther = BigDecimal.ZERO;
    private BigDecimal rent = BigDecimal.ZERO;
    private BigDecimal labor = BigDecimal.ZERO;
    private BigDecimal utility = BigDecimal.ZERO;
    private BigDecimal property = BigDecimal.ZERO;
    private BigDecimal commission = BigDecimal.ZERO;
    private BigDecimal promo = BigDecimal.ZERO;
    private BigDecimal repair = BigDecimal.ZERO;
    private BigDecimal equipment = BigDecimal.ZERO;
    private BigDecimal expenseOther = BigDecimal.ZERO;

    private static Aggregate of(List<ProfitEntryResponse> rows) {
      Aggregate result = new Aggregate();
      if (rows == null) return result;
      for (ProfitEntryResponse row : rows) {
        result.hasData = true;
        result.sales = result.sales.add(orZero(row.sales()));
        result.refund = result.refund.add(orZero(row.refund()));
        result.discount = result.discount.add(orZero(row.discount()));
        result.income = result.income.add(orZero(row.income()));
        result.cost = result.cost.add(orZero(row.costSum()));
        result.gross = result.gross.add(orZero(row.gross()));
        result.expense = result.expense.add(orZero(row.expenseSum()));
        result.net = result.net.add(orZero(row.net()));
        result.material = result.material.add(orZero(row.material()));
        result.packaging = result.packaging.add(orZero(row.packaging()));
        result.loss = result.loss.add(orZero(row.loss()));
        result.costOther = result.costOther.add(orZero(row.costOther()));
        result.rent = result.rent.add(orZero(row.rent()));
        result.labor = result.labor.add(orZero(row.labor()));
        result.utility = result.utility.add(orZero(row.utility()));
        result.property = result.property.add(orZero(row.property()));
        result.commission = result.commission.add(orZero(row.commission()));
        result.promo = result.promo.add(orZero(row.promo()));
        result.repair = result.repair.add(orZero(row.repair()));
        result.equipment = result.equipment.add(orZero(row.equip()));
        result.expenseOther = result.expenseOther.add(orZero(row.expOther()));
      }
      return result;
    }

    private BigDecimal margin() {
      return income.compareTo(BigDecimal.ZERO) == 0
          ? BigDecimal.ZERO
          : net.divide(income, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
      return value == null ? BigDecimal.ZERO : value;
    }
  }
}
