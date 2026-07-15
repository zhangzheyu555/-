package com.storeprofit.system.operations;

import com.storeprofit.system.common.BusinessException;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Issues short-lived, video-scoped credentials for players that cannot attach an Authorization
 * header to media range requests. The original session remains server-side and is revalidated by
 * {@link com.storeprofit.system.platform.auth.AuthService} for every range request.
 */
@Service
public class TrainingVideoPlaybackTicketService {
  private static final int TICKET_BYTES = 32;
  private static final long MIN_TTL_SECONDS = 60;
  private static final long MAX_TTL_SECONDS = 3600;
  private static final int MAX_LIVE_TICKETS = 10_000;

  private final SecureRandom secureRandom;
  private final Clock clock;
  private final long ttlSeconds;
  private final TicketStore ticketStore;
  private final boolean sharedStoreRequired;

  @Autowired
  public TrainingVideoPlaybackTicketService(
      @Value("${app.training.video-playback-ticket-ttl-seconds:1800}") long ttlSeconds,
      @Value("${app.training.require-shared-video-tickets:false}") boolean sharedStoreRequired,
      // This is intentionally the standard Spring environment variable rather than a parallel
      // application credential. It also prevents Spring Data Redis' implicit localhost default
      // from satisfying the production shared-store gate.
      @Value("${SPRING_DATA_REDIS_HOST:}") String redisHost,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider
  ) {
    this(
        ttlSeconds,
        Clock.systemUTC(),
        new SecureRandom(),
        createTicketStore(
            sharedStoreRequired,
            redisHost,
            sharedStoreRequired ? redisTemplateProvider.getIfAvailable() : null,
            normalizedTtlSeconds(ttlSeconds)),
        sharedStoreRequired);
  }

  TrainingVideoPlaybackTicketService(long ttlSeconds, Clock clock, SecureRandom secureRandom) {
    this(ttlSeconds, clock, secureRandom, new LocalTicketStore(), false);
  }

  TrainingVideoPlaybackTicketService(
      long ttlSeconds,
      Clock clock,
      SecureRandom secureRandom,
      boolean sharedStoreRequired,
      String redisHost,
      StringRedisTemplate redisTemplate
  ) {
    this(
        ttlSeconds,
        clock,
        secureRandom,
        createTicketStore(
            sharedStoreRequired, redisHost, redisTemplate, normalizedTtlSeconds(ttlSeconds)),
        sharedStoreRequired);
  }

  private TrainingVideoPlaybackTicketService(
      long ttlSeconds,
      Clock clock,
      SecureRandom secureRandom,
      TicketStore ticketStore,
      boolean sharedStoreRequired
  ) {
    this.ttlSeconds = normalizedTtlSeconds(ttlSeconds);
    this.clock = clock;
    this.secureRandom = secureRandom;
    this.ticketStore = ticketStore;
    this.sharedStoreRequired = sharedStoreRequired;
  }

  /**
   * Multi-instance video streaming must never accidentally fall back to this process' memory.
   * A failed Redis reachability probe aborts startup before the application accepts traffic.
   */
  @PostConstruct
  void verifySharedTicketStoreOnStartup() {
    if (!sharedStoreRequired) {
      return;
    }
    try {
      ticketStore.verifyAvailable();
    } catch (TicketStoreUnavailableException exception) {
      throw new IllegalStateException("共享视频播放票据存储不可用，应用拒绝启动", exception);
    }
  }

  public synchronized PlaybackTicket issue(long videoId, String authorization) {
    try {
      String normalizedAuthorization = normalizedAuthorization(authorization);
      Instant now = clock.instant();
      ticketStore.removeExpired(now);
      if (ticketStore.isAtCapacity(MAX_LIVE_TICKETS)) {
        throw new BusinessException(
            "PLAYBACK_TICKET_CAPACITY_REACHED", "视频播放凭证暂时不可用，请稍后重试",
            HttpStatus.SERVICE_UNAVAILABLE);
      }

      Instant expiresAt = now.plusSeconds(ttlSeconds);
      TicketRecord record = new TicketRecord(videoId, normalizedAuthorization, expiresAt);
      String value;
      do {
        byte[] bytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(bytes);
        value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      } while (!ticketStore.putIfAbsent(value, record, Duration.ofSeconds(ttlSeconds)));
      // Persist before changing the session index so a concurrent issuer cannot leave an
      // unindexed but usable ticket behind.
      ticketStore.replaceSessionTicket(record, value, Duration.ofSeconds(ttlSeconds));
      return new PlaybackTicket(value, expiresAt);
    } catch (TicketStoreUnavailableException exception) {
      throw unavailableTicketStore();
    }
  }

  public String authorizationFor(long videoId, String ticket) {
    try {
      if (ticket == null || ticket.isBlank() || ticket.length() > 128) {
        throw invalidTicket();
      }
      TicketRecord record = ticketStore.find(ticket);
      if (record == null) throw invalidTicket();
      Instant now = clock.instant();
      if (!record.expiresAt().isAfter(now)) {
        ticketStore.remove(ticket);
        throw invalidTicket();
      }
      if (record.videoId() != videoId) {
        throw new BusinessException(
            "PLAYBACK_TICKET_SCOPE_MISMATCH", "视频播放凭证与当前视频不匹配", HttpStatus.FORBIDDEN);
      }
      return record.authorization();
    } catch (TicketStoreUnavailableException exception) {
      throw unavailableTicketStore();
    }
  }

  int liveTicketCount() {
    return ticketStore.liveTicketCount();
  }

  private String normalizedAuthorization(String authorization) {
    if (authorization == null || authorization.isBlank()) throw invalidTicket();
    String value = authorization.trim();
    String token = value.regionMatches(true, 0, "Bearer ", 0, 7)
        ? value.substring(7).trim()
        : value;
    if (token.isBlank() || token.length() > 256) throw invalidTicket();
    return "Bearer " + token;
  }

  private BusinessException invalidTicket() {
    return new BusinessException(
        "PLAYBACK_TICKET_INVALID", "视频播放凭证无效或已过期，请重新打开视频", HttpStatus.UNAUTHORIZED);
  }

  private BusinessException unavailableTicketStore() {
    return new BusinessException(
        "PLAYBACK_TICKET_STORE_UNAVAILABLE", "视频播放凭证服务暂不可用，请稍后重试",
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  private static long normalizedTtlSeconds(long value) {
    return Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, value));
  }

  private static TicketStore createTicketStore(
      boolean sharedStoreRequired,
      String redisHost,
      StringRedisTemplate redisTemplate,
      long ttlSeconds
  ) {
    if (!sharedStoreRequired) {
      return new LocalTicketStore();
    }
    if (redisHost == null || redisHost.isBlank()) {
      throw new IllegalStateException(
          "MOBILE_REQUIRE_SHARED_VIDEO_TICKETS=true requires SPRING_DATA_REDIS_HOST");
    }
    if (redisTemplate == null) {
      throw new IllegalStateException("共享视频播放票据存储未配置");
    }
    return new RedisTicketStore(redisTemplate, Duration.ofSeconds(ttlSeconds));
  }

  private interface TicketStore {
    boolean putIfAbsent(String ticket, TicketRecord record, Duration ttl);

    void replaceSessionTicket(TicketRecord record, String ticket, Duration ttl);

    TicketRecord find(String ticket);

    void remove(String ticket);

    void removeExpired(Instant now);

    boolean isAtCapacity(int capacity);

    int liveTicketCount();

    void verifyAvailable();
  }

  private static final class LocalTicketStore implements TicketStore {
    private final ConcurrentMap<String, TicketRecord> tickets = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(String ticket, TicketRecord record, Duration ttl) {
      return tickets.putIfAbsent(ticket, record) == null;
    }

    @Override
    public void replaceSessionTicket(TicketRecord record, String ticket, Duration ttl) {
      tickets.entrySet().removeIf(entry -> !entry.getKey().equals(ticket)
          && entry.getValue().videoId() == record.videoId()
          && entry.getValue().authorization().equals(record.authorization()));
    }

    @Override
    public TicketRecord find(String ticket) {
      return tickets.get(ticket);
    }

    @Override
    public void remove(String ticket) {
      tickets.remove(ticket);
    }

    @Override
    public void removeExpired(Instant now) {
      tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    @Override
    public boolean isAtCapacity(int capacity) {
      return tickets.size() >= capacity;
    }

    @Override
    public int liveTicketCount() {
      return tickets.size();
    }

    @Override
    public void verifyAvailable() {
      // Process-local development store does not require an external probe.
    }
  }

  private static final class RedisTicketStore implements TicketStore {
    private static final String KEY_PREFIX = "store-profit:training:playback-ticket:";
    private static final String TICKET_KEY_PREFIX = KEY_PREFIX + "ticket:";
    private static final String SESSION_KEY_PREFIX = KEY_PREFIX + "session:";
    private static final DefaultRedisScript<String> REPLACE_SESSION_TICKET = new DefaultRedisScript<>(
        "local previous = redis.call('GET', KEYS[1]); "
            + "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]); "
            + "return previous;",
        String.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    private RedisTicketStore(StringRedisTemplate redisTemplate, Duration ttl) {
      this.redisTemplate = redisTemplate;
      this.ttl = ttl;
    }

    @Override
    public boolean putIfAbsent(String ticket, TicketRecord record, Duration ignored) {
      return call(() -> Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
          ticketKey(ticket), encode(record), ttl)));
    }

    @Override
    public void replaceSessionTicket(TicketRecord record, String ticket, Duration ignored) {
      String currentTicketHash = ticketHash(ticket);
      String previousTicketHash = call(() -> redisTemplate.execute(
          REPLACE_SESSION_TICKET,
          java.util.List.of(sessionKey(record)),
          currentTicketHash,
          Long.toString(ttl.toSeconds())));
      if (previousTicketHash != null && !previousTicketHash.isBlank()
          && !previousTicketHash.equals(currentTicketHash)) {
        run(() -> redisTemplate.delete(ticketKeyForHash(previousTicketHash)));
      }
    }

    @Override
    public TicketRecord find(String ticket) {
      return call(() -> decode(redisTemplate.opsForValue().get(ticketKey(ticket))));
    }

    @Override
    public void remove(String ticket) {
      run(() -> redisTemplate.delete(ticketKey(ticket)));
    }

    @Override
    public void removeExpired(Instant now) {
      // Redis key expiration is authoritative in shared mode; never scan all ticket keys.
    }

    @Override
    public boolean isAtCapacity(int capacity) {
      // Counting distributed expiring keys requires a scan and can become a denial-of-service
      // vector. Shared mode relies on Redis capacity controls and per-key TTL instead.
      return false;
    }

    @Override
    public int liveTicketCount() {
      // Not exposed to business code; -1 avoids a production Redis key scan.
      return -1;
    }

    @Override
    public void verifyAvailable() {
      try {
        RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
          throw new TicketStoreUnavailableException();
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
          String response = connection.ping();
          if (!"PONG".equalsIgnoreCase(response)) {
            throw new TicketStoreUnavailableException();
          }
        }
      } catch (TicketStoreUnavailableException exception) {
        throw exception;
      } catch (RuntimeException exception) {
        throw new TicketStoreUnavailableException(exception);
      }
    }

    private String ticketKey(String ticket) {
      return ticketKeyForHash(ticketHash(ticket));
    }

    private String ticketKeyForHash(String ticketHash) {
      return TICKET_KEY_PREFIX + ticketHash;
    }

    private String sessionKey(TicketRecord record) {
      return SESSION_KEY_PREFIX + sha256(record.videoId() + ":session:" + record.authorization());
    }

    private String ticketHash(String ticket) {
      return sha256(ticket);
    }

    private String encode(TicketRecord record) {
      return record.videoId() + ":" + record.expiresAt().toEpochMilli() + ":"
          + Base64.getUrlEncoder().withoutPadding().encodeToString(
              record.authorization().getBytes(StandardCharsets.UTF_8));
    }

    private TicketRecord decode(String value) {
      if (value == null || value.isBlank()) {
        return null;
      }
      String[] parts = value.split(":", 3);
      if (parts.length != 3) {
        return null;
      }
      try {
        long videoId = Long.parseLong(parts[0]);
        long expiresAtMillis = Long.parseLong(parts[1]);
        String authorization = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
        if (videoId <= 0 || authorization.isBlank()) {
          return null;
        }
        return new TicketRecord(videoId, authorization, Instant.ofEpochMilli(expiresAtMillis));
      } catch (IllegalArgumentException exception) {
        return null;
      }
    }

    private <T> T call(java.util.function.Supplier<T> action) {
      try {
        return action.get();
      } catch (RuntimeException exception) {
        throw new TicketStoreUnavailableException(exception);
      }
    }

    private void run(Runnable action) {
      try {
        action.run();
      } catch (RuntimeException exception) {
        throw new TicketStoreUnavailableException(exception);
      }
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static final class TicketStoreUnavailableException extends RuntimeException {
    private TicketStoreUnavailableException() {
      super();
    }

    private TicketStoreUnavailableException(Throwable cause) {
      super(cause);
    }
  }

  private record TicketRecord(long videoId, String authorization, Instant expiresAt) {
    @Override
    public String toString() {
      return "TicketRecord[redacted]";
    }
  }

  public record PlaybackTicket(String value, Instant expiresAt) {
    @Override
    public String toString() {
      return "PlaybackTicket[redacted, expiresAt=" + expiresAt + "]";
    }
  }
}
