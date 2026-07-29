package com.storeprofit.system.platform.auth;

import com.storeprofit.system.common.LoginProtectionUnavailableException;
import com.storeprofit.system.common.RateLimitException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
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
  private static final DefaultRedisScript<String> ACQUIRE_RATE_SCRIPT = new DefaultRedisScript<>("""
      local attempts = redis.call('INCR', KEYS[1])
      if attempts == 1 then
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
      end
      if attempts > tonumber(ARGV[1]) then
        local ttl = redis.call('PTTL', KEYS[1])
        return '0:' .. tostring(math.max(1, math.ceil(ttl / 1000)))
      end
      return '1:0'
      """, String.class);

  private final LoginProtectionProperties properties;
  private final StringRedisTemplate redisTemplate;
  private final Clock clock;
  private final ConcurrentMap<String, AttemptState> localAttempts = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AttemptState> localRates = new ConcurrentHashMap<>();
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
    acquirePassword(tenantId, username, "");
  }

  /** Checks the source IP before the account key so fabricated usernames cannot fill the store. */
  public void acquirePassword(long tenantId, String username, String sourceIp) {
    enforce(acquireRate(passwordIpKey(sourceIp), properties.getIpAttemptsPerMinute()));
    enforce(acquireAccount(attemptKey(tenantId, username)));
  }

  /** Limits code-to-session calls by source IP; temporary WeChat codes must not be stored as keys. */
  public void acquireWeChat(long tenantId, String sourceIp) {
    enforce(acquireRate(wechatIpKey(sourceIp), properties.getWechatAttemptsPerMinute()));
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
      warnRedisUnavailable(exception);
    }
  }

  public void clearAccount(long tenantId, String username) {
    clear(tenantId, username);
  }

  private Decision acquireAccount(String key) {
    if (redisTemplate == null) {
      return acquireLocally(key);
    }
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
      warnRedisUnavailable(exception);
      throw unavailable();
    }
  }

  private Decision acquireRate(String key, int limit) {
    if (redisTemplate == null) {
      return acquireLocalRate(key, limit);
    }
    try {
      return parseDecision(redisTemplate.execute(
          ACQUIRE_RATE_SCRIPT,
          List.of(key),
          String.valueOf(limit),
          String.valueOf(Duration.ofMinutes(1).toMillis())
      ));
    } catch (RuntimeException exception) {
      warnRedisUnavailable(exception);
      throw unavailable();
    }
  }

  private void enforce(Decision decision) {
    if (!decision.allowed()) {
      throw new RateLimitException(RATE_LIMIT_CODE, RATE_LIMIT_MESSAGE, decision.retryAfterSeconds());
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
    if (properties.getStoreMode() != LoginProtectionProperties.StoreMode.LOCAL_ONLY) {
      throw unavailable();
    }
    long now = clock.millis();
    if (!localAttempts.containsKey(key)
        && localAttempts.size() >= properties.getMaxTrackedAccounts()) {
      deleteExpiredLocalAttempts();
      if (localAttempts.size() >= properties.getMaxTrackedAccounts()) {
        throw unavailable();
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

  private Decision acquireLocalRate(String key, int limit) {
    if (properties.getStoreMode() != LoginProtectionProperties.StoreMode.LOCAL_ONLY) {
      throw unavailable();
    }
    long now = clock.millis();
    AtomicReference<Decision> decision = new AtomicReference<>();
    localRates.compute(key, (ignored, current) -> {
      AttemptState state = current == null || current.expiresAtMillis() <= now
          ? AttemptState.empty()
          : current;
      int attempts = state.attempts() + 1;
      if (attempts > limit) {
        decision.set(Decision.deny(retryAfterSeconds(state.expiresAtMillis() - now)));
        return state;
      }
      decision.set(Decision.permit());
      return new AttemptState(attempts, 0L, now + Duration.ofMinutes(1).toMillis());
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
    localRates.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
  }

  private String attemptKey(long tenantId, String username) {
    String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    return tenantId + ":" + sha256(normalized);
  }

  private String redisKey(String key) {
    return "auth:login:account:" + key;
  }

  private String passwordIpKey(String sourceIp) {
    return "auth:login:ip:" + sha256(normalizeSourceIp(sourceIp));
  }

  private String wechatIpKey(String sourceIp) {
    return "auth:wechat:ip:" + sha256(normalizeSourceIp(sourceIp));
  }

  private String normalizeSourceIp(String sourceIp) {
    return sourceIp == null ? "" : sourceIp.trim();
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private LoginProtectionUnavailableException unavailable() {
    return new LoginProtectionUnavailableException(properties.getUnavailableRetryAfter().toSeconds());
  }

  private void warnRedisUnavailable(RuntimeException exception) {
    long now = clock.millis();
    long previous = lastRedisWarningAt.get();
    if (now - previous >= REDIS_WARNING_INTERVAL_MILLIS
        && lastRedisWarningAt.compareAndSet(previous, now)) {
      log.warn("Redis login limiter unavailable; rejecting login safely. cause={}",
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
