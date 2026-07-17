package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssistantStructuredListLimitTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AuthUser boss = new AuthUser(
      1L, 1L, "tenant", "boss", "hash", "Boss", "BOSS", null, true
  );

  @Test
  void truncatesOverlongStructuredAnalysisListsInsteadOfRejectingTheAnswer() throws Exception {
    Map<String, Object> payload = Map.of(
        "analysisType", "FULL",
        "summary", "Net profit \u00a530000 and net margin 25.0% are driven by cost control and expense execution.",
        "findings", List.of(
            "finding alpha", "finding beta", "finding gamma",
            "finding delta", "finding epsilon", "finding zeta"
        ),
        "risks", List.of(
            risk("risk alpha"), risk("risk beta"), risk("risk gamma"), risk("risk delta")
        ),
        "possibleCauses", List.of(
            cause("cause alpha"), cause("cause beta"), cause("cause gamma"),
            cause("cause delta"), cause("cause epsilon"), cause("cause zeta")
        ),
        "actions", List.of(
            action("action alpha", "FINANCE"),
            action("action beta", "STORE_MANAGER"),
            action("action gamma", "BOSS"),
            action("action delta", "BOSS")
        ),
        "limitations", List.of(
            "limitation alpha", "limitation beta", "limitation gamma",
            "limitation delta", "limitation epsilon", "limitation zeta", "limitation eta"
        ),
        "confidence", "MEDIUM"
    );
    DeepSeekClient client = mock(DeepSeekClient.class);
    when(client.analyzeFast(any(), any(), any(Duration.class))).thenReturn(
        new DeepSeekCallResult(mapper.writeValueAsString(payload), "provider-request", "test-model", 200, 1)
    );
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult());
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    AssistantService service = new AssistantService(properties, dataEngine, client, mapper);

    AssistantChatResponse result = service.chat(boss, new AssistantChatRequest(
        "\u0037\u6708\u51c0\u5229\u6da6\u4e3a\u4ec0\u4e48\u53d8\u5316",
        List.of(), "", "AUTO", "rx13", "2026-07"
    ));

    assertThat(result.error()).isNull();
    assertThat(result.aiAnalysis().available()).isTrue();
    assertThat(result.aiAnalysis().findings()).containsExactly(
        "finding alpha", "finding beta", "finding gamma", "finding delta", "finding epsilon"
    );
    assertThat(result.aiAnalysis().risks()).hasSize(3);
    assertThat(result.aiAnalysis().possibleCauses()).hasSize(5);
    assertThat(result.aiAnalysis().actions()).hasSize(3);
    assertThat(result.aiAnalysis().limitations()).contains("limitation zeta");
    assertThat(result.aiAnalysis().limitations()).doesNotContain("limitation eta");
  }

  @Test
  void retriesWithNumericRepairInstructionWhenModelInventsUnknownAmount() throws Exception {
    Map<String, Object> firstPayload = safePayload("Unknown target \u00a5999 should trigger repair.");
    Map<String, Object> repairedPayload = safePayload(
        "Net profit \u00a530000 and net margin 25.0% need follow-up by store managers."
    );
    DeepSeekClient client = mock(DeepSeekClient.class);
    when(client.analyzeFast(any(), any(), any(Duration.class))).thenReturn(
        new DeepSeekCallResult(mapper.writeValueAsString(firstPayload), "provider-request-1", "test-model", 200, 1),
        new DeepSeekCallResult(mapper.writeValueAsString(repairedPayload), "provider-request-2", "test-model", 200, 1)
    );
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult());
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    AssistantService service = new AssistantService(properties, dataEngine, client, mapper);

    AssistantChatResponse result = service.chat(boss, new AssistantChatRequest(
        "\u0037\u6708\u51c0\u5229\u6da6\u4e3a\u4ec0\u4e48\u53d8\u5316",
        List.of(), "", "AUTO", "rx13", "2026-07"
    ));

    assertThat(result.error()).isNull();
    assertThat(result.aiAnalysis().available()).isTrue();
    ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
    verify(client, times(2)).analyzeFast(prompts.capture(), any(), any(Duration.class));
    assertThat(prompts.getAllValues().get(1)).contains(
        "UNKNOWN_AMOUNT",
        "\u76ee\u6807\u91d1\u989d",
        "\u8d39\u7528\u5360\u6bd4"
    );
  }

  @Test
  void sanitizesUnknownFactualNumberWhenRepairStillInventsAmount() throws Exception {
    Map<String, Object> firstPayload = safePayload("Unknown target \u00a5998 should trigger repair.");
    Map<String, Object> repairedPayload = safePayload("Unknown target \u00a5999 should be hidden.");
    DeepSeekClient client = mock(DeepSeekClient.class);
    when(client.analyzeFast(any(), any(), any(Duration.class))).thenReturn(
        new DeepSeekCallResult(mapper.writeValueAsString(firstPayload), "provider-request-1", "test-model", 200, 1),
        new DeepSeekCallResult(mapper.writeValueAsString(repairedPayload), "provider-request-2", "test-model", 200, 1)
    );
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult());
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    AssistantService service = new AssistantService(properties, dataEngine, client, mapper);

    AssistantChatResponse result = service.chat(boss, new AssistantChatRequest(
        "\u0037\u6708\u51c0\u5229\u6da6\u4e3a\u4ec0\u4e48\u53d8\u5316",
        List.of(), "", "AUTO", "rx13", "2026-07"
    ));

    assertThat(result.error()).isNull();
    assertThat(result.aiAnalysis().available()).isTrue();
    assertThat(result.aiAnalysis().summary()).contains("\u672a\u6838\u9a8c\u6570\u503c");
    assertThat(result.aiAnalysis().summary()).doesNotContain("\u00a5999");
    verify(client, times(2)).analyzeFast(any(), any(), any(Duration.class));
  }

  @Test
  void acceptsNumbersAlreadyProvidedInModelContext() throws Exception {
    Map<String, Object> payload = safePayload("Trend reference \u00a5999 and 12.3% came from context.");
    DeepSeekClient client = mock(DeepSeekClient.class);
    when(client.analyzeFast(any(), any(), any(Duration.class))).thenReturn(
        new DeepSeekCallResult(mapper.writeValueAsString(payload), "provider-request", "test-model", 200, 1)
    );
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(
        dataResult("Trend line provided by data engine: prior net \u00a5999, prior margin 12.3%.")
    );
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    AssistantService service = new AssistantService(properties, dataEngine, client, mapper);

    AssistantChatResponse result = service.chat(boss, new AssistantChatRequest(
        "\u0037\u6708\u51c0\u5229\u6da6\u4e3a\u4ec0\u4e48\u53d8\u5316",
        List.of(), "", "AUTO", "rx13", "2026-07"
    ));

    assertThat(result.error()).isNull();
    assertThat(result.aiAnalysis().available()).isTrue();
    verify(client, times(1)).analyzeFast(any(), any(), any(Duration.class));
  }

  @Test
  void doesNotRejectActionTargetNumberAsCurrentFact() throws Exception {
    Map<String, Object> payload = safePayload(
        "Net profit \u00a530000 and net margin 25.0% need follow-up by store managers.",
        List.of(
            actionWithImpact("action alpha", "FINANCE", "target improvement \u00a5999"),
            action("action beta", "STORE_MANAGER"),
            action("action gamma", "BOSS")
        )
    );
    DeepSeekClient client = mock(DeepSeekClient.class);
    when(client.analyzeFast(any(), any(), any(Duration.class))).thenReturn(
        new DeepSeekCallResult(mapper.writeValueAsString(payload), "provider-request", "test-model", 200, 1)
    );
    AssistantDataEngine dataEngine = mock(AssistantDataEngine.class);
    when(dataEngine.build(any(), any(), any())).thenReturn(dataResult());
    DeepSeekProperties properties = new DeepSeekProperties();
    properties.setApiKey("test-only-key");
    AssistantService service = new AssistantService(properties, dataEngine, client, mapper);

    AssistantChatResponse result = service.chat(boss, new AssistantChatRequest(
        "\u0037\u6708\u51c0\u5229\u6da6\u4e3a\u4ec0\u4e48\u53d8\u5316",
        List.of(), "", "AUTO", "rx13", "2026-07"
    ));

    assertThat(result.error()).isNull();
    assertThat(result.aiAnalysis().available()).isTrue();
    verify(client, times(1)).analyzeFast(any(), any(), any(Duration.class));
  }

  private Map<String, Object> safePayload(String summary) {
    return safePayload(summary, List.of(
        action("action alpha", "FINANCE"),
        action("action beta", "STORE_MANAGER"),
        action("action gamma", "BOSS")
    ));
  }

  private Map<String, Object> safePayload(String summary, List<Map<String, String>> actions) {
    return Map.of(
        "analysisType", "FULL",
        "summary", summary,
        "findings", List.of("finding alpha", "finding beta"),
        "risks", List.of(risk("risk alpha")),
        "possibleCauses", List.of(cause("cause alpha")),
        "actions", actions,
        "limitations", List.of("limitation alpha"),
        "confidence", "MEDIUM"
    );
  }

  private Map<String, String> risk(String title) {
    return Map.of("title", title, "evidence", "evidence text", "severity", "LOW");
  }

  private Map<String, String> cause(String cause) {
    return Map.of("cause", cause, "confidence", "LOW", "basis", "basis text");
  }

  private Map<String, String> action(String action, String ownerRole) {
    return actionWithImpact(action, ownerRole, "stabilize margin");
  }

  private Map<String, String> actionWithImpact(String action, String ownerRole, String expectedImpact) {
    return Map.of(
        "action", action,
        "ownerRole", ownerRole,
        "deadline", "this week",
        "expectedImpact", expectedImpact,
        "verificationMetric", "net margin"
    );
  }

  private AssistantDataEngine.Result dataResult() {
    return dataResult("Safe local context with revenue, net profit and margin.");
  }

  private AssistantDataEngine.Result dataResult(String modelContext) {
    AssistantChatResponse.LocalData localData = new AssistantChatResponse.LocalData(
        "Business snapshot: revenue 120000, net profit 30000, net margin 25 percent.",
        List.of(
            new AssistantChatResponse.Metric("sales", "sales", new BigDecimal("120000"), "CNY", "120000", null, ""),
            new AssistantChatResponse.Metric("net", "net", new BigDecimal("30000.08"), "CNY", "30000.08", null, ""),
            new AssistantChatResponse.Metric("margin", "margin", new BigDecimal("0.2504"), "PERCENT", "25.04 percent", null, "")
        ),
        "2026-07",
        "test store",
        "MySQL"
    );
    return new AssistantDataEngine.Result(
        localData,
        modelContext,
        "data-version",
        "rx13",
        "test store",
        "2026-07",
        List.of("local limitation")
    );
  }
}
