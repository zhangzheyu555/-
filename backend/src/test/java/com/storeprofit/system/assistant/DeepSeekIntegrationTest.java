package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.finance.ProfitSummaryResponse;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DeepSeek 集成测试")
class DeepSeekIntegrationTest {

  private final FinanceService financeService = mock(FinanceService.class);
  private final DeepSeekProperties properties = new DeepSeekProperties();

  private AssistantService assistantService;

  private static AuthUser boss() {
    return new AuthUser(1L, 1L, "默认企业", "boss", "hash", "老板", "BOSS", null, true);
  }

  private static AuthUser storeManager() {
    return new AuthUser(2L, 1L, "默认企业", "rg1", "hash", "店长", "STORE_MANAGER", "rg1", true);
  }

  @BeforeEach
  void setUp() {
    properties.setEnabled(true);
    properties.setApiKey("");
    properties.setBaseUrl("https://api.deepseek.com");
    properties.setModel("deepseek-chat");
    properties.setMaxTokens(1200);
    properties.setTemperature(0.2);
    properties.setConnectTimeout(Duration.ofSeconds(5));
    properties.setTimeout(Duration.ofSeconds(45));
    assistantService = new AssistantService(properties, financeService);

    // Stub FinanceService to avoid NPE in buildDataContext
    ProfitSummaryResponse summary = new ProfitSummaryResponse(
        "2026-07", 5, 3,
        new BigDecimal("500000"), new BigDecimal("480000"),
        new BigDecimal("200000"), new BigDecimal("150000"),
        new BigDecimal("130000"), new BigDecimal("0.26"), 1
    );
    com.storeprofit.system.finance.ProfitDashboardResponse dashboardResponse =
        new com.storeprofit.system.finance.ProfitDashboardResponse(
            List.of("2026-07"), List.of(), summary, List.of(), List.of()
        );
    when(financeService.dashboard(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(dashboardResponse);
    when(financeService.months(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of("2026-07"));
    when(financeService.entries(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
  }

  @Nested
  @DisplayName("DeepSeek 未配置")
  class DeepSeekNotConfigured {

    @Test
    @DisplayName("API Key 为空时返回 DEEPSEEK_NOT_CONFIGURED 降级")
    void returnsNotConfiguredFallbackWhenApiKeyIsEmpty() {
      properties.setApiKey("");

      AssistantChatResponse response = assistantService.chat(boss(),
          new AssistantChatRequest("为什么利润下降", List.of(), "", null, null, null));

      assertThat(response.fallbackUsed()).isTrue();
      assertThat(response.localData().source()).isEqualTo("MySQL 8 财务库");
      assertThat(response.aiAnalysis().model()).isEmpty();
      assertThat(response.aiAnalysis().available()).isFalse();
      assertThat(response.error().code()).isEqualTo("DEEPSEEK_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("API Key 仅为空白字符时返回降级")
    void returnsFallbackWhenApiKeyIsBlank() {
      properties.setApiKey("   ");

      AssistantChatResponse response = assistantService.chat(boss(),
          new AssistantChatRequest("为什么利润下降", List.of(), "", null, null, null));

      assertThat(response.fallbackUsed()).isTrue();
    }

    @Test
    @DisplayName("未配置时不得伪造模型 requestId")
    void requestIdIsEmptyWhenNotConfigured() {
      properties.setApiKey("");

      AssistantChatResponse response = assistantService.chat(boss(),
          new AssistantChatRequest("为什么利润下降", List.of(), "", null, null, null));

      assertThat(response.aiAnalysis().requestId()).isEmpty();
      assertThat(response.error().code()).isEqualTo("DEEPSEEK_NOT_CONFIGURED");
    }
  }

  @Nested
  @DisplayName("DeepSeek 已禁用")
  class DeepSeekDisabled {

    @Test
    @DisplayName("enabled=false 时返回 DEEPSEEK_DISABLED 降级")
    void returnsDisabledFallback() {
      properties.setEnabled(false);
      properties.setApiKey("sk-valid-looking-key");

      AssistantChatResponse response = assistantService.chat(boss(),
          new AssistantChatRequest("为什么利润下降", List.of(), "", null, null, null));

      assertThat(response.fallbackUsed()).isTrue();
      assertThat(response.localData().source()).isEqualTo("MySQL 8 财务库");
      assertThat(response.error().code()).isEqualTo("DEEPSEEK_DISABLED");
    }

    @Test
    @DisplayName("经营助手降级响应不序列化 API Key")
    void fallbackResponseNeverSerializesOperatingAssistantKey() throws Exception {
      String configuredKey = "test-operating-assistant-key-not-a-real-secret";
      properties.setEnabled(false);
      properties.setApiKey(configuredKey);

      AssistantChatResponse response = assistantService.chat(boss(),
          new AssistantChatRequest("为什么利润下降", List.of(), "", null, null, null));
      String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

      assertThat(response.fallbackUsed()).isTrue();
      assertThat(serialized)
          .doesNotContain(configuredKey)
          .doesNotContain("apiKey")
          .doesNotContain("api-key");
    }
  }

  @Nested
  @DisplayName("角色边界检查")
  class RoleBoundary {

    @Test
    @DisplayName("店长不能查询全部门店排名")
    void storeManagerCannotQueryAllStoreRanking() {
      properties.setApiKey("sk-test");

      AssistantChatResponse response = assistantService.chat(storeManager(),
          new AssistantChatRequest("全部门店利润排名", List.of(), "", null, null, null));

      assertThat(response.error().code()).isEqualTo("FORBIDDEN_SCOPE");
      assertThat(response.localData().source()).isEqualTo("系统安全规则");
    }
  }

  @Nested
  @DisplayName("响应结构验证")
  class ResponseStructure {

    @Test
    @DisplayName("降级响应不伪造 provider、model、requestId 和耗时")
    void fallbackResponseContainsAllRequiredFields() {
      properties.setApiKey("");

      AssistantChatResponse response = assistantService.chat(boss(),
          new AssistantChatRequest("为什么利润下降", List.of(), "", null, null, null));

      assertThat(response.aiAnalysis().available()).isFalse();
      assertThat(response.aiAnalysis().provider()).isEmpty();
      assertThat(response.aiAnalysis().model()).isEmpty();
      assertThat(response.aiAnalysis().requestId()).isEmpty();
      assertThat(response.aiAnalysis().latencyMs()).isZero();
      assertThat(response.fallbackUsed()).isTrue();
    }

    @Test
    @DisplayName("守卫拦截响应也有 generatedAt")
    void guardrailResponseHasGeneratedAt() {
      AssistantChatResponse response = assistantService.chat(boss(),
          new AssistantChatRequest("赌博网站", List.of(), "", null, null, null));

      assertThat(response.error().code()).isEqualTo("BLOCKED_WORD");
      assertThat(response.localData().source()).isEqualTo("系统安全规则");
    }

    @Test
    @DisplayName("storeScope 不可变")
    void storeScopeIsImmutable() {
      properties.setApiKey("");

      AssistantChatResponse response = assistantService.chat(boss(),
          new AssistantChatRequest("为什么利润下降", List.of(), "", null, null, null));

      assertThat(response.localData().dataScope()).isNotNull();
    }

    @Test
    @DisplayName("错误信息为中文且经营数据仍可用")
    void errorIsChineseAndLocalDataRemainsAvailable() {
      properties.setApiKey("");

      AssistantChatResponse response = assistantService.chat(boss(),
          new AssistantChatRequest("为什么利润下降", List.of(), "", null, null, null));

      assertThat(response.error().message()).contains("AI分析");
      assertThat(response.localData().summary()).isNotBlank();
    }
  }

  @Nested
  @DisplayName("DeepSeekException 错误分类")
  class ErrorClassification {

    @Test
    @DisplayName("AUTH_FAILED 异常包含正确的 code 和 userMessage")
    void authFailedExceptionHasCorrectFields() {
      DeepSeekException ex = new DeepSeekException("DEEPSEEK_AUTH_FAILED",
          "AI 服务认证失败，请管理员检查配置。", 401);

      assertThat(ex.getCode()).isEqualTo("DEEPSEEK_AUTH_FAILED");
      assertThat(ex.getUserMessage()).isEqualTo("AI 服务认证失败，请管理员检查配置。");
      assertThat(ex.getHttpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("BALANCE_INSUFFICIENT 异常 HTTP 402")
    void balanceInsufficientHasCorrectStatus() {
      DeepSeekException ex = new DeepSeekException("DEEPSEEK_BALANCE_INSUFFICIENT",
          "AI 账户余额不足。", 402);

      assertThat(ex.getCode()).isEqualTo("DEEPSEEK_BALANCE_INSUFFICIENT");
      assertThat(ex.getHttpStatus()).isEqualTo(402);
    }

    @Test
    @DisplayName("RATE_LIMITED 异常 HTTP 429")
    void rateLimitedHasCorrectStatus() {
      DeepSeekException ex = new DeepSeekException("DEEPSEEK_RATE_LIMITED",
          "请求过于频繁，请稍后再试。", 429);

      assertThat(ex.getCode()).isEqualTo("DEEPSEEK_RATE_LIMITED");
      assertThat(ex.getHttpStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("TIMEOUT 异常 HTTP 0（非HTTP错误）")
    void timeoutHasZeroHttpStatus() {
      DeepSeekException ex = new DeepSeekException("DEEPSEEK_TIMEOUT",
          "AI 服务响应超时。", 0);

      assertThat(ex.getCode()).isEqualTo("DEEPSEEK_TIMEOUT");
      assertThat(ex.getHttpStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("UNAVAILABLE 异常 HTTP 500/503")
    void unavailableHasServerErrorStatus() {
      DeepSeekException ex = new DeepSeekException("DEEPSEEK_UNAVAILABLE",
          "AI 服务暂时不可用。", 503);

      assertThat(ex.getCode()).isEqualTo("DEEPSEEK_UNAVAILABLE");
      assertThat(ex.getHttpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("EMPTY_RESPONSE 异常 content 为空")
    void emptyResponseException() {
      DeepSeekException ex = new DeepSeekException("DEEPSEEK_EMPTY_RESPONSE",
          "AI 返回内容为空。", 200);

      assertThat(ex.getCode()).isEqualTo("DEEPSEEK_EMPTY_RESPONSE");
      assertThat(ex.getUserMessage()).isEqualTo("AI 返回内容为空。");
    }

    @Test
    @DisplayName("RESPONSE_INVALID 异常 JSON 无法解析")
    void responseInvalidException() {
      DeepSeekException ex = new DeepSeekException("DEEPSEEK_RESPONSE_INVALID",
          "AI 返回格式无法解析。", 0);

      assertThat(ex.getCode()).isEqualTo("DEEPSEEK_RESPONSE_INVALID");
    }
  }

  @Nested
  @DisplayName("DeepSeekProperties 配置")
  class PropertiesConfiguration {

    @Test
    @DisplayName("默认模型为当前正式的 deepseek-v4-flash")
    void defaultModelIsDeepSeekChat() {
      DeepSeekProperties props = new DeepSeekProperties();
      assertThat(props.getModel()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("setModel 空值回退到 deepseek-v4-flash")
    void setModelFallsBackToDeepSeekChat() {
      DeepSeekProperties props = new DeepSeekProperties();
      props.setModel("");
      assertThat(props.getModel()).isEqualTo("deepseek-v4-flash");

      props.setModel(null);
      assertThat(props.getModel()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("isConfigured 在 enabled=true 且有 Key 时返回 true")
    void isConfiguredTrueWhenEnabledAndHasKey() {
      DeepSeekProperties props = new DeepSeekProperties();
      props.setEnabled(true);
      props.setApiKey("sk-test-key");
      assertThat(props.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("isConfigured 在 enabled=false 时返回 false")
    void isConfiguredFalseWhenDisabled() {
      DeepSeekProperties props = new DeepSeekProperties();
      props.setEnabled(false);
      props.setApiKey("sk-test-key");
      assertThat(props.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("isConfigured 在无 Key 时返回 false")
    void isConfiguredFalseWhenNoKey() {
      DeepSeekProperties props = new DeepSeekProperties();
      props.setEnabled(true);
      props.setApiKey("");
      assertThat(props.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("markSuccess 设置 lastSuccessAt")
    void markSuccessSetsTimestamp() {
      DeepSeekProperties props = new DeepSeekProperties();
      assertThat(props.getLastSuccessAt()).isNull();
      props.markSuccess();
      assertThat(props.getLastSuccessAt()).isNotNull();
    }

    @Test
    @DisplayName("markFailure 设置 lastFailureAt 和 lastFailureCode")
    void markFailureSetsBothFields() {
      DeepSeekProperties props = new DeepSeekProperties();
      props.markFailure("DEEPSEEK_TIMEOUT");
      assertThat(props.getLastFailureAt()).isNotNull();
      assertThat(props.getLastFailureCode()).isEqualTo("DEEPSEEK_TIMEOUT");
    }

    @Test
    @DisplayName("baseUrl 自动去除尾部斜杠")
    void baseUrlTrimsTrailingSlash() {
      DeepSeekProperties props = new DeepSeekProperties();
      props.setBaseUrl("https://api.deepseek.com/");
      assertThat(props.getBaseUrl()).isEqualTo("https://api.deepseek.com");
    }

    @Test
    @DisplayName("getBaseUrlHost 正确提取主机名")
    void baseUrlHostExtractsCorrectly() {
      DeepSeekProperties props = new DeepSeekProperties();
      props.setBaseUrl("https://api.deepseek.com");
      assertThat(props.getBaseUrlHost()).isEqualTo("api.deepseek.com");
    }

    @Test
    @DisplayName("connectTimeout 默认 5 秒")
    void defaultConnectTimeoutIsFiveSeconds() {
      DeepSeekProperties props = new DeepSeekProperties();
      assertThat(props.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("timeout 默认 45 秒")
    void defaultTimeoutIs45Seconds() {
      DeepSeekProperties props = new DeepSeekProperties();
      assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    @DisplayName("整次经营分析默认受 75 秒总预算约束")
    void defaultAnalysisTimeoutIsSeventyFiveSeconds() {
      DeepSeekProperties props = new DeepSeekProperties();
      assertThat(props.getAnalysisTimeout()).isEqualTo(Duration.ofSeconds(75));
    }
  }
}
