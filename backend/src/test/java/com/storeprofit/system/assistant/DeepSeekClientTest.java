package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeepSeekClientTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private HttpServer server;
  private DeepSeekProperties properties;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    properties = new DeepSeekProperties();
    properties.setEnabled(true);
    properties.setApiKey("test-key-never-logged");
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setModel("configured-model");
    properties.setConnectTimeout(Duration.ofSeconds(2));
    properties.setTimeout(Duration.ofSeconds(2));
    properties.setMaxRequestsPerMinute(100);
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.stop(0);
  }

  @Test
  void sendsJsonObjectRequestAndReturnsProviderIdentity() {
    AtomicReference<String> requestBody = new AtomicReference<>();
    server.createContext("/chat/completions", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key-never-logged");
      respond(exchange, 200, successResponse(
          "req-real-123", "deepseek-chat-real", "{\"summary\":\"真实分析\"}"));
    });

    DeepSeekCallResult result = new DeepSeekClient(properties, objectMapper)
        .analyze("system prompt", "user prompt");

    assertThat(result.requestId()).isEqualTo("req-real-123");
    assertThat(result.provider()).isEqualTo("DeepSeek");
    assertThat(result.model()).isEqualTo("deepseek-chat-real");
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.content()).contains("真实分析");
    JsonNode sent = readTree(requestBody.get());
    assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
    assertThat(sent.path("stream").asBoolean()).isFalse();
    assertThat(properties.getLastSuccessAt()).isNotNull();
    assertThat(properties.getLastErrorCode()).isNull();
  }

  @Test
  void automaticProfileUsesFastModelAndSmallerResponseBudget() {
    AtomicReference<String> requestBody = new AtomicReference<>();
    properties.setFastModel("fast-model");
    properties.setFastMaxTokens(640);
    server.createContext("/chat/completions", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, successResponse("req-fast", "fast-model", "{\"summary\":\"ok\"}"));
    });

    new DeepSeekClient(properties, objectMapper)
        .analyzeFast("system prompt", "user prompt", Duration.ofSeconds(1));

    JsonNode sent = readTree(requestBody.get());
    assertThat(sent.path("model").asText()).isEqualTo("fast-model");
    assertThat(sent.path("max_tokens").asInt()).isEqualTo(640);
  }

  @Test
  void retriesRateLimitOnlyOnceThenSucceeds() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext("/chat/completions", exchange -> {
      if (calls.incrementAndGet() == 1) {
        respond(exchange, 429, "{}");
      } else {
        respond(exchange, 200, successResponse(
            "req-after-retry", "deepseek-retry-model", "{\"summary\":\"ok\"}"));
      }
    });

    DeepSeekCallResult result = new DeepSeekClient(properties, objectMapper)
        .analyze("system", "user");

    assertThat(calls).hasValue(2);
    assertThat(result.requestId()).isEqualTo("req-after-retry");
  }

  @Test
  void retriesWithoutJsonResponseFormatWhenJsonModeReturnsEmptyContent() {
    AtomicInteger calls = new AtomicInteger();
    List<JsonNode> requests = new ArrayList<>();
    server.createContext("/chat/completions", exchange -> {
      requests.add(readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      if (calls.incrementAndGet() == 1) {
        respond(exchange, 200, objectMapper.writeValueAsString(Map.of(
            "id", "empty-json-mode",
            "model", "deepseek-v4-pro",
            "choices", List.of(Map.of(
                "finish_reason", "length",
                "message", Map.of(
                    "content", "",
                    "reasoning_content", "reasoning consumed the response budget"
                )
            ))
        )));
      } else {
        respond(exchange, 200, successResponse(
            "fallback-without-json-mode", "deepseek-v4-pro", "{\"status\":\"OK\"}"));
      }
    });

    DeepSeekCallResult result = new DeepSeekClient(properties, objectMapper)
        .analyze("system", "user");

    assertThat(calls).hasValue(2);
    assertThat(requests.get(0).path("response_format").path("type").asText()).isEqualTo("json_object");
    assertThat(requests.get(1).has("response_format")).isFalse();
    assertThat(result.requestId()).isEqualTo("fallback-without-json-mode");
    assertThat(result.content()).isEqualTo("{\"status\":\"OK\"}");
  }

  @Test
  void doesNotRetryPermanentAuthenticationFailure() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext("/chat/completions", exchange -> {
      calls.incrementAndGet();
      respond(exchange, 401, "{}");
    });

    DeepSeekClient client = new DeepSeekClient(properties, objectMapper);

    assertThatThrownBy(() -> client.analyze("system", "user"))
        .isInstanceOfSatisfying(DeepSeekException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo("DEEPSEEK_AUTH_FAILED");
          assertThat(failure.getHttpStatus()).isEqualTo(401);
        });
    assertThat(calls).hasValue(1);
    assertThat(properties.getLastErrorCode()).isEqualTo("DEEPSEEK_AUTH_FAILED");
  }

  @Test
  void doesNotRetryInvalidRequest() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext("/chat/completions", exchange -> {
      calls.incrementAndGet();
      respond(exchange, 422, "{}");
    });

    DeepSeekClient client = new DeepSeekClient(properties, objectMapper);

    assertThatThrownBy(() -> client.analyze("system", "user"))
        .isInstanceOfSatisfying(DeepSeekException.class,
            failure -> assertThat(failure.getCode()).isEqualTo("DEEPSEEK_INVALID_REQUEST"));
    assertThat(calls).hasValue(1);
  }

  @Test
  void rejectsMissingKeyBeforeNetworkCall() {
    properties.setApiKey("");
    DeepSeekClient client = new DeepSeekClient(properties, objectMapper);

    assertThatThrownBy(() -> client.analyze("system", "user"))
        .isInstanceOfSatisfying(DeepSeekException.class,
            failure -> assertThat(failure.getCode()).isEqualTo("DEEPSEEK_NOT_CONFIGURED"));
  }

  @Test
  void rejectsAnExpiredLogicalAnalysisBudgetBeforeNetworkCall() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext("/chat/completions", exchange -> {
      calls.incrementAndGet();
      respond(exchange, 200, successResponse("unexpected", "configured-model", "{}"));
    });
    DeepSeekClient client = new DeepSeekClient(properties, objectMapper);

    assertThatThrownBy(() -> client.analyze("system", "user", Duration.ZERO))
        .isInstanceOfSatisfying(DeepSeekException.class,
            failure -> assertThat(failure.getCode()).isEqualTo("DEEPSEEK_ANALYSIS_TIMEOUT"));
    assertThat(calls).hasValue(0);
  }

  @Test
  void logicalAnalysisBudgetAlsoStopsTransportRetry() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext("/chat/completions", exchange -> {
      calls.incrementAndGet();
      respond(exchange, 503, "{}");
    });
    DeepSeekClient client = new DeepSeekClient(properties, objectMapper);

    assertThatThrownBy(() -> client.analyze("system", "user", Duration.ofMillis(250)))
        .isInstanceOfSatisfying(DeepSeekException.class,
            failure -> assertThat(failure.getCode()).isEqualTo("DEEPSEEK_ANALYSIS_TIMEOUT"));
    assertThat(calls).hasValue(1);
  }

  @Test
  void opensCircuitAfterConfiguredNumberOfTransientFailures() {
    AtomicInteger calls = new AtomicInteger();
    properties.setCircuitFailureThreshold(1);
    properties.setCircuitOpenDuration(Duration.ofSeconds(30));
    server.createContext("/chat/completions", exchange -> {
      calls.incrementAndGet();
      respond(exchange, 503, "{}");
    });
    DeepSeekClient client = new DeepSeekClient(properties, objectMapper);

    assertThatThrownBy(() -> client.analyze("system", "user"))
        .isInstanceOfSatisfying(DeepSeekException.class,
            failure -> assertThat(failure.getCode()).isEqualTo("DEEPSEEK_UNAVAILABLE"));
    assertThat(calls).hasValue(2);

    assertThatThrownBy(() -> client.analyze("system", "user"))
        .isInstanceOfSatisfying(DeepSeekException.class,
            failure -> assertThat(failure.getCode()).isEqualTo("DEEPSEEK_CIRCUIT_OPEN"));
    assertThat(calls).hasValue(2);
  }

  private JsonNode readTree(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (IOException ex) {
      throw new AssertionError(ex);
    }
  }

  private String successResponse(String id, String model, String content) {
    try {
      return objectMapper.writeValueAsString(Map.of(
          "id", id,
          "model", model,
          "choices", List.of(Map.of("message", Map.of("content", content)))
      ));
    } catch (IOException ex) {
      throw new AssertionError(ex);
    }
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
