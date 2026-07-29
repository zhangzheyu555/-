package com.storeprofit.system.platform.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth.login-protection")
public class LoginProtectionProperties {
  public enum StoreMode { LOCAL_ONLY, REDIS_REQUIRED }

  private StoreMode storeMode = StoreMode.LOCAL_ONLY;
  private int initialAttempts = 5;
  private Duration baseCooldown = Duration.ofSeconds(30);
  private Duration maxCooldown = Duration.ofMinutes(5);
  private Duration resetAfter = Duration.ofMinutes(5);
  private int maxTrackedAccounts = 10_000;
  private int maxConcurrentPasswordVerifications = 4;
  private int ipAttemptsPerMinute = 30;
  private int wechatAttemptsPerMinute = 60;
  private Duration unavailableRetryAfter = Duration.ofSeconds(5);

  public StoreMode getStoreMode() {
    return storeMode;
  }

  public void setStoreMode(StoreMode storeMode) {
    this.storeMode = storeMode == null ? StoreMode.LOCAL_ONLY : storeMode;
  }

  public int getInitialAttempts() {
    return initialAttempts;
  }

  public void setInitialAttempts(int initialAttempts) {
    this.initialAttempts = Math.max(1, initialAttempts);
  }

  public Duration getBaseCooldown() {
    return baseCooldown;
  }

  public void setBaseCooldown(Duration baseCooldown) {
    this.baseCooldown = positive(baseCooldown, Duration.ofSeconds(30));
  }

  public Duration getMaxCooldown() {
    return maxCooldown;
  }

  public void setMaxCooldown(Duration maxCooldown) {
    this.maxCooldown = positive(maxCooldown, Duration.ofMinutes(5));
  }

  public Duration getResetAfter() {
    return resetAfter;
  }

  public void setResetAfter(Duration resetAfter) {
    this.resetAfter = positive(resetAfter, Duration.ofMinutes(5));
  }

  public int getMaxTrackedAccounts() {
    return maxTrackedAccounts;
  }

  public void setMaxTrackedAccounts(int maxTrackedAccounts) {
    this.maxTrackedAccounts = Math.max(100, maxTrackedAccounts);
  }

  public int getMaxConcurrentPasswordVerifications() {
    return maxConcurrentPasswordVerifications;
  }

  public void setMaxConcurrentPasswordVerifications(int maxConcurrentPasswordVerifications) {
    this.maxConcurrentPasswordVerifications = Math.max(1, maxConcurrentPasswordVerifications);
  }

  public int getIpAttemptsPerMinute() {
    return ipAttemptsPerMinute;
  }

  public void setIpAttemptsPerMinute(int ipAttemptsPerMinute) {
    this.ipAttemptsPerMinute = Math.max(1, ipAttemptsPerMinute);
  }

  public int getWechatAttemptsPerMinute() {
    return wechatAttemptsPerMinute;
  }

  public void setWechatAttemptsPerMinute(int wechatAttemptsPerMinute) {
    this.wechatAttemptsPerMinute = Math.max(1, wechatAttemptsPerMinute);
  }

  public Duration getUnavailableRetryAfter() {
    return unavailableRetryAfter;
  }

  public void setUnavailableRetryAfter(Duration unavailableRetryAfter) {
    this.unavailableRetryAfter = positive(unavailableRetryAfter, Duration.ofSeconds(5));
  }

  private Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }
}
