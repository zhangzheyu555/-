package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperatingSnapshotContractTest {
  private static final Clock JULY_16 = Clock.fixed(
      Instant.parse("2026-07-16T03:00:00Z"), ZoneId.of("Asia/Shanghai")
  );
  private final AuthUser boss = new AuthUser(
      1L, 1L, "测试租户", "boss", "hash", "老板", "BOSS", null, true
  );

  @Test
  void derivesProfitAndMarginFromOneStableSnapshotAndKeepsTheBridgeIdentity() {
    FinanceService finance = mock(FinanceService.class);
    OrganizationRepository organizations = mock(OrganizationRepository.class);
    AccessControlService access = financeAccess();
    ProfitEntryResponse january = entry("s1", "一店", "2026-01", "100", "30", "42", "28");
    ProfitEntryResponse december = entry("s1", "一店", "2025-12", "100", "30", "42", "28");
    when(finance.entries(eq(boss), eq("2026-01"), isNull(), isNull())).thenReturn(List.of(january));
    when(finance.entries(eq(boss), eq("2025-12"), isNull(), isNull())).thenReturn(List.of(december));
    when(organizations.stores(eq(1L), any(DataScope.class))).thenReturn(List.of(store("s1", "一店")));

    AssistantDataEngine engine = new AssistantDataEngine(finance, null, access, organizations, JULY_16);
    AssistantDataEngine.Result first = engine.build(boss, request("2026-01"), "查看 1 月经营利润");
    AssistantDataEngine.Result second = engine.build(boss, request("2026-01"), "查看 1 月经营利润");
    OperatingSnapshot snapshot = first.snapshot();

    assertThat(snapshot.snapshotId()).isEqualTo(second.snapshot().snapshotId());
    assertThat(snapshot.revenue()).isEqualByComparingTo("100");
    assertThat(snapshot.costOfSales()).isEqualByComparingTo("30");
    assertThat(snapshot.operatingExpense()).isEqualByComparingTo("42");
    assertThat(snapshot.netProfit()).isEqualByComparingTo("28");
    assertThat(snapshot.netMargin()).isEqualByComparingTo("0.2800");
    assertThat(snapshot.netProfit()).isEqualByComparingTo(snapshot.profitBridge().revenue()
        .subtract(snapshot.profitBridge().costOfSales())
        .subtract(snapshot.profitBridge().operatingExpense())
        .add(snapshot.profitBridge().unclassifiedDifference()));
    assertThat(snapshot.profitBridge().otherIncomeExpense()).isNull();
    assertThat(snapshot.profitBridge().tax()).isNull();
    assertThat(snapshot.previousComparablePeriod().available()).isFalse();
    assertThat(snapshot.capabilities().canCompare()).isFalse();
    assertThat(snapshot.comparisonBasis().sameOperatingDays()).isFalse();
    assertThat(snapshot.previousComparablePeriod().netMargin()).isNull();
    assertThat(snapshot.missingFields()).contains("dailyOperatingCoverage");
  }

  @Test
  void missingExpenseDetailLimitsAttributionButDoesNotHideVerifiedKpi() {
    FinanceService finance = mock(FinanceService.class);
    OrganizationRepository organizations = mock(OrganizationRepository.class);
    AccessControlService access = financeAccess();
    ProfitEntryResponse january = entry("s1", "一店", "2026-01", "100", "20", "0", "80");
    ProfitEntryResponse december = entry("s1", "一店", "2025-12", "100", "20", "0", "80");
    when(finance.entries(eq(boss), eq("2026-01"), isNull(), isNull())).thenReturn(List.of(january));
    when(finance.entries(eq(boss), eq("2025-12"), isNull(), isNull())).thenReturn(List.of(december));
    when(organizations.stores(eq(1L), any(DataScope.class))).thenReturn(List.of(store("s1", "一店")));

    OperatingSnapshot snapshot = new AssistantDataEngine(finance, null, access, organizations, JULY_16)
        .build(boss, request("2026-01"), "查看 1 月经营利润").snapshot();

    assertThat(snapshot.capabilities().canComputeKPI()).isTrue();
    assertThat(snapshot.capabilities().canCompare()).isFalse();
    assertThat(snapshot.capabilities().canAttributeCause()).isFalse();
    assertThat(snapshot.capabilities().canUseAI()).isFalse();
    assertThat(snapshot.dataQuality().level()).isEqualTo("PARTIAL");
    assertThat(snapshot.missingFields()).contains("operatingExpenseDetail");
    assertThat(snapshot.netProfit()).isEqualByComparingTo("80");
  }

  @Test
  void doesNotInferExpectedStoresFromProfitRowsWhenTheOrganizationSourceIsAvailable() {
    FinanceService finance = mock(FinanceService.class);
    OrganizationRepository organizations = mock(OrganizationRepository.class);
    AccessControlService access = financeAccess();
    ProfitEntryResponse january = entry("s1", "一店", "2026-01", "100", "30", "42", "28");
    ProfitEntryResponse december = entry("s1", "一店", "2025-12", "100", "30", "42", "28");
    when(finance.entries(eq(boss), eq("2026-01"), isNull(), isNull())).thenReturn(List.of(january));
    when(finance.entries(eq(boss), eq("2025-12"), isNull(), isNull())).thenReturn(List.of(december));
    when(organizations.stores(eq(1L), any(DataScope.class))).thenReturn(List.of());

    OperatingSnapshot snapshot = new AssistantDataEngine(finance, null, access, organizations, JULY_16)
        .build(boss, request("2026-01"), "查看 1 月经营快照").snapshot();

    assertThat(snapshot.capabilities().canComputeKPI()).isTrue();
    assertThat(snapshot.storeScope().storeIds()).isEmpty();
    assertThat(snapshot.storeCoverage().expectedStoreCount()).isZero();
    assertThat(snapshot.storeCoverage().reportedStoreCount()).isEqualTo(1);
    assertThat(snapshot.missingFields()).contains("expectedStoreReportingRule");
    assertThat(snapshot.capabilities().canCompare()).isFalse();
  }

  @Test
  void blocksJulyMtdAgainstFullJuneAndDisclosesCoverageAndUnknownBusinessCutoff() {
    FinanceService finance = mock(FinanceService.class);
    OrganizationRepository organizations = mock(OrganizationRepository.class);
    AccessControlService access = financeAccess();
    ProfitEntryResponse july = entry("s1", "一店", "2026-07", "100", "30", "42", "28");
    ProfitEntryResponse june = entry("s1", "一店", "2026-06", "100", "30", "42", "28");
    when(finance.entries(eq(boss), eq("2026-07"), isNull(), isNull())).thenReturn(List.of(july));
    when(finance.entries(eq(boss), eq("2026-06"), isNull(), isNull())).thenReturn(List.of(june));
    when(organizations.stores(eq(1L), any(DataScope.class))).thenReturn(List.of(
        store("s1", "一店"), store("s2", "二店")
    ));

    OperatingSnapshot snapshot = new AssistantDataEngine(finance, null, access, organizations, JULY_16)
        .build(boss, request("2026-07"), "查看 7 月经营利润").snapshot();

    assertThat(snapshot.isMTD()).isTrue();
    assertThat(snapshot.asOf()).isNull();
    assertThat(snapshot.periodEnd().toString()).isEqualTo("2026-07-16");
    assertThat(snapshot.dataQuality().asOfKnown()).isFalse();
    assertThat(snapshot.storeCoverage().expectedStoreCount()).isEqualTo(2);
    assertThat(snapshot.storeCoverage().reportedStoreCount()).isEqualTo(1);
    assertThat(snapshot.storeCoverage().missingStoreIds()).containsExactly("s2");
    assertThat(snapshot.storeCoverage().coverageRate()).isEqualByComparingTo("0.5000");
    assertThat(snapshot.capabilities().canCompare()).isFalse();
    assertThat(snapshot.previousComparablePeriod().available()).isFalse();
    assertThat(snapshot.comparisonBasis().explanation()).contains("不能与完整上月直接环比");
    assertThat(snapshot.missingFields()).contains("businessAsOf", "dailyOperatingCoverage");
  }

  private AccessControlService financeAccess() {
    AccessControlService access = mock(AccessControlService.class);
    when(access.dataScope(eq(boss), eq(DataScopeDomains.FINANCE))).thenReturn(DataScope.all());
    return access;
  }

  private AssistantChatRequest request(String month) {
    return new AssistantChatRequest("查询经营快照", List.of(), "", "LOCAL", null, month);
  }

  private StoreResponse store(String id, String name) {
    return new StoreResponse(id, id.toUpperCase(), name, 1L, "测试品牌", "测试区域", "店长", "2020-01-01", "ACTIVE", "");
  }

  private ProfitEntryResponse entry(
      String storeId,
      String storeName,
      String month,
      String income,
      String cost,
      String expense,
      String net
  ) {
    BigDecimal incomeValue = new BigDecimal(income);
    BigDecimal costValue = new BigDecimal(cost);
    BigDecimal expenseValue = new BigDecimal(expense);
    BigDecimal netValue = new BigDecimal(net);
    return new ProfitEntryResponse(
        1L, storeId, storeId, storeName, 1L, "测试品牌", "测试区域", "店长", month,
        incomeValue, BigDecimal.ZERO, BigDecimal.ZERO, incomeValue,
        costValue, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        costValue, BigDecimal.ZERO, incomeValue.subtract(costValue), BigDecimal.ZERO,
        BigDecimal.ZERO, expenseValue, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        expenseValue, netValue,
        netValue.divide(incomeValue, 4, RoundingMode.HALF_UP), "健康", ""
    );
  }
}
