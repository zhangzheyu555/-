package com.storeprofit.system.eleme;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ElemeWebhookEventRepository {
  static final String PROVIDER = "ELEME";

  private final JdbcTemplate jdbcTemplate;

  public ElemeWebhookEventRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void insert(String eventId, String eventType, String payloadSha256) {
    jdbcTemplate.update("""
        insert into platform_webhook_event(
          provider, event_id, event_type, payload_sha256, processing_status,
          duplicate_count, received_at, last_received_at
        )
        values (?, ?, ?, ?, 'RECEIVED', 0, current_timestamp, current_timestamp)
        """, PROVIDER, eventId, eventType, payloadSha256);
  }

  public Optional<String> payloadHash(String eventId) {
    return jdbcTemplate.query("""
        select payload_sha256
        from platform_webhook_event
        where provider = ? and event_id = ?
        """, (rs, rowNum) -> rs.getString("payload_sha256"), PROVIDER, eventId)
        .stream()
        .findFirst();
  }

  public int recordDuplicate(String eventId, String payloadSha256) {
    return jdbcTemplate.update("""
        update platform_webhook_event
        set duplicate_count = duplicate_count + 1,
            last_received_at = current_timestamp
        where provider = ? and event_id = ? and payload_sha256 = ?
        """, PROVIDER, eventId, payloadSha256);
  }
}
