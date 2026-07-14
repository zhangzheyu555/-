package com.storeprofit.system.assistant;

import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.inspection.InspectionRecordResponse;
import com.storeprofit.system.inspection.InspectionService;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Builds permission-filtered, non-sensitive operating metrics from MySQL. */
@Component
public class AssistantDataEngine {
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  public static final String CALCULATION_VERSION = "operating-snapshot-v4-inspection-200";
  private final FinanceService financeService;
  private final InspectionService inspectionService;
  private final AccessControlService accessControl;

  @Autowired
  public AssistantDataEngine(
      FinanceService financeService,
      InspectionService inspectionService,
      AccessControlService accessControl
  ) {
    this.financeService = financeService;
    this.inspectionService = inspectionService;
    this.accessControl = accessControl;
  }

  /** Compatibility constructor retained for focused tests that provide inspection data. */
  public AssistantDataEngine(FinanceService financeService, InspectionService inspectionService) {
    this(financeService, inspectionService, null);
  }

  /** Compatibility constructor retained for focused tests. */
  public AssistantDataEngine(FinanceService financeService) {
    this(financeService, null, null);
  }

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
    Aggregate previous = aggregateFor(user, selected.minusMonths(1).toString(), storeId);
    Aggregate previousYear = aggregateFor(user, selected.minusYears(1).toString(), storeId);

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
        current.net,
        changeRate(current.net, previous.net, previous.hasData),
        previous.hasData ? "较上月" : "暂无上月数据"
    ));
    metrics.add(percentMetric("margin", "净利率", current.margin(), null, ""));
    metrics.add(percentMetric("materialRate", "原材料成本率", ratio(current.material, current.income), null, ""));
    metrics.add(percentMetric("laborRate", "人工成本率", ratio(current.labor, current.income), null, ""));
    metrics.add(percentMetric("expenseRate", "费用率", ratio(current.expense, current.income), null, ""));

    if (previous.hasData) {
      metrics.add(percentMetric("momSalesChange", "营业额环比", changeRate(current.sales, previous.sales, true), null, ""));
      metrics.add(percentMetric("momNetChange", "净利润环比", changeRate(current.net, previous.net, true), null, ""));
    }
    if (previousYear.hasData) {
      metrics.add(percentMetric("yoySalesChange", "营业额同比", changeRate(current.sales, previousYear.sales, true), null, ""));
      metrics.add(percentMetric("yoyNetChange", "净利润同比", changeRate(current.net, previousYear.net, true), null, ""));
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
    if (!previous.hasData) limitations.add("缺少上月数据，无法计算环比");
    if (!previousYear.hasData) limitations.add("缺少去年同期数据，无法计算同比");
    if (trend.size() < 3) limitations.add("近三个月数据不足，趋势判断可信度受限");

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

    String summary = localSummary(dataScope, month, current);
    Instant updatedAt = Instant.now();
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
    String modelContext = modelContext(preliminary, trend, anomalies, limitations, inspectionSummary);
    String dataVersion = sha256(user.tenantId() + "|" + storeId + "|" + month + "|" + modelContext);
    AssistantChatResponse.LocalData localData = new AssistantChatResponse.LocalData(
        summary, metrics, month, dataScope, "MySQL 8 财务库", dataVersion,
        CALCULATION_VERSION, updatedAt
    );
    return new Result(localData, modelContext, dataVersion, storeId, storeName, month, limitations);
  }

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

  private String localSummary(String scope, String month, Aggregate aggregate) {
    if (!aggregate.hasData) return scope + "在" + month + "暂无经营数据。";
    return scope + " " + month + "：营业额" + money(aggregate.sales)
        + "，成本" + money(aggregate.cost)
        + "，费用" + money(aggregate.expense)
        + "，净利润" + money(aggregate.net)
        + "，净利率" + percent(aggregate.margin()) + "。";
  }

  private String modelContext(
      AssistantChatResponse.LocalData localData,
      List<TrendPoint> trend,
      List<String> anomalies,
      List<String> limitations,
      InspectionSummary inspectionSummary
  ) {
    StringBuilder value = new StringBuilder();
    value.append("数据期间：").append(localData.dataPeriod()).append('\n');
    value.append("数据范围：").append(localData.dataScope()).append('\n');
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
      List<String> limitations
  ) {
    public Result {
      limitations = limitations == null ? List.of() : List.copyOf(limitations);
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
