package com.storeprofit.system.common;

/** Raised when the configured shared login protection store cannot make a safe decision. */
public class LoginProtectionUnavailableException extends RuntimeException {
  private static final String CODE = "LOGIN_PROTECTION_UNAVAILABLE";
  private static final String MESSAGE = "登录保护服务暂不可用，请稍后重试";
  private final long retryAfterSeconds;

  public LoginProtectionUnavailableException(long retryAfterSeconds) {
    super(MESSAGE);
    this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
  }

  public String getCode() {
    return CODE;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
