package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthTokenHashMigrationTest {
  private static final String LEGACY_RAW_TOKEN = "legacy-plain-session-token";

  @Test
  void v58HashesExistingSessionsAndRemovesTheRawTokenColumn() {
    DataSource dataSource = dataSource();
    migrateTo(dataSource, "56");
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    AuthRepository repository = new AuthRepository(jdbcTemplate);
    repository.createUser(
        1L,
        "legacy-token-user",
        new PasswordService().hash("test-password"),
        "旧会话测试用户",
        "FINANCE",
        null
    );
    AuthUser user = repository.findByUsername(1L, "legacy-token-user").orElseThrow();
    jdbcTemplate.update("""
        insert into auth_token(token, tenant_id, user_id, permission_version, expires_at, created_at)
        values (?, ?, ?, ?, ?, current_timestamp)
        """, LEGACY_RAW_TOKEN, user.tenantId(), user.id(), user.permissionVersion(),
        java.sql.Timestamp.from(OffsetDateTime.now().plusHours(1).toInstant()));

    migrateTo(dataSource, "58");

    String storedHash = jdbcTemplate.queryForObject(
        "select token_hash from auth_token where user_id = ?", String.class, user.id());
    assertThat(storedHash).hasSize(64).isNotEqualTo(LEGACY_RAW_TOKEN);
    assertThat(jdbcTemplate.queryForList("""
        select lower(column_name)
        from information_schema.columns
        where lower(table_name) = 'auth_token'
        """, String.class)).contains("token_hash").doesNotContain("token");
    assertThat(repository.findByToken(LEGACY_RAW_TOKEN)).isPresent();
  }

  private void migrateTo(DataSource dataSource, String target) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(target)
        .baselineOnMigrate(true)
        .load()
        .migrate();
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:auth_token_hash_" + UUID.randomUUID()
        + ";MODE=MySQL;NON_KEYWORDS=MONTH;DATABASE_TO_LOWER=FALSE;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
