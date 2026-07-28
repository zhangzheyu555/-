package com.storeprofit.system.platform.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth.login-protection")
public class LoginProtectionProperties {
  private int initialAttempts = 5;
  private Duration baseCooldown = Duration.ofSeconds(30);
  private Duration maxCooldown = Duration.ofMinutes(5);
  private Duration resetAfter = Duration.ofMinutes(5);
  private int maxTrackedAccounts = 10_000;
  private int maxConcurrentPasswordVerifications = 4;

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

  private Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }
}
