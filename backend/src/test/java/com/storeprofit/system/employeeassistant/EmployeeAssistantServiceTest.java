package com.storeprofit.system.employeeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class EmployeeAssistantServiceTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void missingUrlOrTokenIsReportedAsNotConfiguredAndAuditedWithoutContent() {
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = service("", "", auditRepository);

    assertThat(service.health(user()))
        .isEqualTo(new EmployeeAssistantStatusResponse(
            false,
            false,
            EmployeeAssistantState.UNCONFIGURED,
            "员工服务助手未配置",
            false,
            false
        ));
    BusinessException error = catchThrowableOfType(
        () -> service.chat(user(), new EmployeeAssistantChatRequest(null, "怎么处理漏发吸管？")),
        BusinessException.class
    );

    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(error.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_NOT_CONFIGURED");
    ArgumentCaptor<AuditLogRequest> capture = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository, org.mockito.Mockito.times(2)).writeLog(any(AuthUser.class), capture.capture());
    AuditLogRequest log = capture.getAllValues().get(1);
    assertThat(log.action()).isEqualTo("employee_assistant.chat");
    assertThat(log.reason()).contains("NOT_CONFIGURED").doesNotContain("漏发吸管");
    assertThat(log.beforeJson()).isNull();
    assertThat(log.afterJson()).isNull();
    assertThat(capture.getAllValues().get(0).action()).isEqualTo("employee_assistant.health");
    assertThat(capture.getAllValues().get(0).reason()).isEqualTo("state=UNCONFIGURED");
  }

  @Test
  void eachProviderModeRequiresEverySelectedModeVariable() {
    AuditRepository auditRepository = mock(AuditRepository.class);

    EmployeeAssistantService remoteWithoutUrl = new EmployeeAssistantService(
        "REMOTE", "", "remote-token", "", "", "", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ObjectMapper(), auditRepository, HttpClient.newHttpClient());
    EmployeeAssistantService remoteWithoutToken = new EmployeeAssistantService(
        "REMOTE", "https://assistant.example.test", "", "", "", "", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ObjectMapper(), auditRepository, HttpClient.newHttpClient());
    EmployeeAssistantService modelWithoutName = new EmployeeAssistantService(
        "MODEL", "", "", "https://model.example.test", "model-key", "", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ObjectMapper(), auditRepository, HttpClient.newHttpClient());
    EmployeeAssistantService modelWithoutApiKey = new EmployeeAssistantService(
        "MODEL", "", "", "https://model.example.test", "", "employee-support", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ObjectMapper(), auditRepository, HttpClient.newHttpClient());

    assertThat(remoteWithoutUrl.health(user()).state()).isEqualTo(EmployeeAssistantState.UNCONFIGURED);
    assertThat(remoteWithoutToken.health(user()).state()).isEqualTo(EmployeeAssistantState.UNCONFIGURED);
    assertThat(modelWithoutName.health(user()).state()).isEqualTo(EmployeeAssistantState.UNCONFIGURED);
    assertThat(modelWithoutApiKey.health(user()).state()).isEqualTo(EmployeeAssistantState.UNCONFIGURED);
  }

  @Test
  void localProviderNeedsNoUrlOrApiKeyAndAnswersCommonServiceQuestions() {
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = new EmployeeAssistantService(
        "LOCAL", "", "", "", "", "", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ObjectMapper(), auditRepository, HttpClient.newHttpClient());

    EmployeeAssistantStatusResponse status = service.health(user());
    EmployeeAssistantChatResponse response = service.chat(user(),
        new EmployeeAssistantChatRequest(null, "顾客等待太久，应该怎么回应？"));

    assertThat(status.state()).isEqualTo(EmployeeAssistantState.READY);
    assertThat(status.configured()).isTrue();
    assertThat(status.enabled()).isTrue();
    assertThat(status.canAsk()).isTrue();
    assertThat(response.answer()).contains("致歉", "立即跟进");
    assertThat(response.answerSource()).isEqualTo(EmployeeAssistantAnswerSource.ASSISTANT);
    assertThat(response.needsHuman()).isFalse();
    ArgumentCaptor<AuditLogRequest> capture = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository, org.mockito.Mockito.times(2)).writeLog(any(AuthUser.class), capture.capture());
    assertThat(capture.getAllValues().get(0).reason()).isEqualTo("state=READY");
    assertThat(capture.getAllValues().get(1).reason()).contains("SUCCESS_LOCAL");
  }

  @Test
  void localProviderNeverGuessesOutsideItsSafeServiceRules() {
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = new EmployeeAssistantService(
        "LOCAL", "", "", "", "", "", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ObjectMapper(), auditRepository, HttpClient.newHttpClient());

    EmployeeAssistantChatResponse response = service.chat(user(),
        new EmployeeAssistantChatRequest(null, "打印机突然没有反应怎么办？"));

    assertThat(response.answer()).contains("超出本地安全话术范围", "值班经理");
    assertThat(response.answerSource()).isEqualTo(EmployeeAssistantAnswerSource.HUMAN_REQUIRED);
    assertThat(response.needsHuman()).isTrue();
    assertThat(response.handoffCategory()).isEqualTo("LOCAL_NO_MATCH");
  }

  @Test
  void localProviderRejectsMixedExternalConfiguration() {
    EmployeeAssistantService service = new EmployeeAssistantService(
        "LOCAL", "", "", "https://model.example.test", "must-not-be-used", "model",
        Duration.ofSeconds(1), Duration.ofSeconds(2), new ObjectMapper(), mock(AuditRepository.class),
        HttpClient.newHttpClient());

    EmployeeAssistantStatusResponse status = service.health(user());

    assertThat(status.state()).isEqualTo(EmployeeAssistantState.UNCONFIGURED);
    assertThat(status.message()).doesNotContain("model.example", "must-not-be-used");
  }

  @Test
  void missingProviderIsUnconfiguredEvenWhenRemoteValuesExistAndDoesNotLeakThem() {
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = new EmployeeAssistantService(
        "", "https://assistant.example.test/health?must-not-appear", "remote-secret-must-not-appear",
        "", "", "", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ObjectMapper(), auditRepository, HttpClient.newHttpClient());

    EmployeeAssistantStatusResponse result = service.health(user());

    assertThat(result.state()).isEqualTo(EmployeeAssistantState.UNCONFIGURED);
    assertThat(result.message()).doesNotContain("must-not-appear", "remote-secret");
    ArgumentCaptor<AuditLogRequest> capture = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(AuthUser.class), capture.capture());
    assertThat(capture.getValue().reason()).isEqualTo("state=UNCONFIGURED")
        .doesNotContain("must-not-appear", "secret");
  }

  @Test
  void redactsPersonalIdentifiersAndForwardsOnlyQuestionAndUuidSession() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    startServer(exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"answer\":\"先向顾客致歉并转人工\",\"needs_human\":true,\"internal\":\"hidden\"}");
    });
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = service(baseUrl(), "test-token", auditRepository);

    EmployeeAssistantChatResponse response = service.chat(
        user(),
        new EmployeeAssistantChatRequest(null, "顾客手机号是13800138000，漏发吸管怎么处理？")
    );

    assertThat(response.answer()).isEqualTo("先向顾客致歉并转人工");
    assertThat(response.configured()).isTrue();
    assertThat(response.needsHuman()).isTrue();
    assertThat(response.requestId()).matches("[0-9a-f-]{36}");
    assertThat(response.sessionId()).matches("[0-9a-f-]{36}");
    assertThat(authorization.get()).isEqualTo("Bearer test-token");
    assertThat(requestBody.get()).contains("***").doesNotContain("13800138000").doesNotContain("test-token");
    assertThat(requestBody.get()).contains("conversation_id");

    ArgumentCaptor<AuditLogRequest> capture = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(AuthUser.class), capture.capture());
    assertThat(capture.getValue().targetId()).isEqualTo(response.requestId());
    assertThat(capture.getValue().reason()).contains("SUCCESS").contains("input_redacted=true")
        .doesNotContain("13800138000");
  }

  @Test
  void rejectsFinancialContentAndAttachmentReferencesBeforeCallingUpstream() {
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = service("https://assistant.example.test", "test-token", auditRepository);

    BusinessException financial = catchThrowableOfType(
        () -> service.chat(user(), new EmployeeAssistantChatRequest(null, "今日营收 1000 元，帮我分析")),
        BusinessException.class
    );
    BusinessException attachment = catchThrowableOfType(
        () -> service.chat(user(), new EmployeeAssistantChatRequest(null, "请看 /api/storage/attachments/42")),
        BusinessException.class
    );

    assertThat(financial.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_FINANCE_BLOCKED");
    assertThat(attachment.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_ATTACHMENT_BLOCKED");
    verify(auditRepository, org.mockito.Mockito.times(2)).writeLog(any(AuthUser.class), any(AuditLogRequest.class));
  }

  @Test
  void blocksCustomerOrderAndAddressBeforeAnythingCanReachUpstream() throws Exception {
    AtomicInteger chatCalls = new AtomicInteger();
    startServer(exchange -> {
      chatCalls.incrementAndGet();
      respond(exchange, 200, "{\"answer\":\"should not be sent\"}");
    });
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = service(baseUrl(), "test-token", auditRepository);

    BusinessException order = catchThrowableOfType(
        () -> service.chat(user(), new EmployeeAssistantChatRequest(null, "顾客订单号：A20260714001，请查一下")),
        BusinessException.class
    );
    BusinessException address = catchThrowableOfType(
        () -> service.chat(user(), new EmployeeAssistantChatRequest(null, "配送地址：上海市浦东新区测试路 1 号")),
        BusinessException.class
    );
    BusinessException customerName = catchThrowableOfType(
        () -> service.chat(user(), new EmployeeAssistantChatRequest(null, "顾客是张三，请帮我回复")),
        BusinessException.class
    );
    BusinessException conversationalOrder = catchThrowableOfType(
        () -> service.chat(user(), new EmployeeAssistantChatRequest(null, "订单号是ABCD1234，请帮我看一下")),
        BusinessException.class
    );
    BusinessException conversationalAddress = catchThrowableOfType(
        () -> service.chat(user(), new EmployeeAssistantChatRequest(null, "收货地址为上海市浦东新区测试路 1 号")),
        BusinessException.class
    );

    assertThat(order.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_PRIVACY_BLOCKED");
    assertThat(address.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_PRIVACY_BLOCKED");
    assertThat(customerName.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_PRIVACY_BLOCKED");
    assertThat(conversationalOrder.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_PRIVACY_BLOCKED");
    assertThat(conversationalAddress.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_PRIVACY_BLOCKED");
    assertThat(chatCalls).hasValue(0);
    verify(auditRepository, org.mockito.Mockito.times(5)).writeLog(any(AuthUser.class), any(AuditLogRequest.class));
  }

  @Test
  void mapsUpstream403ToChineseAuthorizationMessageWithoutLeakingBody() throws Exception {
    startServer(exchange -> respond(exchange, 403, "{\"detail\":\"internal-token-error\"}"));
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = service(baseUrl(), "test-token", auditRepository);

    BusinessException error = catchThrowableOfType(
        () -> service.chat(user(), new EmployeeAssistantChatRequest(null, "漏发吸管怎么处理？")),
        BusinessException.class
    );

    assertThat(error.getCode()).isEqualTo("EMPLOYEE_ASSISTANT_AUTH_FAILED");
    assertThat(error.getMessage()).contains("联系管理员检查服务配置").doesNotContain("internal-token-error");
    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void reportsUnauthorizedHealthAsAuthFailedWithoutLeakingUpstreamDetails() throws Exception {
    startServer(
        exchange -> respond(exchange, 200, "{\"answer\":\"ignored\"}"),
        exchange -> respond(exchange, 401, "{\"detail\":\"token expired\"}")
    );
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = service(baseUrl(), "wrong-token", auditRepository);

    EmployeeAssistantStatusResponse result = service.health(user());

    assertThat(result).isEqualTo(new EmployeeAssistantStatusResponse(
        false,
        true,
        EmployeeAssistantState.AUTH_FAILED,
        "员工服务助手授权异常，请联系管理员检查服务配置",
        false,
        false
    ));
    ArgumentCaptor<AuditLogRequest> capture = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(AuthUser.class), capture.capture());
    assertThat(capture.getValue().reason()).isEqualTo("state=AUTH_FAILED").doesNotContain("token expired");
  }

  @Test
  void reportsTimeoutAsUnavailableAndAuditsSafeState() throws Exception {
    startServer(
        exchange -> respond(exchange, 200, "{\"answer\":\"ignored\"}"),
        exchange -> {
          try {
            Thread.sleep(250);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          respond(exchange, 200, "{} ");
        }
    );
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = service(baseUrl(), "test-token", auditRepository, Duration.ofMillis(50));

    EmployeeAssistantStatusResponse result = service.health(user());

    assertThat(result.state()).isEqualTo(EmployeeAssistantState.UNAVAILABLE);
    assertThat(result.enabled()).isFalse();
    assertThat(result.configured()).isTrue();
    assertThat(result.message()).contains("响应超时");
    ArgumentCaptor<AuditLogRequest> capture = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(AuthUser.class), capture.capture());
    assertThat(capture.getValue().reason()).isEqualTo("state=UNAVAILABLE");
  }

  @Test
  void reportsReadyHealthStateAndUsesBearerToken() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    startServer(
        exchange -> respond(exchange, 200, "{\"answer\":\"ignored\"}"),
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          respond(exchange, 204, "");
        }
    );
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = service(baseUrl(), "test-token", auditRepository);

    EmployeeAssistantStatusResponse result = service.health(user());

    assertThat(result).isEqualTo(new EmployeeAssistantStatusResponse(
        true,
        true,
        EmployeeAssistantState.READY,
        "员工服务助手已就绪",
        false,
        true
    ));
    assertThat(authorization.get()).isEqualTo("Bearer test-token");
  }

  @Test
  void explicitlyEnabledModelProviderUsesOnlyEmployeeSpecificConfigurationAndSafePayload() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    startModelServer(authorization, requestBody);
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = new EmployeeAssistantService(
        "MODEL",
        "",
        "",
        baseUrl(),
        "employee-model-token",
        "employee-support-model",
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        new ObjectMapper(),
        auditRepository,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        null,
        true
    );

    assertThat(service.health(user()).state()).isEqualTo(EmployeeAssistantState.READY);
    EmployeeAssistantChatResponse response = service.chat(
        user(),
        new EmployeeAssistantChatRequest("2d77c1d7-1f23-4d11-a49e-2ca6e3f9d908", "顾客投诉等待太久，怎么回应？")
    );

    assertThat(response.answer()).isEqualTo("先致歉，再说明将立即跟进处理。");
    assertThat(authorization.get()).isEqualTo("Bearer employee-model-token");
    assertThat(requestBody.get())
        .contains("employee-support-model")
        .contains("会话编号：2d77c1d7-1f23-4d11-a49e-2ca6e3f9d908")
        .contains("顾客投诉等待太久，怎么回应？")
        .contains("可以这样说", "员工怎么处理", "什么时候转人工", "小票", "支付记录")
        .doesNotContain("DEEPSEEK_API_KEY")
        .doesNotContain("营业额");
  }

  @Test
  void modelSelectionWithoutEmployeeModelVariablesRemainsUnconfiguredAndNeverFallsBackToRemote() {
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = new EmployeeAssistantService(
        "MODEL",
        "https://remote.example.test",
        "remote-token-must-not-be-used",
        "",
        "",
        "",
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        new ObjectMapper(),
        auditRepository,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
    );

    EmployeeAssistantStatusResponse result = service.health(user());

    assertThat(result.state()).isEqualTo(EmployeeAssistantState.UNCONFIGURED);
    assertThat(result.configured()).isFalse();
    assertThat(result.enabled()).isFalse();
  }

  @Test
  void mixedProviderConfigurationIsRejectedWithoutLeakingEitherModeValues() {
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = new EmployeeAssistantService(
        "REMOTE",
        "https://assistant.example.test/health?must-not-appear",
        "remote-secret-must-not-appear",
        "https://model.example.test/v1?must-not-appear",
        "model-secret-must-not-appear",
        "employee-support",
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        new ObjectMapper(),
        auditRepository,
        HttpClient.newHttpClient()
    );

    EmployeeAssistantStatusResponse result = service.health(user());

    assertThat(result.state()).isEqualTo(EmployeeAssistantState.UNCONFIGURED);
    assertThat(result.configured()).isFalse();
    assertThat(result.message())
        .doesNotContain("must-not-appear")
        .doesNotContain("remote-secret")
        .doesNotContain("model-secret");
    ArgumentCaptor<AuditLogRequest> capture = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(AuthUser.class), capture.capture());
    assertThat(capture.getValue().reason())
        .isEqualTo("state=UNCONFIGURED")
        .doesNotContain("must-not-appear")
        .doesNotContain("secret");
  }

  @Test
  void approvedKnowledgeAnswersLocallyBeforeAnyProviderAndKeepsOnlyRedactedInputInAudit() {
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantKnowledgeRepository knowledgeRepository = mock(EmployeeAssistantKnowledgeRepository.class);
    when(knowledgeRepository.publishedKnowledge(1L)).thenReturn(List.of(knowledge(11L, "漏发吸管处理", "漏发,吸管")));
    EmployeeAssistantService service = service("https://unused.example.test", "test-token", auditRepository,
        knowledgeRepository);

    long startedAt = System.nanoTime();
    EmployeeAssistantChatResponse response = service.chat(user(),
        new EmployeeAssistantChatRequest(null, "漏发吸管处理时顾客电话是13800138000，怎么答复？"));
    long totalMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    assertThat(response.answer()).isEqualTo("先致歉并立即补发，必要时请值班经理协助。");
    assertThat(response.answerSource()).isEqualTo(EmployeeAssistantAnswerSource.KNOWLEDGE);
    assertThat(response.knowledgeId()).isEqualTo(11L);
    assertThat(response.knowledgeVersion()).isEqualTo(2);
    assertThat(totalMillis).isLessThan(800L);
    ArgumentCaptor<AuditLogRequest> capture = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(AuthUser.class), capture.capture());
    assertThat(capture.getValue().reason()).contains("SUCCESS_KNOWLEDGE").contains("input_redacted=true")
        .contains("model_ms=0", "total_ms=")
        .doesNotContain("13800138000");
  }

  @Test
  void highRiskQuestionForcesHumanHandoffWithoutCallingProvider() {
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantService service = service("https://provider-must-not-be-called.example.test", "test-token", auditRepository);

    EmployeeAssistantChatResponse response = service.chat(user(),
        new EmployeeAssistantChatRequest(null, "顾客反映食品安全问题，需要怎么处理？"));

    assertThat(response.needsHuman()).isTrue();
    assertThat(response.answerSource()).isEqualTo(EmployeeAssistantAnswerSource.HUMAN_REQUIRED);
    assertThat(response.handoffCategory()).isEqualTo("FOOD_SAFETY");
    assertThat(response.answer()).contains("转人工处理");
    ArgumentCaptor<AuditLogRequest> capture = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(AuthUser.class), capture.capture());
    assertThat(capture.getValue().reason()).contains("HUMAN_REQUIRED_FOOD_SAFETY");
  }

  @Test
  void refundQuestionUsesTheFixedPrivacySafeHandoffReplyWithoutCallingProvider() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    startServer(exchange -> {
      calls.incrementAndGet();
      respond(exchange, 200, "{\"answer\":\"should not be used\"}");
    });
    AuditRepository auditRepository = mock(AuditRepository.class);
    EmployeeAssistantKnowledgeRepository knowledgeRepository = mock(EmployeeAssistantKnowledgeRepository.class);
    EmployeeAssistantService service = service(baseUrl(), "test-token", auditRepository, knowledgeRepository);

    long startedAt = System.nanoTime();
    EmployeeAssistantChatResponse response = service.chat(user(),
        new EmployeeAssistantChatRequest(null, "顾客申请退款时，员工应该怎么回复？"));
    long totalMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    assertThat(response.answerSource()).isEqualTo(EmployeeAssistantAnswerSource.HUMAN_REQUIRED);
    assertThat(response.needsHuman()).isTrue();
    assertThat(response.handoffCategory()).isEqualTo("REFUND_REVIEW");
    assertThat(response.answer()).contains("现有业务系统内按门店规则核验", "值班负责人")
        .doesNotContain("请提供", "请发送", "请上传");
    assertThat(calls).hasValue(0);
    verifyNoInteractions(knowledgeRepository);
    assertThat(totalMillis).isLessThan(500L);
    ArgumentCaptor<AuditLogRequest> audit = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(AuthUser.class), audit.capture());
    assertThat(audit.getValue().reason())
        .contains("HUMAN_REQUIRED_REFUND", "knowledge_ms=0", "model_ms=0", "total_ms=")
        .doesNotContain("顾客申请退款");
  }

  @Test
  void unsafeProviderOutputIsReplacedWithAPrivacySafeHandoffReply() throws Exception {
    startServer(exchange -> respond(exchange, 200,
        "{\"answer\":\"订单号发过来，再把小票、支付记录和附件传来\",\"needs_human\":false}"));
    EmployeeAssistantService service = service(baseUrl(), "test-token", mock(AuditRepository.class));

    EmployeeAssistantChatResponse response = service.chat(user(),
        new EmployeeAssistantChatRequest(null, "顾客投诉等待太久，怎么回复？"));

    assertThat(response.answerSource()).isEqualTo(EmployeeAssistantAnswerSource.HUMAN_REQUIRED);
    assertThat(response.needsHuman()).isTrue();
    assertThat(response.handoffCategory()).isEqualTo("OUTPUT_SAFETY");
    assertThat(response.answer()).contains("不索要或发送顾客姓名", "转值班负责人")
        .doesNotContain("订单号发过来", "支付记录和附件传来");
  }

  @Test
  void lowRiskProviderAnswerCacheIsIsolatedByStoreScope() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    startServer(exchange -> {
      calls.incrementAndGet();
      respond(exchange, 200, "{\"answer\":\"先致歉并说明会立即跟进。\",\"needs_human\":false}");
    });
    EmployeeAssistantService service = service(baseUrl(), "test-token", mock(AuditRepository.class));
    EmployeeAssistantChatRequest request = new EmployeeAssistantChatRequest(null, "顾客投诉等待太久，怎么回应？");

    EmployeeAssistantChatResponse first = service.chat(user("s1"), request);
    EmployeeAssistantChatResponse second = service.chat(user("s1"), request);
    EmployeeAssistantChatResponse otherStore = service.chat(user("s2"), request);

    assertThat(first.answer()).isEqualTo(second.answer());
    assertThat(otherStore.answer()).isEqualTo(first.answer());
    assertThat(calls).hasValue(2);
  }

  @Test
  void publishedKnowledgeVersionChangeInvalidatesLowRiskAnswerCache() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    startServer(exchange -> {
      calls.incrementAndGet();
      respond(exchange, 200, "{\"answer\":\"先致歉并说明会立即跟进。\",\"needs_human\":false}");
    });
    AtomicReference<List<EmployeeAssistantKnowledgeRepository.KnowledgeRow>> published =
        new AtomicReference<>(List.of(knowledge(31L, "交班规范", "交班")));
    EmployeeAssistantKnowledgeRepository knowledgeRepository = mock(EmployeeAssistantKnowledgeRepository.class);
    when(knowledgeRepository.publishedKnowledge(1L)).thenAnswer(ignored -> published.get());
    EmployeeAssistantService service = service(baseUrl(), "test-token", mock(AuditRepository.class), knowledgeRepository);
    EmployeeAssistantChatRequest request = new EmployeeAssistantChatRequest(null, "顾客投诉等待太久，怎么回应？");

    service.chat(user(), request);
    published.set(List.of(new EmployeeAssistantKnowledgeRepository.KnowledgeRow(
        31L, 1L, "SERVICE", "交班规范", "交班", "更新后的交班话术", "PUBLISHED", 3, 9L, 9L,
        LocalDateTime.of(2026, 7, 14, 9, 0), LocalDateTime.of(2026, 7, 15, 9, 0))));
    service.chat(user(), request);

    assertThat(calls).hasValue(2);
  }

  @Test
  void upstreamGatewayTimeoutReturnsSafeHandoffInsteadOfAnError() throws Exception {
    startServer(exchange -> respond(exchange, 504, "{\"detail\":\"internal gateway detail\"}"));
    EmployeeAssistantService service = service(baseUrl(), "test-token", mock(AuditRepository.class));

    EmployeeAssistantChatResponse response = service.chat(user(),
        new EmployeeAssistantChatRequest(null, "顾客投诉等待太久，怎么回应？"));

    assertThat(response.needsHuman()).isTrue();
    assertThat(response.handoffCategory()).isEqualTo("UPSTREAM_TIMEOUT");
    assertThat(response.answer()).contains("什么时候转人工", "值班负责人")
        .doesNotContain("internal gateway detail");
  }

  @Test
  void providerTimeoutReturnsAReusableSafeHandoffReply() throws Exception {
    startServer(exchange -> {
      try {
        Thread.sleep(250);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      respond(exchange, 200, "{\"answer\":\"late\"}");
    });
    EmployeeAssistantService service = service(baseUrl(), "test-token", mock(AuditRepository.class), Duration.ofMillis(50));

    EmployeeAssistantChatResponse response = service.chat(user(),
        new EmployeeAssistantChatRequest(null, "顾客投诉等待太久，怎么回应？"));

    assertThat(response.answerSource()).isEqualTo(EmployeeAssistantAnswerSource.HUMAN_REQUIRED);
    assertThat(response.needsHuman()).isTrue();
    assertThat(response.handoffCategory()).isEqualTo("UPSTREAM_TIMEOUT");
    assertThat(response.answer()).contains("转值班负责人", "不在聊天中补充顾客或订单信息");
  }

  @Test
  void providerFallbackReceivesAtMostThreePublishedKnowledgeSnippets() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    startServer(exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"answer\":\"请按员工助手建议处理\",\"needs_human\":false}");
    });
    EmployeeAssistantKnowledgeRepository knowledgeRepository = mock(EmployeeAssistantKnowledgeRepository.class);
    when(knowledgeRepository.publishedKnowledge(1L)).thenReturn(List.of(
        knowledge(1L, "参考一", "漏发,到店"), knowledge(2L, "参考二", "漏发,配送"),
        knowledge(3L, "参考三", "漏发,补发"), knowledge(4L, "参考四", "漏发,赔付")
    ));
    EmployeeAssistantService service = service(baseUrl(), "test-token", mock(AuditRepository.class), knowledgeRepository);

    EmployeeAssistantChatResponse response = service.chat(user(), new EmployeeAssistantChatRequest(null, "漏发要怎么处理？"));

    assertThat(response.answerSource()).isEqualTo(EmployeeAssistantAnswerSource.ASSISTANT);
    assertThat(requestBody.get()).contains("参考一", "参考二", "参考三").doesNotContain("参考四");
    assertThat(requestBody.get()).doesNotContain("13800138000");
  }

  @Test
  void sensitivePublishedKnowledgeIsNeverReturnedOrForwardedToTheProvider() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    startServer(exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"answer\":\"请按服务规范处理\",\"needs_human\":false}");
    });
    EmployeeAssistantKnowledgeRepository knowledgeRepository = mock(EmployeeAssistantKnowledgeRepository.class);
    when(knowledgeRepository.publishedKnowledge(1L)).thenReturn(List.of(
        knowledge(1L, "客户联系方式", "漏发,处理", "客户电话 13800138000，请直接联系顾客"),
        knowledge(3L, "订单查询要求", "漏发,处理", "订单号发过来，再把小票和支付记录传来"),
        knowledge(2L, "补发流程", "漏发,补发", "先致歉并立即补发。")
    ));
    EmployeeAssistantService service = service(baseUrl(), "test-token", mock(AuditRepository.class), knowledgeRepository);

    EmployeeAssistantChatResponse response = service.chat(user(), new EmployeeAssistantChatRequest(null, "漏发怎么处理？"));

    assertThat(response.answerSource()).isEqualTo(EmployeeAssistantAnswerSource.ASSISTANT);
    assertThat(requestBody.get())
        .doesNotContain("客户联系方式", "13800138000", "客户电话", "订单查询要求", "订单号发过来")
        .contains("补发流程");
  }

  @Test
  void unsafePublishedKnowledgeDoesNotAdvertiseLocalQuestionCapability() {
    EmployeeAssistantKnowledgeRepository knowledgeRepository = mock(EmployeeAssistantKnowledgeRepository.class);
    when(knowledgeRepository.publishedKnowledge(1L)).thenReturn(List.of(
        knowledge(1L, "客户联系方式", "漏发,处理", "客户电话 13800138000，请直接联系顾客")
    ));
    EmployeeAssistantService service = new EmployeeAssistantService(
        "", "", "", "", "", "", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ObjectMapper(), mock(AuditRepository.class), HttpClient.newHttpClient(), knowledgeRepository, false);

    EmployeeAssistantStatusResponse status = service.health(user());

    assertThat(status.state()).isEqualTo(EmployeeAssistantState.UNCONFIGURED);
    assertThat(status.knowledgeAvailable()).isFalse();
    assertThat(status.canAsk()).isFalse();
  }

  private EmployeeAssistantService service(String url, String token, AuditRepository auditRepository) {
    return service(url, token, auditRepository, Duration.ofSeconds(2), true);
  }

  private EmployeeAssistantService service(
      String url,
      String token,
      AuditRepository auditRepository,
      Duration timeout
  ) {
    return service(url, token, auditRepository, timeout, true);
  }

  private EmployeeAssistantService service(
      String url,
      String token,
      AuditRepository auditRepository,
      Duration timeout,
      boolean runtimeSecured
  ) {
    return new EmployeeAssistantService(
        "REMOTE", url, token, "", "", "",
        Duration.ofSeconds(1),
        timeout,
        new ObjectMapper(),
        auditRepository,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        null,
        runtimeSecured
    );
  }

  private EmployeeAssistantService service(
      String url,
      String token,
      AuditRepository auditRepository,
      EmployeeAssistantKnowledgeRepository knowledgeRepository
  ) {
    return new EmployeeAssistantService(
        "REMOTE", url, token, "", "", "", Duration.ofSeconds(1), Duration.ofSeconds(2),
        new ObjectMapper(), auditRepository, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        knowledgeRepository, true
    );
  }

  private EmployeeAssistantKnowledgeRepository.KnowledgeRow knowledge(long id, String title, String keywords) {
    return knowledge(id, title, keywords, "先致歉并立即补发，必要时请值班经理协助。");
  }

  private EmployeeAssistantKnowledgeRepository.KnowledgeRow knowledge(
      long id,
      String title,
      String keywords,
      String standardAnswer
  ) {
    return new EmployeeAssistantKnowledgeRepository.KnowledgeRow(id, 1L, "SERVICE", title, keywords,
        standardAnswer, "PUBLISHED", 2, 9L, 9L,
        LocalDateTime.of(2026, 7, 14, 9, 0), LocalDateTime.of(2026, 7, 14, 9, 0));
  }

  private void startServer(ExchangeHandler handler) throws IOException {
    startServer(handler, exchange -> respond(exchange, 200, "{}"));
  }

  private void startServer(ExchangeHandler chatHandler, ExchangeHandler healthHandler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/chat", exchange -> chatHandler.handle(exchange));
    server.createContext("/api/v1/health", exchange -> healthHandler.handle(exchange));
    server.start();
  }

  private void startModelServer(
      AtomicReference<String> authorization,
      AtomicReference<String> requestBody
  ) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/models", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      respond(exchange, 200, "{\"data\":[]}");
    });
    server.createContext("/chat/completions", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"先致歉，再说明将立即跟进处理。\"}}]}");
    });
    server.start();
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private AuthUser user() {
    return user("s1");
  }

  private AuthUser user(String storeId) {
    return new AuthUser(8L, 1L, "测试租户", "test-user", "hash", "测试用户", "STORE_MANAGER", storeId, true, 3L);
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
