package com.storeprofit.system.common;

import org.springframework.http.HttpStatus;

public class RateLimitException extends BusinessException {
  private final long retryAfterSeconds;

  public RateLimitException(String code, String message, long retryAfterSeconds) {
    super(code, message, HttpStatus.TOO_MANY_REQUESTS);
    this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
