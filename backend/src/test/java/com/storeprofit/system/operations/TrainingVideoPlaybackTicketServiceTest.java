package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.BusinessException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class TrainingVideoPlaybackTicketServiceTest {
  private final MutableClock clock = new MutableClock(Instant.parse("2026-07-15T08:00:00Z"));
  private final TrainingVideoPlaybackTicketService service =
      new TrainingVideoPlaybackTicketService(60, clock, new SecureRandom());

  @Test
  void ticketIsHighEntropyVideoScopedAndContainsNoPrimaryToken() {
    var ticket = service.issue(7L, "Bearer primary-session-token");

    assertThat(ticket.value()).hasSize(43).doesNotContain("primary-session-token");
    assertThat(service.authorizationFor(7L, ticket.value()))
        .isEqualTo("Bearer primary-session-token");
    assertThatThrownBy(() -> service.authorizationFor(8L, ticket.value()))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("PLAYBACK_TICKET_SCOPE_MISMATCH");
          assertThat(error.getStatus().value()).isEqualTo(403);
        });
  }

  @Test
  void expiredTicketIsRemovedAndCannotBeReused() {
    var ticket = service.issue(7L, "primary-session-token");

    clock.advance(Duration.ofSeconds(61));

    assertThatThrownBy(() -> service.authorizationFor(7L, ticket.value()))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("PLAYBACK_TICKET_INVALID");
          assertThat(error.getStatus().value()).isEqualTo(401);
        });
    assertThat(service.liveTicketCount()).isZero();
  }

  @Test
  void reissuingForSameSessionAndVideoRevokesPreviousTicket() {
    var first = service.issue(7L, "Bearer primary-session-token");
    var second = service.issue(7L, "Bearer primary-session-token");

    assertThat(first.value()).isNotEqualTo(second.value());
    assertThat(service.liveTicketCount()).isOne();
    assertThatThrownBy(() -> service.authorizationFor(7L, first.value()))
        .isInstanceOf(BusinessException.class);
    assertThat(service.authorizationFor(7L, second.value()))
        .isEqualTo("Bearer primary-session-token");
  }

  @Test
  void sharedModeRequiresAnExplicitStandardRedisHostAndNeverFallsBackToLocalMemory() {
    assertThatThrownBy(() -> new TrainingVideoPlaybackTicketService(
        60, clock, new SecureRandom(), true, "", org.mockito.Mockito.mock(StringRedisTemplate.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MOBILE_REQUIRE_SHARED_VIDEO_TICKETS=true")
        .hasMessageContaining("SPRING_DATA_REDIS_HOST");
  }

  @Test
  void sharedModeReturnsServiceUnavailableWhenRedisCannotStoreTicket() {
    StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    org.mockito.Mockito.when(redisTemplate.opsForValue())
        .thenThrow(new IllegalStateException("redis unavailable"));
    TrainingVideoPlaybackTicketService sharedService = new TrainingVideoPlaybackTicketService(
        60, clock, new SecureRandom(), true, "redis-staging", redisTemplate);

    assertThatThrownBy(() -> sharedService.issue(7L, "Bearer primary-session-token"))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("PLAYBACK_TICKET_STORE_UNAVAILABLE");
          assertThat(error.getStatus().value()).isEqualTo(503);
          assertThat(error.getMessage()).doesNotContain("primary-session-token");
        });
  }

  @Test
  void sharedModePersistsOnlyHashedTicketKeysWithTheTicketTtlAndMaintainsVideoScope() {
    StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
    Map<String, String> records = new ConcurrentHashMap<>();
    org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(values);
    org.mockito.Mockito.when(values.setIfAbsent(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
        .thenAnswer(invocation -> records.putIfAbsent(
            invocation.getArgument(0), invocation.getArgument(1)) == null);
    org.mockito.Mockito.when(values.get(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> records.get(invocation.getArgument(0)));
    TrainingVideoPlaybackTicketService sharedService = new TrainingVideoPlaybackTicketService(
        60, clock, new SecureRandom(), true, "redis-staging", redisTemplate);

    var ticket = sharedService.issue(7L, "Bearer primary-session-token");

    assertThat(sharedService.authorizationFor(7L, ticket.value()))
        .isEqualTo("Bearer primary-session-token");
    assertThatThrownBy(() -> sharedService.authorizationFor(8L, ticket.value()))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("PLAYBACK_TICKET_SCOPE_MISMATCH"));
    org.mockito.ArgumentCaptor<String> keyCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(values).setIfAbsent(
        keyCaptor.capture(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.eq(java.time.Duration.ofSeconds(60)));
    assertThat(keyCaptor.getValue()).startsWith("store-profit:training:playback-ticket:ticket:")
        .doesNotContain(ticket.value())
        .doesNotContain("primary-session-token");
  }

  @Test
  void sharedModeStartupProbeFailsClosedWithoutAReachableRedisConnection() {
    StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory =
        org.mockito.Mockito.mock(org.springframework.data.redis.connection.RedisConnectionFactory.class);
    org.mockito.Mockito.when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
    org.mockito.Mockito.when(connectionFactory.getConnection())
        .thenThrow(new IllegalStateException("redis unavailable"));
    TrainingVideoPlaybackTicketService sharedService = new TrainingVideoPlaybackTicketService(
        60, clock, new SecureRandom(), true, "redis-staging", redisTemplate);

    assertThatThrownBy(sharedService::verifySharedTicketStoreOnStartup)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("共享视频播放票据存储不可用")
        .hasMessageNotContaining("redis-staging");
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
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
