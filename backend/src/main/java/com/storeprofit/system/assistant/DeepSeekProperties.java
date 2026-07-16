package com.storeprofit.system.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.assistant.deepseek")
public class DeepSeekProperties {
  private boolean enabled = true;
  private String apiKey = "";
  private String baseUrl = "https://api.deepseek.com";
  private String model = "deepseek-v4-flash";
  private int maxTokens = 1200;
  private double temperature = 0.2;
  private Duration connectTimeout = Duration.ofSeconds(5);
  private Duration timeout = Duration.ofSeconds(45);
  /**
   * Hard budget for one business-analysis operation, including a possible schema repair call.
   * This is deliberately separate from {@link #timeout}, which bounds one HTTP request.
   */
  private Duration analysisTimeout = Duration.ofSeconds(30);
  private int maxRequestsPerMinute = 30;
  private int circuitFailureThreshold = 3;
  private Duration circuitOpenDuration = Duration.ofSeconds(30);
  private List<String> blockedWords = new ArrayList<>();

  private volatile Instant lastSuccessAt;
  private volatile Instant lastFailureAt;
  private volatile String lastFailureCode;
  /** Last business-analysis outcome, deliberately separate from a successful HTTP response. */
  private volatile String lastAnalysisState;
  private volatile Instant lastValidatedAnalysisAt;
  private volatile String lastAnalysisErrorCode;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isConfigured() {
    return enabled && hasApiKey();
  }

  public boolean hasApiKey() {
    return apiKey != null && !apiKey.isBlank();
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = trimTrailingSlash(baseUrl);
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model == null || model.isBlank() ? "deepseek-v4-flash" : model.trim();
  }

  public int getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
  }

  public double getTemperature() {
    return temperature;
  }

  public void setTemperature(double temperature) {
    this.temperature = temperature;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout == null ? Duration.ofSeconds(45) : timeout;
  }

  public Duration getAnalysisTimeout() {
    return analysisTimeout;
  }

  public void setAnalysisTimeout(Duration analysisTimeout) {
    this.analysisTimeout = analysisTimeout == null || analysisTimeout.isZero() || analysisTimeout.isNegative()
        ? Duration.ofSeconds(30)
        : analysisTimeout;
  }

  public int getMaxRequestsPerMinute() {
    return maxRequestsPerMinute;
  }

  public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
    this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
  }

  public int getCircuitFailureThreshold() {
    return circuitFailureThreshold;
  }

  public void setCircuitFailureThreshold(int circuitFailureThreshold) {
    this.circuitFailureThreshold = Math.max(1, circuitFailureThreshold);
  }

  public Duration getCircuitOpenDuration() {
    return circuitOpenDuration;
  }

  public void setCircuitOpenDuration(Duration circuitOpenDuration) {
    this.circuitOpenDuration = circuitOpenDuration == null
        ? Duration.ofSeconds(30)
        : circuitOpenDuration;
  }

  public List<String> getBlockedWords() {
    return blockedWords;
  }

  public void setBlockedWords(List<String> blockedWords) {
    this.blockedWords = blockedWords == null ? new ArrayList<>() : blockedWords;
  }

  public Instant getLastSuccessAt() {
    return lastSuccessAt;
  }

  public void markSuccess() {
    this.lastSuccessAt = Instant.now();
    this.lastFailureCode = null;
  }

  public Instant getLastFailureAt() {
    return lastFailureAt;
  }

  public void markFailure(String code) {
    this.lastFailureAt = Instant.now();
    this.lastFailureCode = code;
  }

  public String getLastFailureCode() {
    return lastFailureCode;
  }

  public String getLastErrorCode() {
    return lastFailureCode;
  }

  /** Records an analysis that passed the full schema and business-quality gates. */
  public void markAnalysisReady() {
    Instant now = Instant.now();
    this.lastSuccessAt = now;
    this.lastFailureCode = null;
    this.lastValidatedAnalysisAt = now;
    this.lastAnalysisErrorCode = null;
    this.lastAnalysisState = "READY";
  }

  /** Records a provider response rejected by the local JSON or business-quality contract. */
  public void markAnalysisResponseRejected(String errorCode) {
    markFailure(errorCode);
    this.lastAnalysisErrorCode = safeErrorCode(errorCode);
    this.lastAnalysisState = "RESPONSE_REJECTED";
  }

  /** Records a failed provider call after configuration has already been confirmed. */
  public void markAnalysisUpstreamError(String errorCode) {
    markFailure(errorCode);
    this.lastAnalysisErrorCode = safeErrorCode(errorCode);
    this.lastAnalysisState = "UPSTREAM_ERROR";
  }

  /**
   * Runtime status intentionally reflects the last complete analysis outcome, not just whether a
   * key exists or the upstream returned HTTP 200.
   */
  public String getAnalysisState() {
    if (!isConfigured()) return "NOT_CONFIGURED";
    if (lastAnalysisState == null || lastAnalysisState.isBlank()) return "CONFIGURED";
    return lastAnalysisState;
  }

  public Instant getLastValidatedAnalysisAt() {
    return lastValidatedAnalysisAt;
  }

  public String getLastAnalysisErrorCode() {
    return lastAnalysisErrorCode;
  }

  public String getBaseUrlHost() {
    try {
      java.net.URI uri = java.net.URI.create(baseUrl);
      return uri.getHost();
    } catch (Exception e) {
      return "unknown";
    }
  }

  private String trimTrailingSlash(String value) {
    if (value == null || value.isBlank()) {
      return "https://api.deepseek.com";
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private String safeErrorCode(String value) {
    return value == null ? "" : value.replaceAll("[^A-Z0-9_]", "").trim();
  }
}
