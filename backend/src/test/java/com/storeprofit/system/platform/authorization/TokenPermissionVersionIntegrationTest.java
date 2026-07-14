package com.storeprofit.system.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.PasswordService;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TokenPermissionVersionIntegrationTest {
  @Test
  void staleTokenVersionIsRejectedEvenBeforeTokenCleanupRuns() {
    DataSource dataSource = migratedDataSource();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    AuthRepository authRepository = new AuthRepository(jdbcTemplate);
    authRepository.createUser(
        1L, "stale-version-user", new PasswordService().hash("test-password"),
        "旧版本测试", "OPERATIONS", null);
    var user = authRepository.findByUsername(1L, "stale-version-user").orElseThrow();
    authRepository.createToken(
        "TEST_STALE_PERMISSION_TOKEN",
        user.tenantId(),
        user.id(),
        user.permissionVersion(),
        OffsetDateTime.now().plusHours(1)
    );

    jdbcTemplate.update("""
        update auth_user set permission_version = permission_version + 1
        where tenant_id = ? and id = ?
        """, user.tenantId(), user.id());

    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from auth_token where token = 'TEST_STALE_PERMISSION_TOKEN'",
        Integer.class)).isEqualTo(1);
    assertThat(authRepository.findByToken("TEST_STALE_PERMISSION_TOKEN")).isEmpty();
  }

  @Test
  void permissionVersionChangeRevokesEveryExistingToken() {
    DataSource dataSource = migratedDataSource();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    AuthRepository authRepository = new AuthRepository(jdbcTemplate);
    AuthorizationRepository authorizationRepository = new AuthorizationRepository(jdbcTemplate);
    authRepository.createUser(
        1L, "permission-version-user", new PasswordService().hash("test-password"),
        "权限版本测试", "FINANCE", null);
    var user = authRepository.findByUsername(1L, "permission-version-user").orElseThrow();
    authRepository.createToken(
        "TEST_PERMISSION_VERSION_TOKEN",
        user.tenantId(),
        user.id(),
        user.permissionVersion(),
        OffsetDateTime.now().plusHours(1)
    );

    assertThat(authRepository.findByToken("TEST_PERMISSION_VERSION_TOKEN")).isPresent();

    long nextVersion = authorizationRepository.incrementPermissionVersionAndDeleteTokens(
        user.tenantId(), user.id());

    assertThat(nextVersion).isEqualTo(user.permissionVersion() + 1);
    assertThat(authRepository.findByToken("TEST_PERMISSION_VERSION_TOKEN")).isEmpty();
  }

  private DataSource migratedDataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:permission_version;MODE=MySQL;NON_KEYWORDS=MONTH;DATABASE_TO_LOWER=FALSE;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .baselineOnMigrate(true)
        .load()
        .migrate();
    return dataSource;
  }
}
