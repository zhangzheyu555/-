package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class AssistantAnalysisPipelineTest {
  private HttpServer server;
  private final AuthUser boss = new AuthUser(
      1L, 1L, "测试企业", "boss", "hash", "老板", "BOSS", null, true
  );

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void autoRoutesFactsLocallyAndAnalysisToAi() {
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("");
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult());
    AssistantService service = new AssistantService(properties, mock(FinanceService.class), dataEngine);

    AssistantChatResponse fact = service.chat(boss, request("7月营业额是多少"));
    AssistantChatResponse analysis = service.chat(boss, request("南阳三中店7月经营表现怎么样？"));
    AssistantChatResponse why = service.chat(boss, request("7月净利润为什么变化"));

    assertThat(fact.error()).isNull();
    assertThat(fact.selectedMode()).isEqualTo("LOCAL");
    assertThat(fact.selectionReason()).contains("事实查询");
    assertThat(fact.fallbackUsed()).isFalse();
    assertThat(analysis.selectedMode()).isEqualTo("AI");
    assertThat(analysis.error().code()).isEqualTo("DEEPSEEK_NOT_CONFIGURED");
    assertThat(why.error().code()).isEqualTo("DEEPSEEK_NOT_CONFIGURED");
    assertThat(analysis.aiAnalysis().summary()).isEmpty();

    AssistantChatResponse amount = service.chat(boss, request("7月金额"));
    AssistantChatResponse cost = service.chat(boss, request("7月成本"));
    AssistantChatResponse profit = service.chat(boss, request("7月利润"));
    assertThat(amount.selectedMode()).isEqualTo("LOCAL");
    assertThat(cost.selectedMode()).isEqualTo("LOCAL");
    assertThat(profit.selectedMode()).isEqualTo("LOCAL");
  }

  @Test
  void exposesRealProviderMetadataAndCachesQualifiedAnalysis() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ObjectMapper mapper = new ObjectMapper();
    String content = mapper.writeValueAsString(Map.of(
        "analysisType", "FULL",
        "summary", "利润改善空间集中在费用结构与执行节奏。",
        "findings", List.of("费用结构需要优先复核"),
        "risks", List.of(Map.of(
            "title", "费用结构风险", "evidence", "费用率25.0%", "severity", "MEDIUM"
        )),
        "possibleCauses", List.of(Map.of(
            "cause", "排班与促销节奏可能不匹配", "confidence", "LOW", "basis", "人工成本结构待复核"
        )),
        "actions", List.of(
            Map.of("action", "核对费用明细", "ownerRole", "FINANCE", "deadline", "本周三",
                "expectedImpact", "降低费用波动", "verificationMetric", "费用率"),
            Map.of("action", "复盘排班效率", "ownerRole", "STORE_MANAGER", "deadline", "本周五",
                "expectedImpact", "提高排班效率", "verificationMetric", "人工成本率"),
            Map.of("action", "跟踪改善结果", "ownerRole", "BOSS", "deadline", "本周日",
                "expectedImpact", "稳定盈利水平", "verificationMetric", "净利率")
        ),
        "confidence", "MEDIUM",
        "limitations", List.of("缺少客流数据")
    ));
    String response = mapper.writeValueAsString(Map.of(
        "id", "provider-request-1",
        "model", "deepseek-test-model",
        "choices", List.of(Map.of("message", Map.of("content", content)))
    ));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      calls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();

    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    properties.setModel("configured-model");
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setConnectTimeout(Duration.ofSeconds(1));
    properties.setTimeout(Duration.ofSeconds(2));
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult());
    AssistantService service = new AssistantService(properties, mock(FinanceService.class), dataEngine);

    AssistantChatResponse first = service.chat(boss, request("7月净利润为什么变化"));
    AssistantChatResponse second = service.chat(boss, request("7月净利润为什么变化"));

    assertThat(first.aiAnalysis().available()).isTrue();
    assertThat(first.aiAnalysis().provider()).isEqualTo("DeepSeek");
    assertThat(first.aiAnalysis().model()).isEqualTo("deepseek-test-model");
    assertThat(first.aiAnalysis().requestId()).isEqualTo("provider-request-1");
    assertThat(first.aiAnalysis().actions()).hasSize(3);
    assertThat(first.aiAnalysis().actions().getFirst().ownerRole()).isEqualTo("FINANCE");
    assertThat(first.aiAnalysis().summary()).isNotEqualTo(first.localData().summary());
    assertThat(second.aiAnalysis()).isEqualTo(first.aiAnalysis());
    assertThat(calls).hasValue(1);
  }

  @Test
  void rejectsLowQualityDuplicateAfterOneQualityRetry() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ObjectMapper mapper = new ObjectMapper();
    String lowQuality = mapper.writeValueAsString(Map.of(
        "analysisType", "FULL",
        "summary", "南阳三中店 2026-07：营业额¥120000.00，净利润¥30000.00，净利率25.0%。",
        "findings", List.of(), "risks", List.of(), "possibleCauses", List.of(),
        "actions", List.of(), "confidence", "LOW", "limitations", List.of()
    ));
    String response = mapper.writeValueAsString(Map.of(
        "id", "low-quality", "model", "deepseek-test-model",
        "choices", List.of(Map.of("message", Map.of("content", lowQuality)))
    ));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      calls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();

    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setTimeout(Duration.ofSeconds(2));
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult());
    AssistantService service = new AssistantService(properties, mock(FinanceService.class), dataEngine);

    AssistantChatResponse result = service.chat(boss, request("7月净利润为什么变化"));

    assertThat(calls).hasValue(2);
    assertThat(result.aiAnalysis().available()).isFalse();
    assertThat(result.aiAnalysis().summary()).isEmpty();
    assertThat(result.error().code()).isEqualTo("ANALYSIS_QUALITY_REJECTED");
    assertThat(result.error().message()).isEqualTo("模型结果未通过必要的完整性校验，未展示不可靠结论，请稍后重新分析。");
  }

  @Test
  void appliesTheRemainingAnalysisBudgetToTheSingleSchemaRepair() throws Exception {
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    properties.setAnalysisTimeout(Duration.ofSeconds(2));
    DeepSeekClient client = mock(DeepSeekClient.class);
    AtomicInteger calls = new AtomicInteger();
    when(client.analyze(any(), any(), any(Duration.class))).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) Thread.sleep(25);
      return new DeepSeekCallResult("not-json", "request", "test-model", 200, 1);
    });
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult());
    AssistantService service = new AssistantService(properties, dataEngine, client, new ObjectMapper());

    AssistantChatResponse result = service.chat(boss, request("7月净利润为什么变化"));

    ArgumentCaptor<Duration> budgets = ArgumentCaptor.forClass(Duration.class);
    verify(client, times(2)).analyze(any(), any(), budgets.capture());
    assertThat(calls).hasValue(2);
    assertThat(budgets.getAllValues()).hasSize(2);
    assertThat(budgets.getAllValues().get(0)).isGreaterThan(Duration.ZERO);
    assertThat(budgets.getAllValues().get(1)).isGreaterThan(Duration.ZERO)
        .isLessThan(budgets.getAllValues().get(0));
    assertThat(result.error().code()).isEqualTo("SCHEMA_INVALID");
    assertThat(result.error().message()).isEqualTo("模型返回格式异常，已自动重试仍未成功，请稍后重试");
  }

  @Test
  void acceptsOneWholeJsonFenceAfterRemovingBom() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String fenced = "\uFEFF```json\n" + mapper.writeValueAsString(validFullPayload()) + "\n```";
    AssistantService service = mockBackedService(fenced, dataResult());

    AssistantChatResponse result = service.chat(boss, request("7月净利润为什么变化"));

    assertThat(result.aiAnalysis().available()).isTrue();
    assertThat(result.aiAnalysis().analysisType()).isEqualTo("FULL");
    assertThat(result.aiAnalysis().risks().getFirst().evidence()).contains("25%");
  }

  @Test
  void rejectsMissingFieldInvalidConfidenceAndWrongFieldTypeAsSchemaInvalid() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> missingType = new LinkedHashMap<>(validFullPayload());
    missingType.remove("analysisType");
    AssistantChatResponse missing = mockBackedService(mapper.writeValueAsString(missingType), dataResult())
        .chat(boss, request("7月净利润为什么变化"));

    Map<String, Object> badConfidence = new LinkedHashMap<>(validFullPayload());
    badConfidence.put("confidence", "CERTAIN");
    AssistantChatResponse confidence = mockBackedService(mapper.writeValueAsString(badConfidence), dataResult())
        .chat(boss, request("7月净利润为什么变化"));

    Map<String, Object> wrongFieldType = new LinkedHashMap<>(validFullPayload());
    wrongFieldType.put("summary", 7);
    AssistantChatResponse typed = mockBackedService(mapper.writeValueAsString(wrongFieldType), dataResult())
        .chat(boss, request("7月净利润为什么变化"));

    assertThat(missing.error().code()).isEqualTo("SCHEMA_INVALID");
    assertThat(confidence.error().code()).isEqualTo("SCHEMA_INVALID");
    assertThat(typed.error().code()).isEqualTo("SCHEMA_INVALID");
  }

  @Test
  void neverExtractsJsonEmbeddedInOrdinaryModelText() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String ordinaryText = "下面是分析结果：" + mapper.writeValueAsString(validFullPayload());

    AssistantChatResponse result = mockBackedService(ordinaryText, dataResult())
        .chat(boss, request("7月净利润为什么变化"));

    assertThat(result.aiAnalysis().available()).isFalse();
    assertThat(result.error().code()).isEqualTo("SCHEMA_INVALID");
  }

  @Test
  void acceptsDataLimitedAnalysisWithDataCompletionActions() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("analysisType", "DATA_LIMITED");
    payload.put("summary", "当前经营数据不足，暂不判断原因和趋势。");
    payload.put("findings", List.of("成本和历史月份数据尚未补全。"));
    payload.put("risks", List.of());
    payload.put("possibleCauses", List.of());
    payload.put("actions", List.of(
        Map.of("action", "补全本月成本明细", "ownerRole", "店长", "deadline", "本周五",
            "expectedImpact", "形成完整成本数据", "verificationMetric", "成本合计"),
        Map.of("action", "录入缺失历史月份经营数据", "ownerRole", "FINANCE", "deadline", "本周日",
            "expectedImpact", "支持后续趋势对比", "verificationMetric", "历史月份数据")
    ));
    payload.put("limitations", List.of("成本和可比月份数据不足"));
    payload.put("confidence", "LOW");

    AssistantChatResponse result = mockBackedService(
        mapper.writeValueAsString(payload), dataLimitedResult()
    ).chat(boss, request("7月净利润为什么变化"));

    assertThat(result.aiAnalysis().available()).isTrue();
    assertThat(result.aiAnalysis().analysisType()).isEqualTo("DATA_LIMITED");
    assertThat(result.aiAnalysis().possibleCauses()).isEmpty();
    assertThat(result.aiAnalysis().actions()).hasSize(2);
    assertThat(result.aiAnalysis().actions().getFirst().ownerRole()).isEqualTo("STORE_MANAGER");
  }

  @Test
  void rejectsContradictorySnapshotAndIllegalActionRole() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> contradiction = new LinkedHashMap<>(validFullPayload());
    contradiction.put("summary", "净利润为负，需要立即止损。");
    AssistantChatResponse contradictory = mockBackedService(
        mapper.writeValueAsString(contradiction), dataResult()
    ).chat(boss, request("7月净利润为什么变化"));

    Map<String, Object> illegalRole = new LinkedHashMap<>(validFullPayload());
    illegalRole.put("actions", List.of(
        Map.of("action", "核对费用明细", "ownerRole", "MANAGER", "deadline", "本周三",
            "expectedImpact", "降低费用波动", "verificationMetric", "费用率"),
        Map.of("action", "复盘排班效率", "ownerRole", "STORE_MANAGER", "deadline", "本周五",
            "expectedImpact", "提高排班效率", "verificationMetric", "人工成本率"),
        Map.of("action", "跟踪改善结果", "ownerRole", "BOSS", "deadline", "本周日",
            "expectedImpact", "稳定盈利水平", "verificationMetric", "净利率")
    ));
    AssistantChatResponse invalidRole = mockBackedService(
        mapper.writeValueAsString(illegalRole), dataResult()
    ).chat(boss, request("7月净利润为什么变化"));

    assertThat(contradictory.error().code()).isEqualTo("ANALYSIS_SNAPSHOT_CONTRADICTION");
    assertThat(contradictory.error().message()).isEqualTo("模型结论与当前经营数据不一致，系统已拦截该结果，请核对数据后重新分析。");
    assertThat(invalidRole.error().code()).isEqualTo("ANALYSIS_ACTION_ROLE_INVALID");
    assertThat(invalidRole.error().message()).isEqualTo("模型建议的处理角色不符合系统职责范围，系统已拦截该结果，请稍后重新分析。");
  }

  @Test
  void reportsDataCompletionNeededWhenTheModelIgnoresTheDataLimitedContract() throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    AssistantChatResponse result = mockBackedService(
        mapper.writeValueAsString(validFullPayload()), dataLimitedResult()
    ).chat(boss, request("7月净利润为什么变化"));

    assertThat(result.error().code()).isEqualTo("DATA_LIMITED_REQUIRED");
    assertThat(result.error().message()).isEqualTo("经营数据不足，暂不能判断原因，请先补全成本、费用或历史月份数据。");
  }

  @Test
  void rejectsAmountsThatAreNotPresentInTheOperatingSnapshot() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ObjectMapper mapper = new ObjectMapper();
    String content = mapper.writeValueAsString(Map.of(
        "analysisType", "FULL",
        "summary", "利润改善仍依赖费用结构治理。",
        "findings", List.of("经营快照存在改善空间"),
        "risks", List.of(Map.of(
            "title", "费用风险", "evidence", "预计损失¥999999.00", "severity", "HIGH"
        )),
        "possibleCauses", List.of(Map.of(
            "cause", "费用控制可能不足", "confidence", "LOW", "basis", "费用结构待核实"
        )),
        "actions", List.of(
            Map.of("action", "核对费用", "ownerRole", "FINANCE", "deadline", "本周三", "expectedImpact", "改善费用结构", "verificationMetric", "费用率"),
            Map.of("action", "复盘排班", "ownerRole", "STORE_MANAGER", "deadline", "本周五", "expectedImpact", "提升效率", "verificationMetric", "人工成本率"),
            Map.of("action", "复核结果", "ownerRole", "BOSS", "deadline", "本周日", "expectedImpact", "稳定利润", "verificationMetric", "净利率")
        ),
        "confidence", "LOW", "limitations", List.of()
    ));
    String response = mapper.writeValueAsString(Map.of(
        "id", "unknown-amount", "model", "deepseek-test-model",
        "choices", List.of(Map.of("message", Map.of("content", content)))
    ));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      calls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();

    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setTimeout(Duration.ofSeconds(2));
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult());
    AssistantService service = new AssistantService(properties, mock(FinanceService.class), dataEngine);

    AssistantChatResponse result = service.chat(boss, request("7月净利润为什么变化"));

    assertThat(calls).hasValue(2);
    assertThat(result.error().code()).isEqualTo("ANALYSIS_UNKNOWN_NUMERIC");
    assertThat(result.error().message()).isEqualTo("模型引用了当前经营数据中没有的金额或比例，系统已拦截该结果，请核对数据后重新分析。");
    assertThat(result.aiAnalysis().available()).isFalse();
  }

  @Test
  void crossStoreDenialStopsBeforeAnyModelRequest() {
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("configured-but-must-not-be-used");
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenThrow(
        new BusinessException("FORBIDDEN", "店长只能查看自己门店利润表", HttpStatus.FORBIDDEN)
    );
    AssistantService service = new AssistantService(properties, mock(FinanceService.class), dataEngine);

    assertThatThrownBy(() -> service.chat(boss, request("7月净利润为什么变化")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("只能查看自己门店");
  }

  private AssistantChatRequest request(String message) {
    return new AssistantChatRequest(message, List.of(), "", "AUTO", "rx13", "2026-07");
  }

  private AssistantService mockBackedService(
      String content,
      AssistantDataEngine.Result data
  ) {
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    DeepSeekClient client = mock(DeepSeekClient.class);
    when(client.analyze(any(), any(), any(Duration.class))).thenReturn(
        new DeepSeekCallResult(content, "provider-request", "deepseek-test-model", 200, 1)
    );
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(data);
    return new AssistantService(properties, dataEngine, client, new ObjectMapper());
  }

  private Map<String, Object> validFullPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("analysisType", "FULL");
    payload.put("summary", "利润改善空间集中在费用结构与执行节奏。");
    payload.put("findings", List.of("费用结构需要优先复核"));
    payload.put("risks", List.of(Map.of(
        "title", "费用结构风险", "evidence", "费用率25%", "severity", "MEDIUM"
    )));
    payload.put("possibleCauses", List.of(Map.of(
        "cause", "排班与促销节奏可能不匹配", "confidence", "LOW", "basis", "人工成本结构待复核"
    )));
    payload.put("actions", List.of(
        Map.of("action", "核对费用明细", "ownerRole", "FINANCE", "deadline", "本周三",
            "expectedImpact", "降低费用波动", "verificationMetric", "费用率"),
        Map.of("action", "复盘排班效率", "ownerRole", "STORE_MANAGER", "deadline", "本周五",
            "expectedImpact", "提高排班效率", "verificationMetric", "人工成本率"),
        Map.of("action", "跟踪改善结果", "ownerRole", "BOSS", "deadline", "本周日",
            "expectedImpact", "稳定盈利水平", "verificationMetric", "净利率")
    ));
    payload.put("limitations", List.of("缺少客流数据"));
    payload.put("confidence", "MEDIUM");
    return payload;
  }

  private AssistantDataEngine.Result dataResult() {
    AssistantChatResponse.LocalData localData = new AssistantChatResponse.LocalData(
        "南阳三中店 2026-07：营业额¥120000.00，净利润¥30000.00，净利率25.0%。",
        List.of(
            new AssistantChatResponse.Metric("sales", "营业额", new BigDecimal("120000"), "CNY", "¥120000.00", null, ""),
            new AssistantChatResponse.Metric("net", "净利润", new BigDecimal("30000"), "CNY", "¥30000.00", new BigDecimal("0.5"), "较上月"),
            new AssistantChatResponse.Metric("margin", "净利率", new BigDecimal("0.25"), "PERCENT", "25.0%", null, "")
        ),
        "2026-07", "南阳三中店", "MySQL 8 财务库"
    );
    return new AssistantDataEngine.Result(
        localData,
        "权限过滤后的测试数据，不含敏感信息",
        "data-version-1",
        "rx13",
        "南阳三中店",
        "2026-07",
        List.of("缺少客流数据")
    );
  }

  private AssistantDataEngine.Result dataLimitedResult() {
    AssistantChatResponse.LocalData localData = new AssistantChatResponse.LocalData(
        "南阳三中店 2026-07：营业额¥120000.00，成本¥0.00，净利润¥120000.00，净利率100.0%。",
        List.of(
            new AssistantChatResponse.Metric("sales", "营业额", new BigDecimal("120000"), "CNY", "¥120000.00", null, ""),
            new AssistantChatResponse.Metric("cost", "成本合计", BigDecimal.ZERO, "CNY", "¥0.00", null, ""),
            new AssistantChatResponse.Metric("net", "净利润", new BigDecimal("120000"), "CNY", "¥120000.00", null, ""),
            new AssistantChatResponse.Metric("margin", "净利率", BigDecimal.ONE, "PERCENT", "100.0%", null, "")
        ),
        "2026-07", "南阳三中店", "MySQL 8 财务库"
    );
    return new AssistantDataEngine.Result(
        localData,
        "权限过滤后的测试数据，不含敏感信息",
        "data-version-limited",
        "rx13",
        "南阳三中店",
        "2026-07",
        List.of("缺少上月数据，无法计算环比", "近三个月数据不足，趋势判断可信度受限")
    );
  }
}
