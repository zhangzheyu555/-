package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TrainingVideoCategoryEncodingMigrationTest {

  @Test
  void repairsOnlyTheConfirmedMojibakeCategory() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(("""
        jdbc:h2:mem:training-video-category-%s;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """).formatted(UUID.randomUUID()).replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("90")
        .load()
        .migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("""
        insert into auth_user(
          tenant_id, username, password_hash, display_name, role, enabled, created_at
        ) values (1, 'video-encoding-test', 'test-only', '视频编码迁移测试', 'BOSS', 1,
          current_timestamp)
        """);
    Long userId = jdbc.queryForObject(
        "select id from auth_user where tenant_id = 1 and username = 'video-encoding-test'",
        Long.class);
    jdbc.update("""
        insert into warehouse_attachment(
          tenant_id, business_type, business_id, file_name, content_type, file_size,
          storage_path, content, uploaded_by, uploaded_at
        ) values (1, 'TRAINING_VIDEO', 'ENCODING-TEST-1', '乱码测试.mp4', 'video/mp4', 1,
          'mysql://encoding-test-1', X'00', ?, current_timestamp)
        """, userId);
    Long brokenAttachmentId = jdbc.queryForObject(
        "select id from warehouse_attachment where business_id = 'ENCODING-TEST-1'",
        Long.class);
    jdbc.update("""
        insert into warehouse_attachment(
          tenant_id, business_type, business_id, file_name, content_type, file_size,
          storage_path, content, uploaded_by, uploaded_at
        ) values (1, 'TRAINING_VIDEO', 'ENCODING-TEST-2', '正常分类.mp4', 'video/mp4', 1,
          'mysql://encoding-test-2', X'00', ?, current_timestamp)
        """, userId);
    Long normalAttachmentId = jdbc.queryForObject(
        "select id from warehouse_attachment where business_id = 'ENCODING-TEST-2'",
        Long.class);

    jdbc.update("""
        insert into training_video(
          tenant_id, video_code, attachment_id, title, category, created_by
        ) values (1, 'ENCODING-TEST-1', ?, '乱码分类视频', 'è®¾å¤‡åŸ¹è®­', ?)
        """, brokenAttachmentId, userId);
    jdbc.update("""
        insert into training_video(
          tenant_id, video_code, attachment_id, title, category, created_by
        ) values (1, 'ENCODING-TEST-2', ?, '正常分类视频', '设备维护', ?)
        """, normalAttachmentId, userId);

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("102")
        .load()
        .migrate();

    assertThat(jdbc.queryForObject(
        "select category from training_video where video_code = 'ENCODING-TEST-1'",
        String.class)).isEqualTo("设备培训");
    assertThat(jdbc.queryForObject(
        "select category from training_video where video_code = 'ENCODING-TEST-2'",
        String.class)).isEqualTo("设备维护");
  }
}
