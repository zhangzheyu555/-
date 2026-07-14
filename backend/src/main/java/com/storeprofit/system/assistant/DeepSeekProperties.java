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
  private int maxRequestsPerMinute = 30;
  private int circuitFailureThreshold = 3;
  private Duration circuitOpenDuration = Duration.ofSeconds(30);
  private List<String> blockedWords = new ArrayList<>();

  private volatile Instant lastSuccessAt;
  private volatile Instant lastFailureAt;
  private volatile String lastFailureCode;

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
}
