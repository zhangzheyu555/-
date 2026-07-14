package com.storeprofit.system.eleme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ElemeWebhookServiceTest {
  private EmbeddedDatabase database;
  private JdbcTemplate jdbcTemplate;
  private ElemeProperties properties;
  private ElemeWebhookSignatureVerifier verifier;
  private ElemeWebhookService service;

  @BeforeEach
  void setUp() {
    database = new EmbeddedDatabaseBuilder()
        .generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2)
        .setName("webhook;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
        .build();
    jdbcTemplate = new JdbcTemplate((DataSource) database);
    jdbcTemplate.execute("""
        create table platform_webhook_event (
          id bigint auto_increment primary key,
          provider varchar(32) not null,
          event_id varchar(160) not null,
          event_type varchar(80),
          payload_sha256 char(64) not null,
          processing_status varchar(32) not null,
          duplicate_count int not null default 0,
          received_at timestamp not null default current_timestamp,
          last_received_at timestamp not null default current_timestamp,
          constraint uk_platform_webhook_event unique (provider, event_id)
        )
        """);
    properties = new ElemeProperties();
    properties.setWebhookSecret("test-only-secret");
    properties.setWebhookSignatureMode("HMAC_SHA256_BODY");
    verifier = new ElemeWebhookSignatureVerifier(properties);
    service = new ElemeWebhookService(
        properties,
        verifier,
        new ElemeWebhookEventRepository(jdbcTemplate),
        new ObjectMapper()
    );
  }

  @AfterEach
  void tearDown() {
    database.shutdown();
  }

  @Test
  void persistsOnlyMetadataAndTreatsAnExactRepeatAsIdempotent() {
    byte[] body = "{\"type\":217,\"orderId\":\"order-1\"}".getBytes(StandardCharsets.UTF_8);
    String signature = verifier.signForTest(body);

    ElemeWebhookReceipt first = service.receive(body, signature, "evt-1");
    ElemeWebhookReceipt duplicate = service.receive(body, signature, "evt-1");

    assertThat(first.duplicate()).isFalse();
    assertThat(duplicate.duplicate()).isTrue();
    assertThat(first.message()).isEqualTo("ok");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from platform_webhook_event", Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select duplicate_count from platform_webhook_event where event_id = 'evt-1'", Integer.class))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select event_type from platform_webhook_event where event_id = 'evt-1'", String.class))
        .isEqualTo("217");
    assertThat(jdbcTemplate.queryForObject(
        "select payload_sha256 from platform_webhook_event where event_id = 'evt-1'", String.class))
        .hasSize(64)
        .doesNotContain("order-1", "test-only-secret");
  }

  @Test
  void rejectsSameEventIdWithDifferentPayloadWithoutChangingStoredEvent() {
    byte[] first = "{\"type\":217}".getBytes(StandardCharsets.UTF_8);
    byte[] changed = "{\"type\":18}".getBytes(StandardCharsets.UTF_8);
    service.receive(first, verifier.signForTest(first), "evt-collision");

    BusinessException conflict = catchThrowableOfType(
        () -> service.receive(changed, verifier.signForTest(changed), "evt-collision"),
        BusinessException.class
    );

    assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getCode()).isEqualTo("ELEME_WEBHOOK_EVENT_CONFLICT");
    assertThat(jdbcTemplate.queryForObject(
        "select duplicate_count from platform_webhook_event where event_id = 'evt-collision'", Integer.class))
        .isZero();
  }

  @Test
  void invalidSignatureNeverPersistsAnEvent() {
    byte[] body = "{\"type\":217}".getBytes(StandardCharsets.UTF_8);

    BusinessException denied = catchThrowableOfType(
        () -> service.receive(body, "00", "evt-denied"),
        BusinessException.class
    );

    assertThat(denied.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from platform_webhook_event", Integer.class)).isZero();
  }

  @Test
  void validSignatureStillRequiresEventIdAndJsonObject() {
    byte[] arrayBody = "[]".getBytes(StandardCharsets.UTF_8);
    BusinessException missingId = catchThrowableOfType(
        () -> service.receive(arrayBody, verifier.signForTest(arrayBody), null),
        BusinessException.class
    );
    assertThat(missingId.getCode()).isEqualTo("ELEME_WEBHOOK_EVENT_ID_INVALID");

    BusinessException invalidJson = catchThrowableOfType(
        () -> service.receive(arrayBody, verifier.signForTest(arrayBody), "evt-array"),
        BusinessException.class
    );
    assertThat(invalidJson.getCode()).isEqualTo("ELEME_WEBHOOK_PAYLOAD_INVALID");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from platform_webhook_event", Integer.class)).isZero();
  }
}
