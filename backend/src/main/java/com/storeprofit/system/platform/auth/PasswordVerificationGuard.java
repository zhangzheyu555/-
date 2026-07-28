package com.storeprofit.system.platform.auth;

import com.storeprofit.system.common.RateLimitException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PasswordVerificationGuard {
  private final Semaphore permits;

  @Autowired
  public PasswordVerificationGuard(LoginProtectionProperties properties) {
    this(properties.getMaxConcurrentPasswordVerifications());
  }

  PasswordVerificationGuard(int maxConcurrentVerifications) {
    this.permits = new Semaphore(Math.max(1, maxConcurrentVerifications), true);
  }

  public boolean verify(Supplier<Boolean> verification) {
    Objects.requireNonNull(verification, "verification");
    if (!permits.tryAcquire()) {
      throw new RateLimitException(
          "LOGIN_RATE_LIMITED", "登录请求较多，请稍后重试", 1);
    }
    try {
      return Boolean.TRUE.equals(verification.get());
    } finally {
      permits.release();
    }
  }
}
