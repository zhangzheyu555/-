package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssistantSnapshotServiceTest {
  private final AuthUser boss = new AuthUser(
      1L, 1L, "测试租户", "boss", "hash", "老板", "BOSS", null, true
  );

  @Test
  void dataInsufficiencyStillInvokesTheModelWithDataLimitedContract() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String content = mapper.writeValueAsString(Map.of(
        "analysisType", "DATA_LIMITED",
        "summary", "\u5f53\u524d\u6570\u636e\u4e0d\u8db3\uff0c\u5df2\u8bf7\u6c42\u6a21\u578b\u8f93\u51fa\u8865\u6570\u5efa\u8bae\u3002",
        "findings", List.of("\u5feb\u7167\u5df2\u6709\u7ecf\u8425\u91d1\u989d\uff0c\u4f46\u4e0d\u8db3\u4ee5\u505a\u539f\u56e0\u5f52\u56e0\u3002"),
        "risks", List.of(),
        "possibleCauses", List.of(),
        "actions", List.of(Map.of(
            "action", "\u8865\u5168\u7ecf\u8425\u6570\u636e",
            "ownerRole", "FINANCE",
            "deadline", "\u672c\u5468\u4e94",
            "expectedImpact", "\u5f62\u6210\u53ef\u5206\u6790\u7684\u5b8c\u6574\u5feb\u7167",
            "verificationMetric", "\u7ecf\u8425\u6570\u636e\u5b8c\u6574\u7387"
        )),
        "limitations", List.of("\u7ecf\u8425\u6570\u636e\u4e0d\u8db3"),
        "confidence", "LOW"
    ));
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("configured-test-only-key");
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    DeepSeekClient client = mock(DeepSeekClient.class);
    when(client.analyze(any(), any(), any(Duration.class))).thenReturn(
        new DeepSeekCallResult(content, "provider-request", "deepseek-test-model", 200, 1)
    );
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult(snapshot(false)));
    AssistantService service = new AssistantService(properties, dataEngine, client, new ObjectMapper());

    AssistantChatResponse response = service.chat(
        boss,
        new AssistantChatRequest("7月净利润为什么变化", List.of(), "", "AI", "s1", "2026-07")
    );

    assertThat(response.selectedMode()).isEqualTo("AI");
    assertThat(response.localData().snapshotId()).isEqualTo("snapshot-data-limited");
    assertThat(response.localData().operatingSnapshot()).isNotNull();
    assertThat(response.localData().aiInvocation()).isEqualTo("LIVE");
    assertThat(response.localData().insufficientData()).isNull();
    assertThat(response.aiAnalysis().available()).isTrue();
    assertThat(response.aiAnalysis().analysisType()).isEqualTo("DATA_LIMITED");
    assertThat(response.aiAnalysis().possibleCauses()).isEmpty();
    verify(client, times(1)).analyze(any(), any(), any(Duration.class));
  }

  @Test
  void localDataQueryCarriesTheSameSnapshotIdWithoutClaimingModelUse() {
    DeepSeekProperties properties = new DeepSeekProperties();
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    DeepSeekClient client = mock(DeepSeekClient.class);
    OperatingSnapshot snapshot = snapshot(true);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult(snapshot));
    AssistantService service = new AssistantService(properties, dataEngine, client, new ObjectMapper());

    AssistantChatResponse response = service.chat(
        boss,
        new AssistantChatRequest("7月营业额是多少", List.of(), "", "LOCAL", "s1", "2026-07")
    );

    assertThat(response.selectedMode()).isEqualTo("LOCAL");
    assertThat(response.localData().snapshotId()).isEqualTo(snapshot.snapshotId());
    assertThat(response.localData().operatingSnapshot()).isSameAs(snapshot);
    assertThat(response.localData().aiInvocation()).isEqualTo("NOT_REQUESTED");
    verifyNoInteractions(client);
  }

  @Test
  void snapshotFromAnotherTenantIsRejectedWithoutReadingOrSendingItsFacts() {
    DeepSeekProperties properties = new DeepSeekProperties();
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    DeepSeekClient client = mock(DeepSeekClient.class);
    OperatingSnapshot snapshot = snapshot(true);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult(snapshot));
    AssistantService service = new AssistantService(properties, dataEngine, client, new ObjectMapper());
    AuthUser anotherTenantBoss = new AuthUser(
        2L, 2L, "另一测试租户", "other-boss", "hash", "另一位老板", "BOSS", null, true
    );

    service.operatingSnapshot(boss, "s1", "2026-07");
    AssistantChatResponse response = service.chat(
        anotherTenantBoss,
        new AssistantChatRequest("7月营业额是多少", List.of(), "", "LOCAL", "s1", "2026-07", snapshot.snapshotId())
    );

    assertThat(response.error()).isNotNull();
    assertThat(response.error().code()).isEqualTo("SNAPSHOT_EXPIRED");
    assertThat(response.localData().snapshotId()).isBlank();
    assertThat(response.localData().operatingSnapshot()).isNull();
    verifyNoInteractions(client);
  }

  private AssistantDataEngine.Result dataResult(OperatingSnapshot snapshot) {
    AssistantChatResponse.LocalData localData = new AssistantChatResponse.LocalData(
        "测试门店 2026-07：实收收入¥100.00，经营利润¥28.00，经营利润率28.0%。",
        List.of(
            new AssistantChatResponse.Metric("revenue", "实收收入", new BigDecimal("100"), "CNY", "¥100.00", null, ""),
            new AssistantChatResponse.Metric("net", "经营利润", new BigDecimal("28"), "CNY", "¥28.00", null, ""),
            new AssistantChatResponse.Metric("margin", "经营利润率", new BigDecimal("0.28"), "PERCENT", "28.0%", null, "")
        ),
        "2026-07", "测试门店", "MySQL 8 财务库", "data-version", AssistantDataEngine.CALCULATION_VERSION, Instant.parse("2026-07-16T00:00:00Z")
    );
    return new AssistantDataEngine.Result(
        localData, "仅含当前快照事实", "data-version", "s1", "测试门店", "2026-07", List.of(), snapshot
    );
  }

  private OperatingSnapshot snapshot(boolean canUseAi) {
    OperatingSnapshot.StoreScope scope = new OperatingSnapshot.StoreScope("测试门店", List.of("s1"), List.of("测试门店"));
    OperatingSnapshot.StoreCoverage coverage = new OperatingSnapshot.StoreCoverage(
        1, 1, List.of(), List.of(), List.of(), false, BigDecimal.ONE
    );
    OperatingSnapshot.ProfitBridge bridge = new OperatingSnapshot.ProfitBridge(
        "MONTHLY_OPERATING_PROFIT_PRE_TAX", new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
        new BigDecimal("100"), new BigDecimal("30"), new BigDecimal("42"), null, null, BigDecimal.ZERO, new BigDecimal("28")
    );
    return new OperatingSnapshot(
        canUseAi ? "snapshot-ready" : "snapshot-data-limited",
        Instant.parse("2026-07-16T00:00:00Z"), null,
        LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-16"), true,
        scope, coverage, new BigDecimal("100"), new BigDecimal("30"), new BigDecimal("42"), null, null,
        new BigDecimal("28"), new BigDecimal("0.28"),
        new OperatingSnapshot.PreviousComparablePeriod(false, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"), List.of("s1"), "不可直接环比"),
        OperatingSnapshot.ComparisonBasis.unavailable("不可直接环比"),
        bridge,
        new OperatingSnapshot.Capabilities(true, false, canUseAi, canUseAi),
        new OperatingSnapshot.DataQuality(canUseAi ? "COMPLETE" : "PARTIAL", List.of("日级截止日期未记录"), false, false),
        canUseAi ? List.of() : List.of("businessAsOf"),
        "source-v1"
    );
  }
}
