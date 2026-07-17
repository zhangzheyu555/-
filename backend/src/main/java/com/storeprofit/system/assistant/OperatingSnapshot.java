package com.storeprofit.system.assistant;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Immutable, read-only operating facts used by every surface of the store assistant.
 *
 * <p>All financial amounts are calculated with {@link BigDecimal} on the server and serialized
 * as decimal strings. The browser may format these values, but must not recalculate them.</p>
 */
public record OperatingSnapshot(
    String snapshotId,
    Instant generatedAt,
    LocalDate asOf,
    LocalDate periodStart,
    LocalDate periodEnd,
    boolean isMTD,
    StoreScope storeScope,
    StoreCoverage storeCoverage,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal revenue,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal costOfSales,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal operatingExpense,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal otherIncomeExpense,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal tax,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netProfit,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netMargin,
    PreviousComparablePeriod previousComparablePeriod,
    ComparisonBasis comparisonBasis,
    ProfitBridge profitBridge,
    Capabilities capabilities,
    DataQuality dataQuality,
    List<String> missingFields,
    String dataSourceVersion
) {
  public OperatingSnapshot {
    snapshotId = safe(snapshotId);
    generatedAt = generatedAt == null ? Instant.EPOCH : generatedAt;
    storeScope = storeScope == null ? StoreScope.empty() : storeScope;
    storeCoverage = storeCoverage == null ? StoreCoverage.unknown() : storeCoverage;
    revenue = amount(revenue);
    costOfSales = amount(costOfSales);
    operatingExpense = amount(operatingExpense);
    netProfit = amount(netProfit);
    netMargin = amount(netMargin);
    comparisonBasis = comparisonBasis == null ? ComparisonBasis.unavailable("") : comparisonBasis;
    profitBridge = profitBridge == null
        ? ProfitBridge.of(revenue, costOfSales, operatingExpense, otherIncomeExpense, tax, BigDecimal.ZERO, netProfit)
        : profitBridge;
    capabilities = capabilities == null ? Capabilities.none() : capabilities;
    dataQuality = dataQuality == null ? DataQuality.insufficient(List.of()) : dataQuality;
    missingFields = immutable(missingFields);
    dataSourceVersion = safe(dataSourceVersion);
  }

  public record StoreScope(String label, List<String> storeIds, List<String> storeNames) {
    public StoreScope {
      label = safe(label);
      storeIds = immutable(storeIds);
      storeNames = immutable(storeNames);
    }

    static StoreScope empty() {
      return new StoreScope("", List.of(), List.of());
    }
  }

  /** Missing dates are intentionally empty when the source has no daily business-date facts. */
  public record StoreCoverage(
      int expectedStoreCount,
      int reportedStoreCount,
      List<String> missingStoreIds,
      List<String> missingStoreNames,
      List<String> missingDates,
      boolean missingDatesKnown,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal coverageRate
  ) {
    public StoreCoverage {
      expectedStoreCount = Math.max(0, expectedStoreCount);
      reportedStoreCount = Math.max(0, reportedStoreCount);
      missingStoreIds = immutable(missingStoreIds);
      missingStoreNames = immutable(missingStoreNames);
      missingDates = immutable(missingDates);
      coverageRate = amount(coverageRate);
    }

    static StoreCoverage unknown() {
      return new StoreCoverage(0, 0, List.of(), List.of(), List.of(), false, BigDecimal.ZERO);
    }
  }

  public record PreviousComparablePeriod(
      boolean available,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<String> storeIds,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal revenue,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netProfit,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netMargin,
      String unavailableReason
  ) {
    public PreviousComparablePeriod {
      storeIds = immutable(storeIds);
      unavailableReason = safe(unavailableReason);
    }

    /** Compatibility constructor for callers that only need the comparable-period identity. */
    public PreviousComparablePeriod(
        boolean available,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<String> storeIds,
        String unavailableReason
    ) {
      this(available, periodStart, periodEnd, storeIds, null, null, null, unavailableReason);
    }
  }

  public record ComparisonBasis(
      boolean available,
      boolean sameStoreScope,
      boolean sameAccountingBasis,
      boolean sameOperatingDays,
      boolean sameDayCount,
      String explanation
  ) {
    public ComparisonBasis {
      explanation = safe(explanation);
    }

    static ComparisonBasis unavailable(String explanation) {
      return new ComparisonBasis(false, false, false, false, false, explanation);
    }
  }

  /** Explicit bridge prevents costs, expenses and unavailable statutory fields from being conflated. */
  public record ProfitBridge(
      String accountingScope,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossSales,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refunds,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal discounts,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal revenue,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal costOfSales,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal operatingExpense,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal otherIncomeExpense,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal tax,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unclassifiedDifference,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netProfit
  ) {
    public ProfitBridge {
      accountingScope = safe(accountingScope);
      grossSales = amount(grossSales);
      refunds = amount(refunds);
      discounts = amount(discounts);
      revenue = amount(revenue);
      costOfSales = amount(costOfSales);
      operatingExpense = amount(operatingExpense);
      unclassifiedDifference = amount(unclassifiedDifference);
      netProfit = amount(netProfit);
    }

    static ProfitBridge of(
        BigDecimal revenue,
        BigDecimal costOfSales,
        BigDecimal operatingExpense,
        BigDecimal otherIncomeExpense,
        BigDecimal tax,
        BigDecimal unclassifiedDifference,
        BigDecimal netProfit
    ) {
      return new ProfitBridge(
          "MONTHLY_OPERATING_PROFIT_PRE_TAX",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          revenue,
          costOfSales,
          operatingExpense,
          otherIncomeExpense,
          tax,
          unclassifiedDifference,
          netProfit
      );
    }
  }

  public record Capabilities(
      boolean canComputeKPI,
      boolean canCompare,
      boolean canAttributeCause,
      boolean canUseAI
  ) {
    static Capabilities none() {
      return new Capabilities(false, false, false, false);
    }
  }

  public record DataQuality(
      String level,
      List<String> notices,
      boolean dailyCoverageKnown,
      boolean asOfKnown
  ) {
    public DataQuality {
      level = safe(level).isBlank() ? "INSUFFICIENT" : safe(level).toUpperCase();
      notices = immutable(notices);
    }

    /** Compatibility constructor for callers written before the explicit as-of disclosure. */
    public DataQuality(String level, List<String> notices, boolean dailyCoverageKnown) {
      this(level, notices, dailyCoverageKnown, false);
    }

    static DataQuality insufficient(List<String> notices) {
      return new DataQuality("INSUFFICIENT", notices, false, false);
    }
  }

  private static BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }
}
