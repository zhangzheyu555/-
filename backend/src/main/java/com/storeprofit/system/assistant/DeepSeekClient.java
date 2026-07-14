package com.storeprofit.system.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Isolated DeepSeek transport client. It never logs credentials, prompts or provider bodies.
 */
@Component
public class DeepSeekClient {
  private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
  private static final int MAX_ATTEMPTS = 2;
  private static final Duration RETRY_DELAY = Duration.ofMillis(250);

  private final DeepSeekProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final ArrayDeque<Instant> requestWindow = new ArrayDeque<>();
  private final AtomicInteger consecutiveTransientFailures = new AtomicInteger();
  private volatile Instant circuitOpenUntil;

  @Autowired
  public DeepSeekClient(DeepSeekProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build());
  }

  DeepSeekClient(DeepSeekProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  /**
   * Calls the provider and returns only a genuine successful response.
   * A timeout, HTTP 429, or temporary 5xx is retried once; permanent 4xx errors are never retried.
   */
  public DeepSeekCallResult analyze(String systemPrompt, String userPrompt) {
    requireConfigured();
    requireCircuitClosed();
    acquireRateLimitPermit();

    DeepSeekException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        DeepSeekCallResult result = execute(systemPrompt, userPrompt);
        consecutiveTransientFailures.set(0);
        circuitOpenUntil = null;
        properties.markSuccess();
        log.info("DeepSeek request succeeded provider={} requestId={} model={} status={} latencyMs={} attempt={}",
            result.provider(), safeLogValue(result.requestId()), safeLogValue(result.model()),
            result.httpStatus(), result.latencyMs(), attempt);
        return result;
      } catch (DeepSeekException failure) {
        lastFailure = failure;
        if (attempt >= MAX_ATTEMPTS || !isRetryable(failure)) {
          recordFailure(failure);
          throw failure;
        }
        log.warn("DeepSeek request retry code={} status={} attempt={}/{}",
            failure.getCode(), failure.getHttpStatus(), attempt, MAX_ATTEMPTS);
        waitBeforeRetry();
      }
    }
    recordFailure(lastFailure);
    throw lastFailure;
  }

  private DeepSeekCallResult execute(String systemPrompt, String userPrompt) {
    long startedAt = System.nanoTime();
    HttpRequest request;
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", properties.getModel());
      body.put("messages", List.of(
          Map.of("role", "system", "content", nullToEmpty(systemPrompt)),
          Map.of("role", "user", "content", nullToEmpty(userPrompt))
      ));
      body.put("temperature", properties.getTemperature());
      body.put("max_tokens", properties.getMaxTokens());
      body.put("stream", false);
      body.put("response_format", Map.of("type", "json_object"));

      request = HttpRequest.newBuilder()
          .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
          .timeout(properties.getTimeout())
          .header("Authorization", "Bearer " + properties.getApiKey())
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(
              objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
          .build();
    } catch (IllegalArgumentException | IOException ex) {
      throw new DeepSeekException(
          "DEEPSEEK_CONFIGURATION_INVALID", "AI服务连接配置无效，请管理员检查配置。", 0);
    }

    try {
      HttpResponse<String> response = httpClient.send(
          request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int status = response.statusCode();
      long latencyMs = elapsedMillis(startedAt);
      if (status < 200 || status >= 300) {
        throw classifyHttpError(status);
      }

      JsonNode root;
      try {
        root = objectMapper.readTree(response.body());
      } catch (IOException ex) {
        throw new DeepSeekException(
            "DEEPSEEK_RESPONSE_INVALID", "AI返回格式无法解析。", status);
      }
      String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
      if (content.isEmpty()) {
        throw new DeepSeekException("DEEPSEEK_EMPTY_RESPONSE", "AI返回内容为空。", status);
      }
      String requestId = root.path("id").asText("").trim();
      if (requestId.isEmpty()) {
        requestId = response.headers().firstValue("x-request-id").orElse("").trim();
      }
      String actualModel = root.path("model").asText("").trim();
      return new DeepSeekCallResult(content, requestId, actualModel, status, latencyMs);
    } catch (HttpTimeoutException ex) {
      throw new DeepSeekException("DEEPSEEK_TIMEOUT", "AI服务响应超时，请稍后重试。", 0);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new DeepSeekException("DEEPSEEK_CANCELLED", "AI分析已取消。", 0);
    } catch (IOException ex) {
      throw new DeepSeekException("DEEPSEEK_NETWORK_ERROR", "AI服务网络连接失败。", 0);
    }
  }

  private DeepSeekException classifyHttpError(int status) {
    return switch (status) {
      case 400, 422 -> new DeepSeekException(
          "DEEPSEEK_INVALID_REQUEST", "AI请求参数错误，请稍后重试。", status);
      case 401, 403 -> new DeepSeekException(
          "DEEPSEEK_AUTH_FAILED", "AI服务认证失败，请管理员检查配置。", status);
      case 402 -> new DeepSeekException(
          "DEEPSEEK_BALANCE_INSUFFICIENT", "AI账户余额不足，请管理员处理。", status);
      case 404 -> new DeepSeekException(
          "DEEPSEEK_MODEL_NOT_FOUND", "AI模型不可用，请管理员检查模型配置。", status);
      case 429 -> new DeepSeekException(
          "DEEPSEEK_RATE_LIMITED", "AI请求过于频繁，请稍后再试。", status);
      default -> {
        if (status >= 500 && status <= 599) {
          yield new DeepSeekException(
              "DEEPSEEK_UNAVAILABLE", "AI服务暂时不可用，请稍后重试。", status);
        }
        yield new DeepSeekException(
            "DEEPSEEK_REQUEST_FAILED", "AI请求失败，请稍后重试。", status);
      }
    };
  }

  private boolean isRetryable(DeepSeekException failure) {
    int status = failure.getHttpStatus();
    return "DEEPSEEK_TIMEOUT".equals(failure.getCode())
        || status == 429
        || (status >= 500 && status <= 599);
  }

  private void requireConfigured() {
    if (!properties.isEnabled()) {
      DeepSeekException failure = new DeepSeekException(
          "DEEPSEEK_DISABLED", "AI分析服务尚未启用。", 0);
      properties.markFailure(failure.getCode());
      throw failure;
    }
    if (!properties.hasApiKey()) {
      DeepSeekException failure = new DeepSeekException(
          "DEEPSEEK_NOT_CONFIGURED", "AI分析服务尚未配置，本地经营数据仍可正常查询。", 0);
      properties.markFailure(failure.getCode());
      throw failure;
    }
  }

  private void requireCircuitClosed() {
    Instant openUntil = circuitOpenUntil;
    if (openUntil == null) return;
    if (!openUntil.isAfter(Instant.now())) {
      circuitOpenUntil = null;
      consecutiveTransientFailures.set(0);
      return;
    }
    DeepSeekException failure = new DeepSeekException(
        "DEEPSEEK_CIRCUIT_OPEN", "AI服务暂时不可用，请稍后重试。", 0);
    properties.markFailure(failure.getCode());
    throw failure;
  }

  private void acquireRateLimitPermit() {
    Instant now = Instant.now();
    Instant windowStart = now.minusSeconds(60);
    synchronized (requestWindow) {
      while (!requestWindow.isEmpty() && requestWindow.peekFirst().isBefore(windowStart)) {
        requestWindow.removeFirst();
      }
      if (requestWindow.size() >= properties.getMaxRequestsPerMinute()) {
        DeepSeekException failure = new DeepSeekException(
            "DEEPSEEK_RATE_LIMITED_LOCAL", "AI分析请求过于频繁，请稍后再试。", 0);
        properties.markFailure(failure.getCode());
        throw failure;
      }
      requestWindow.addLast(now);
    }
  }

  private void recordFailure(DeepSeekException failure) {
    if (failure == null) return;
    properties.markFailure(failure.getCode());
    if (isRetryable(failure)) {
      int failures = consecutiveTransientFailures.incrementAndGet();
      if (failures >= properties.getCircuitFailureThreshold()) {
        circuitOpenUntil = Instant.now().plus(properties.getCircuitOpenDuration());
        log.warn("DeepSeek circuit opened code={} failures={} openSeconds={}",
            failure.getCode(), failures, properties.getCircuitOpenDuration().toSeconds());
      }
    }
    log.warn("DeepSeek request failed code={} status={}", failure.getCode(), failure.getHttpStatus());
  }

  private void waitBeforeRetry() {
    try {
      Thread.sleep(RETRY_DELAY.toMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      DeepSeekException failure = new DeepSeekException(
          "DEEPSEEK_CANCELLED", "AI分析已取消。", 0);
      properties.markFailure(failure.getCode());
      throw failure;
    }
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private long elapsedMillis(long startedAt) {
    return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
  }

  private String safeLogValue(String value) {
    if (value == null || value.isBlank()) return "unavailable";
    String sanitized = value.replaceAll("[\\p{Cntrl}\\r\\n\\t]", "").trim();
    return sanitized.length() <= 48 ? sanitized : sanitized.substring(0, 48) + "...";
  }
}
