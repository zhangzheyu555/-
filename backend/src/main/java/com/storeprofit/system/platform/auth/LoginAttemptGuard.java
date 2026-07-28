package com.storeprofit.system.platform.auth;

import com.storeprofit.system.common.RateLimitException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptGuard {
  private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);
  private static final String RATE_LIMIT_CODE = "LOGIN_RATE_LIMITED";
  private static final String RATE_LIMIT_MESSAGE = "登录尝试过多，请稍后再试";
  private static final long REDIS_WARNING_INTERVAL_MILLIS = 60_000L;
  private static final DefaultRedisScript<String> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
      local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0')
      local nextAllowedAt = tonumber(redis.call('HGET', KEYS[1], 'nextAllowedAt') or '0')
      local now = tonumber(ARGV[1])
      local initialAttempts = tonumber(ARGV[2])
      local baseCooldown = tonumber(ARGV[3])
      local maxCooldown = tonumber(ARGV[4])
      local resetAfter = tonumber(ARGV[5])

      if attempts >= initialAttempts and now < nextAllowedAt then
        local retryAfter = math.max(1, math.ceil((nextAllowedAt - now) / 1000))
        return '0:' .. tostring(retryAfter)
      end

      attempts = attempts + 1
      if attempts >= initialAttempts then
        local exponent = attempts - initialAttempts
        local cooldown = math.min(maxCooldown, baseCooldown * math.pow(2, exponent))
        nextAllowedAt = now + cooldown
      else
        nextAllowedAt = 0
      end

      redis.call('HSET', KEYS[1], 'attempts', attempts, 'nextAllowedAt', nextAllowedAt)
      redis.call('PEXPIRE', KEYS[1], resetAfter)
      return '1:0'
      """, String.class);

  private final LoginProtectionProperties properties;
  private final StringRedisTemplate redisTemplate;
  private final Clock clock;
  private final ConcurrentMap<String, AttemptState> localAttempts = new ConcurrentHashMap<>();
  private final AtomicLong lastRedisWarningAt = new AtomicLong();

  @Autowired
  public LoginAttemptGuard(
      LoginProtectionProperties properties,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider
  ) {
    this(properties, redisTemplateProvider.getIfAvailable(), Clock.systemUTC());
  }

  LoginAttemptGuard(
      LoginProtectionProperties properties,
      StringRedisTemplate redisTemplate,
      Clock clock
  ) {
    this.properties = properties;
    this.redisTemplate = redisTemplate;
    this.clock = clock;
  }

  public void acquire(long tenantId, String username) {
    String key = attemptKey(tenantId, username);
    Decision decision = redisTemplate == null ? acquireLocally(key) : acquireWithFallback(key);
    if (!decision.allowed()) {
      throw new RateLimitException(RATE_LIMIT_CODE, RATE_LIMIT_MESSAGE, decision.retryAfterSeconds());
    }
  }

  public void clear(long tenantId, String username) {
    String key = attemptKey(tenantId, username);
    localAttempts.remove(key);
    if (redisTemplate == null) {
      return;
    }
    try {
      redisTemplate.delete(redisKey(key));
    } catch (RuntimeException exception) {
      warnRedisFallback(exception);
    }
  }

  private Decision acquireWithFallback(String key) {
    try {
      String result = redisTemplate.execute(
          ACQUIRE_SCRIPT,
          List.of(redisKey(key)),
          String.valueOf(clock.millis()),
          String.valueOf(properties.getInitialAttempts()),
          String.valueOf(properties.getBaseCooldown().toMillis()),
          String.valueOf(properties.getMaxCooldown().toMillis()),
          String.valueOf(properties.getResetAfter().toMillis())
      );
      Decision decision = parseDecision(result);
      localAttempts.remove(key);
      return decision;
    } catch (RuntimeException exception) {
      warnRedisFallback(exception);
      return acquireLocally(key);
    }
  }

  private Decision parseDecision(String result) {
    if (result == null) {
      throw new IllegalStateException("Login attempt limiter returned no decision");
    }
    String[] parts = result.split(":", 2);
    if (parts.length != 2) {
      throw new IllegalStateException("Login attempt limiter returned an invalid decision");
    }
    return new Decision("1".equals(parts[0]), Long.parseLong(parts[1]));
  }

  private Decision acquireLocally(String key) {
    long now = clock.millis();
    if (!localAttempts.containsKey(key)
        && localAttempts.size() >= properties.getMaxTrackedAccounts()) {
      deleteExpiredLocalAttempts();
      if (localAttempts.size() >= properties.getMaxTrackedAccounts()) {
        return Decision.permit();
      }
    }
    AtomicReference<Decision> decision = new AtomicReference<>();
    localAttempts.compute(key, (ignored, current) -> {
      AttemptState state = current == null || current.expiresAtMillis() <= now
          ? AttemptState.empty()
          : current;
      if (state.attempts() >= properties.getInitialAttempts()
          && now < state.nextAllowedAtMillis()) {
        decision.set(Decision.deny(retryAfterSeconds(state.nextAllowedAtMillis() - now)));
        return state;
      }
      int attempts = state.attempts() + 1;
      long nextAllowedAt = attempts >= properties.getInitialAttempts()
          ? now + cooldownMillis(attempts)
          : 0L;
      decision.set(Decision.permit());
      return new AttemptState(
          attempts,
          nextAllowedAt,
          now + properties.getResetAfter().toMillis()
      );
    });
    return decision.get();
  }

  private long cooldownMillis(int attempts) {
    int exponent = Math.max(0, attempts - properties.getInitialAttempts());
    long base = properties.getBaseCooldown().toMillis();
    long maximum = properties.getMaxCooldown().toMillis();
    long multiplier = 1L << Math.min(exponent, 20);
    if (base > maximum / multiplier) {
      return maximum;
    }
    return Math.min(maximum, base * multiplier);
  }

  private long retryAfterSeconds(long remainingMillis) {
    return Math.max(1, (remainingMillis + 999L) / 1000L);
  }

  @Scheduled(fixedDelayString = "${app.auth.login-protection.cleanup-interval:PT5M}")
  public void deleteExpiredLocalAttempts() {
    long now = clock.millis();
    localAttempts.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
  }

  private String attemptKey(long tenantId, String username) {
    String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    return tenantId + ":" + sha256(normalized);
  }

  private String redisKey(String key) {
    return "auth:login:account:" + key;
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private void warnRedisFallback(RuntimeException exception) {
    long now = clock.millis();
    long previous = lastRedisWarningAt.get();
    if (now - previous >= REDIS_WARNING_INTERVAL_MILLIS
        && lastRedisWarningAt.compareAndSet(previous, now)) {
      log.warn("Redis login limiter unavailable; using local protection. cause={}",
          exception.getClass().getSimpleName());
    }
  }

  private record AttemptState(int attempts, long nextAllowedAtMillis, long expiresAtMillis) {
    private static AttemptState empty() {
      return new AttemptState(0, 0L, 0L);
    }
  }

  private record Decision(boolean allowed, long retryAfterSeconds) {
    private static Decision permit() {
      return new Decision(true, 0L);
    }

    private static Decision deny(long retryAfterSeconds) {
      return new Decision(false, retryAfterSeconds);
    }
  }
}
