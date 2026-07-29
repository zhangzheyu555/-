package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.LoginProtectionUnavailableException;
import com.storeprofit.system.common.RateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class LoginAttemptGuardTest {
  @Test
  void productionConstructorIsExplicitlySelectedForSpringInjection() {
    assertThatCode(() -> Arrays.stream(LoginAttemptGuard.class.getDeclaredConstructors())
        .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
        .findFirst()
        .orElseThrow())
        .doesNotThrowAnyException();
  }

  @Test
  void differentAccountsNeverShareTheSameCooldown() {
    MutableClock clock = new MutableClock();
    LoginAttemptGuard guard = guard(clock);

    for (int attempt = 0; attempt < 5; attempt++) {
      guard.acquire(1L, "boss");
    }

    assertThatThrownBy(() -> guard.acquire(1L, "boss"))
        .isInstanceOf(RateLimitException.class);
    assertThatCode(() -> guard.acquire(1L, "finance"))
        .doesNotThrowAnyException();
  }

  @Test
  void accountCooldownAllowsOnlyOneProbeAndThenIncreases() {
    MutableClock clock = new MutableClock();
    LoginAttemptGuard guard = guard(clock);
    for (int attempt = 0; attempt < 5; attempt++) {
      guard.acquire(1L, "boss");
    }

    assertThatThrownBy(() -> guard.acquire(1L, "boss"))
        .isInstanceOf(RateLimitException.class);

    clock.advance(Duration.ofSeconds(30));
    assertThatCode(() -> guard.acquire(1L, "boss"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.acquire(1L, "boss"))
        .isInstanceOf(RateLimitException.class);

    clock.advance(Duration.ofSeconds(60));
    assertThatCode(() -> guard.acquire(1L, "boss"))
        .doesNotThrowAnyException();
  }

  @Test
  void successfulLoginClearsTheAccountCooldown() {
    MutableClock clock = new MutableClock();
    LoginAttemptGuard guard = guard(clock);
    for (int attempt = 0; attempt < 5; attempt++) {
      guard.acquire(1L, "boss");
    }

    guard.clear(1L, "boss");

    assertThatCode(() -> guard.acquire(1L, "boss"))
        .doesNotThrowAnyException();
  }

  @Test
  void redisRequiredRejectsLoginWhenRedisIsUnavailable() {
    StringRedisTemplate redis = new StringRedisTemplate();
    LoginProtectionProperties properties = new LoginProtectionProperties();
    properties.setStoreMode(LoginProtectionProperties.StoreMode.REDIS_REQUIRED);
    LoginAttemptGuard guard = new LoginAttemptGuard(properties, redis, Clock.systemUTC());

    assertThatThrownBy(() -> guard.acquirePassword(1L, "boss", "127.0.0.1"))
        .isInstanceOf(LoginProtectionUnavailableException.class);
  }

  @Test
  void localOnlyRejectsNewAccountWhenTrackingCapacityIsFull() {
    LoginProtectionProperties properties = new LoginProtectionProperties();
    properties.setStoreMode(LoginProtectionProperties.StoreMode.LOCAL_ONLY);
    properties.setMaxTrackedAccounts(100);
    LoginAttemptGuard guard = new LoginAttemptGuard(properties, null, Clock.systemUTC());

    for (int index = 0; index < 100; index++) {
      guard.acquirePassword(1L, "user-" + index, "127.0.0." + index);
    }

    assertThatThrownBy(() -> guard.acquirePassword(1L, "one-more-user", "127.0.0.1"))
        .isInstanceOf(LoginProtectionUnavailableException.class);
  }

  private LoginAttemptGuard guard(Clock clock) {
    LoginProtectionProperties properties = new LoginProtectionProperties();
    properties.setInitialAttempts(5);
    properties.setBaseCooldown(Duration.ofSeconds(30));
    properties.setMaxCooldown(Duration.ofMinutes(5));
    properties.setResetAfter(Duration.ofMinutes(5));
    return new LoginAttemptGuard(properties, null, clock);
  }

  private static final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-07-28T00:00:00Z");

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
