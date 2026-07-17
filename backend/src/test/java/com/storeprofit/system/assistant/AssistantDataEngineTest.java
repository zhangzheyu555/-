package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.inspection.InspectionRecordResponse;
import com.storeprofit.system.inspection.InspectionService;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AssistantDataEngineTest {
  private final FinanceService financeService = mock(FinanceService.class);
  private final InspectionService inspectionService = mock(InspectionService.class);
  private final AssistantDataEngine engine = new AssistantDataEngine(
      financeService,
      inspectionService,
      null,
      null,
      Clock.fixed(Instant.parse("2026-07-16T03:00:00Z"), ZoneId.of("Asia/Shanghai"))
  );
  private final AuthUser boss = new AuthUser(
      1L, 1L, "测试企业", "boss", "hash", "老板", "BOSS", null, true
  );

  @Test
  void buildsStoreScopedMetricsComparisonsAndRecentTrend() {
    ProfitEntryResponse may = entry("rx13", "南阳三中店", "2026-05", "90000", "90000", "27000", "9000", "36000", "30000", "24000");
    ProfitEntryResponse june = entry("rx13", "南阳三中店", "2026-06", "100000", "100000", "30000", "10000", "40000", "40000", "20000");
    ProfitEntryResponse july = entry("rx13", "南阳三中店", "2026-07", "120000", "120000", "36000", "12000", "48000", "42000", "30000");
    ProfitEntryResponse other = entry("other", "其他门店", "2026-07", "80000", "80000", "20000", "8000", "30000", "30000", "20000");

    when(financeService.entries(any(), eq("2026-05"), isNull(), eq("rx13"))).thenReturn(List.of(may));
    when(financeService.entries(any(), eq("2026-06"), isNull(), eq("rx13"))).thenReturn(List.of(june));
    when(financeService.entries(any(), eq("2026-07"), isNull(), eq("rx13"))).thenReturn(List.of(july));
    when(financeService.entries(any(), eq("2025-07"), isNull(), eq("rx13"))).thenReturn(List.of());
    when(financeService.entries(any(), eq("2026-07"), isNull(), isNull())).thenReturn(List.of(july, other));
    when(financeService.entries(any(), eq("2026-07"), eq(1L), isNull())).thenReturn(List.of(july, other));
    when(inspectionService.records(any(), isNull(), isNull(), isNull(), eq("rx13"), isNull()))
        .thenReturn(List.of());

    AssistantDataEngine.Result result = engine.build(
        boss,
        new AssistantChatRequest("南阳三中店7月经营表现怎么样？", List.of(), "", "AUTO", "rx13", "2026-07"),
        "南阳三中店7月经营表现怎么样？"
    );

    assertThat(result.localData().dataScope()).isEqualTo("南阳三中店");
    assertThat(result.localData().source()).isEqualTo("MySQL 8 财务库");
    assertThat(metric(result, "materialRate").value()).isEqualByComparingTo("0.3000");
    assertThat(metric(result, "laborRate").value()).isEqualByComparingTo("0.1000");
    assertThat(metric(result, "material").value()).isEqualByComparingTo("36000");
    assertThat(metric(result, "labor").value()).isEqualByComparingTo("12000");
    assertThat(metric(result, "brandAverageMargin").value()).isNotNull();
    assertThat(metric(result, "allStoreAverageNet").value()).isEqualByComparingTo("25000");
    assertThat(metric(result, "net").changeRate()).isNull();
    assertThat(result.localData().metrics()).noneMatch(item -> item.key().startsWith("mom"));
    assertThat(result.snapshot().isMTD()).isTrue();
    assertThat(result.snapshot().capabilities().canCompare()).isFalse();
    assertThat(result.snapshot().comparisonBasis().explanation()).contains("不能与完整上月直接环比");
    assertThat(result.modelContext()).contains("2026-05", "2026-06", "2026-07");
    assertThat(result.modelContext()).doesNotContain("其他门店");
    assertThat(result.dataVersion()).hasSize(64);
    assertThat(result.localData().dataVersion()).isEqualTo(result.dataVersion());
    assertThat(result.localData().calculationVersion())
        .isEqualTo(AssistantDataEngine.CALCULATION_VERSION);
    assertThat(result.localData().updatedAt()).isNotNull();
    assertThat(result.limitations()).contains("当前授权范围内暂无巡检记录");
  }

  @Test
  void addsCanonicalInspectionMetricsWithoutReadingRawHundredPointValues() {
    ProfitEntryResponse july = entry(
        "rx13", "南阳三中店", "2026-07", "120000", "120000",
        "36000", "12000", "48000", "42000", "30000");
    when(financeService.entries(any(), eq("2026-07"), isNull(), eq("rx13")))
        .thenReturn(List.of(july));
    when(financeService.entries(any(), eq("2026-07"), isNull(), isNull()))
        .thenReturn(List.of(july));
    when(financeService.entries(any(), eq("2026-07"), eq(1L), isNull()))
        .thenReturn(List.of(july));
    when(inspectionService.records(any(), isNull(), isNull(), isNull(), eq("rx13"), isNull()))
        .thenReturn(List.of(
            inspection("legacy-82", "rx13", "南阳三中店", "2026-07-12", "100", "82", true, "[]"),
            inspection("legacy-98", "rx13", "南阳三中店", "2026-07-13", "100", "98", true, "[]")
        ));

    AssistantDataEngine.Result result = engine.build(
        boss,
        new AssistantChatRequest("南阳三中店7月巡检表现怎么样？", List.of(), "", "AUTO", "rx13", "2026-07"),
        "南阳三中店7月巡检表现怎么样？"
    );

    assertThat(metric(result, "inspectionLatestScore").value()).isEqualByComparingTo("196.00");
    assertThat(metric(result, "inspectionMaxScore").value()).isEqualByComparingTo("200.00");
    assertThat(metric(result, "inspectionPassScore").value()).isEqualByComparingTo("180.00");
    assertThat(metric(result, "inspectionLatestPassed").value()).isEqualByComparingTo("1");
    assertThat(metric(result, "inspectionLatestPassed").displayValue()).isEqualTo("合格");
    assertThat(metric(result, "inspectionLatestPassed").comparison()).isEqualTo("resultCode=PASSED");
    assertThat(metric(result, "inspectionAverageScore").value()).isEqualByComparingTo("180.00");
    assertThat(metric(result, "inspectionFailedCount").value()).isEqualByComparingTo("1");
    assertThat(metric(result, "inspectionRedLineCount").value()).isEqualByComparingTo("0");
    assertThat(result.modelContext()).contains(
        "score=196.00", "maxScore=200.00", "passScore=180.00",
        "passed=true", "resultCode=PASSED", "averageScore=180.00/200.00",
        "failedCount=1", "redLineCount=0");
    assertThat(result.modelContext()).doesNotContain("score=98.00", "score=82.00");
  }

  @Test
  void usesBoundStoreAndKeepsRedLineDecisionFromCanonicalBackendResult() {
    AuthUser manager = new AuthUser(
        2L, 1L, "测试企业", "rg1", "hash", "店长", "STORE_MANAGER", "rg1", true);
    ProfitEntryResponse july = entry(
        "rg1", "荆州之星店", "2026-07", "100000", "100000",
        "30000", "10000", "40000", "30000", "30000");
    when(financeService.entries(any(), eq("2026-07"), isNull(), eq("rg1")))
        .thenReturn(List.of(july));
    when(financeService.entries(any(), eq("2026-07"), isNull(), isNull()))
        .thenReturn(List.of(july));
    when(financeService.entries(any(), eq("2026-07"), eq(1L), isNull()))
        .thenReturn(List.of(july));
    when(inspectionService.records(eq(manager), isNull(), isNull(), isNull(), eq("rg1"), isNull()))
        .thenReturn(List.of(inspection(
            "legacy-red", "rg1", "荆州之星店", "2026-07-13", "100", "98", true,
            "[{\"code\":\"R1\"}]")));

    AssistantDataEngine.Result result = engine.build(
        manager,
        new AssistantChatRequest("本店7月巡检情况", List.of(), "", "AUTO", null, "2026-07"),
        "本店7月巡检情况"
    );

    verify(inspectionService).records(manager, null, null, null, "rg1", null);
    assertThat(metric(result, "inspectionLatestScore").value()).isEqualByComparingTo("196.00");
    assertThat(metric(result, "inspectionLatestPassed").displayValue()).isEqualTo("不合格");
    assertThat(metric(result, "inspectionFailedCount").value()).isEqualByComparingTo("1");
    assertThat(metric(result, "inspectionRedLineCount").value()).isEqualByComparingTo("1");
    assertThat(result.modelContext()).contains("passed=false", "resultCode=RED_LINE_FAILED");
  }

  @Test
  void propagatesInspectionCrossStoreDenialAsForbidden() {
    AuthUser manager = new AuthUser(
        2L, 1L, "测试企业", "rg1", "hash", "店长", "STORE_MANAGER", "rg1", true);
    when(financeService.months(any())).thenReturn(List.of());
    when(inspectionService.records(eq(manager), isNull(), isNull(), isNull(), eq("other"), isNull()))
        .thenThrow(new BusinessException("FORBIDDEN", "店长只能查看本店巡检记录", HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> engine.build(
        manager,
        new AssistantChatRequest("查看其他门店巡检", List.of(), "", "AUTO", "other", "2026-07"),
        "查看其他门店巡检"
    )).isInstanceOfSatisfying(BusinessException.class, error -> {
      assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(error.getCode()).isEqualTo("FORBIDDEN");
    });
  }

  @Test
  void keepsFinanceSnapshotWhenUserHasNoInspectionReadPermission() {
    AuthUser finance = new AuthUser(
        3L, 1L, "测试企业", "finance", "hash", "财务", "FINANCE", null, true);
    ProfitEntryResponse july = entry(
        "rx13", "南阳三中店", "2026-07", "120000", "120000",
        "36000", "12000", "48000", "42000", "30000");
    when(financeService.entries(any(), eq("2026-07"), isNull(), eq("rx13")))
        .thenReturn(List.of(july));
    when(financeService.entries(any(), eq("2026-07"), isNull(), isNull()))
        .thenReturn(List.of(july));
    when(financeService.entries(any(), eq("2026-07"), eq(1L), isNull()))
        .thenReturn(List.of(july));
    AccessControlService deniedAccessControl = mock(AccessControlService.class);
    when(deniedAccessControl.hasPermission(finance, PermissionCodes.INSPECTION_READ))
        .thenReturn(false);
    AssistantDataEngine permissionAwareEngine = new AssistantDataEngine(
        financeService, inspectionService, deniedAccessControl);

    AssistantDataEngine.Result result = permissionAwareEngine.build(
        finance,
        new AssistantChatRequest("南阳三中店7月营业额", List.of(), "", "AUTO", "rx13", "2026-07"),
        "南阳三中店7月营业额"
    );

    assertThat(metric(result, "sales").value()).isEqualByComparingTo("120000");
    assertThat(result.localData().metrics())
        .noneMatch(item -> item.key().startsWith("inspection"));
    assertThat(result.limitations()).contains("当前账号无巡检读取权限，经营快照未包含巡检数据");
    assertThat(result.modelContext()).contains("当前账号无巡检读取权限，未查询巡检数据");
    verifyNoInteractions(inspectionService);
  }

  private AssistantChatResponse.Metric metric(AssistantDataEngine.Result result, String key) {
    return result.localData().metrics().stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
  }

  private ProfitEntryResponse entry(
      String storeId,
      String storeName,
      String month,
      String sales,
      String income,
      String material,
      String labor,
      String cost,
      String expense,
      String net
  ) {
    BigDecimal incomeValue = new BigDecimal(income);
    BigDecimal costValue = new BigDecimal(cost);
    BigDecimal netValue = new BigDecimal(net);
    return new ProfitEntryResponse(
        1L, storeId, storeId, storeName, 1L, "测试品牌", "测试区域", "店长", month,
        new BigDecimal(sales), BigDecimal.ZERO, BigDecimal.ZERO, incomeValue,
        new BigDecimal(material), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        costValue, BigDecimal.ZERO, incomeValue.subtract(costValue), BigDecimal.ZERO,
        BigDecimal.ZERO, new BigDecimal(labor), BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        new BigDecimal(expense), netValue,
        netValue.divide(incomeValue, 4, java.math.RoundingMode.HALF_UP), "健康", ""
    );
  }

  private InspectionRecordResponse inspection(
      String id,
      String storeId,
      String storeName,
      String date,
      String fullScore,
      String score,
      boolean passed,
      String redlinesJson
  ) {
    return new InspectionRecordResponse(
        id, storeId, storeId, storeName, 1L, "测试品牌", date, "测试督导", "测试品牌",
        new BigDecimal(fullScore), new BigDecimal(score), passed,
        "[]", redlinesJson, "[]", ""
    );
  }
}
