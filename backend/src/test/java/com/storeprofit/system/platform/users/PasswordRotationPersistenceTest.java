package com.storeprofit.system.platform.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.PasswordService;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PasswordRotationPersistenceTest {
  private static final String USERNAME = "test-rotation-boss";
  private static final String OLD_PASSWORD = "TEST_ONLY_OLD_PASSWORD_2026";
  private static final String NEW_PASSWORD = "TEST_ONLY_NEW_PASSWORD_2026";

  @Test
  void rotationUsesPasswordEncoderRevokesEveryTokenAndPersistsAcrossRepositoryRestart() {
    DataSource dataSource = migratedDataSource();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    AuthRepository repository = new AuthRepository(jdbc);
    PasswordService passwordService = new PasswordService();
    AuditRepository auditRepository = new AuditRepository(jdbc);
    AccessControlService accessControl = mock(AccessControlService.class);
    UserManagementService service = new UserManagementService(
        repository,
        passwordService,
        mock(OrganizationRepository.class),
        accessControl,
        auditRepository
    );

    repository.createUser(1L, USERNAME, passwordService.hash(OLD_PASSWORD), "TEST 轮换老板", "BOSS", null);
    var currentUser = repository.findByUsername(1L, USERNAME).orElseThrow();
    repository.createToken("TEST_OLD_TOKEN_1", 1L, currentUser.id(), OffsetDateTime.now().plusHours(1));
    repository.createToken("TEST_OLD_TOKEN_2", 1L, currentUser.id(), OffsetDateTime.now().plusHours(1));

    service.resetPassword(
        currentUser,
        currentUser.id(),
        new UserPasswordResetRequest(NEW_PASSWORD, OLD_PASSWORD)
    );

    var rotated = repository.findByUsername(1L, USERNAME).orElseThrow();
    assertThat(rotated.passwordHash()).startsWith("pbkdf2$");
    assertThat(passwordService.matches(OLD_PASSWORD, rotated.passwordHash())).isFalse();
    assertThat(passwordService.matches(NEW_PASSWORD, rotated.passwordHash())).isTrue();
    assertThat(repository.findByToken("TEST_OLD_TOKEN_1")).isEmpty();
    assertThat(repository.findByToken("TEST_OLD_TOKEN_2")).isEmpty();
    assertThat(jdbc.queryForObject(
        "select count(*) from auth_token where tenant_id = 1 and user_id = ?",
        Integer.class,
        currentUser.id()
    )).isZero();

    AuthRepository restartedRepository = new AuthRepository(new JdbcTemplate(dataSource));
    PasswordService restartedPasswordService = new PasswordService();
    var afterRestart = restartedRepository.findByUsername(1L, USERNAME).orElseThrow();
    assertThat(afterRestart.passwordHash()).isEqualTo(rotated.passwordHash());
    assertThat(restartedPasswordService.matches(NEW_PASSWORD, afterRestart.passwordHash())).isTrue();

    String auditReason = jdbc.queryForObject(
        "select reason from operation_log where tenant_id = 1 and target_type = 'auth_user' and target_id = ?",
        String.class,
        Long.toString(currentUser.id())
    );
    assertThat(auditReason)
        .contains("旧登录已失效")
        .doesNotContain(OLD_PASSWORD, NEW_PASSWORD, rotated.passwordHash());
  }

  private DataSource migratedDataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:password_rotation;MODE=MySQL;NON_KEYWORDS=MONTH;DATABASE_TO_LOWER=FALSE;DB_CLOSE_DELAY=-1");
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
