package com.storeprofit.system.assistant;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.assistant.deepseek")
public class DeepSeekProperties {
  private String apiKey = "";
  private String baseUrl = "https://api.deepseek.com";
  private String model = "deepseek-v4-flash";
  private int maxTokens = 800;
  private double temperature = 0.2;
  private Duration timeout = Duration.ofSeconds(30);
  private List<String> blockedWords = new ArrayList<>();

  public boolean hasApiKey() {
    return apiKey != null && !apiKey.isBlank();
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
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
    this.model = model;
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

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  public List<String> getBlockedWords() {
    return blockedWords;
  }

  public void setBlockedWords(List<String> blockedWords) {
    this.blockedWords = blockedWords == null ? new ArrayList<>() : blockedWords;
  }

  private String trimTrailingSlash(String value) {
    if (value == null || value.isBlank()) {
      return "https://api.deepseek.com";
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
