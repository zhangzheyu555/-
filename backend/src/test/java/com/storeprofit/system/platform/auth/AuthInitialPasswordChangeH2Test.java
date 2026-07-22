package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AuthInitialPasswordChangeH2Test {
  private static final String INITIAL_PASSWORD = "TestOnly!InitialA9";
  private static final String NEW_PASSWORD = "TestOnly!ChangedB8";

  @Test
  void ordinaryAccountsKeepTheMigrationDefaultWithoutForcedPasswordChange() {
    Fixture fixture = fixture(new AuditRepositoryHolder());
    fixture.repository().createUser(1L, "ordinary-user", new PasswordService().hash(NEW_PASSWORD),
        "普通合成账号", "EMPLOYEE", "INITIAL_PASSWORD_STORE");
    long userId = fixture.repository().findByUsername(1L, "ordinary-user").orElseThrow().id();

    assertThat(fixture.repository().passwordChangeRequired(1L, userId)).isFalse();
    assertThat(fixture.service().login(new LoginRequest("ordinary-user", NEW_PASSWORD, 1L)).status())
        .isEqualTo("AUTHENTICATED");
  }

  @Test
  void initialPasswordOnlyIssuesRestrictedGrantAndChangeMakesItUnusable() {
    Fixture fixture = fixture(new AuditRepositoryHolder());
    long userId = fixture.createPendingEmployee("first-change", INITIAL_PASSWORD);
    long otherUserId = fixture.createPendingEmployee("other-user", "TestOnly!OtherC7");

    LoginResponse pending = fixture.service().login(new LoginRequest("first-change", INITIAL_PASSWORD, 1L));

    assertThat(pending.status()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    assertThat(pending.token()).isNull();
    assertThat(pending.user()).isNull();
    assertThat(pending.passwordChangeCredential()).isNotBlank();
    assertThatThrownBy(() -> fixture.service().requireUser("Bearer " + pending.passwordChangeCredential()))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("UNAUTHORIZED");

    String originalOtherHash = fixture.passwordHash(otherUserId);
    long originalPermissionVersion = fixture.permissionVersion(userId);
    String oldSession = "test-only-old-session";
    fixture.repository().createToken(oldSession, 1L, userId, OffsetDateTime.now().plusHours(1));
    int logsBefore = fixture.countLogs();

    fixture.inTransaction(() -> fixture.service().changeInitialPassword(
        new InitialPasswordChangeRequest(pending.passwordChangeCredential(), NEW_PASSWORD, NEW_PASSWORD)));

    assertThat(fixture.repository().passwordChangeRequired(1L, userId)).isFalse();
    assertThat(fixture.permissionVersion(userId)).isEqualTo(originalPermissionVersion + 1);
    assertThat(fixture.repository().passwordChangeRequired(1L, otherUserId)).isTrue();
    assertThat(fixture.passwordHash(otherUserId)).isEqualTo(originalOtherHash);
    assertThat(new PasswordService().matches(INITIAL_PASSWORD, fixture.passwordHash(userId))).isFalse();
    assertThat(new PasswordService().matches(NEW_PASSWORD, fixture.passwordHash(userId))).isTrue();
    assertThatThrownBy(() -> fixture.service().requireUser("Bearer " + oldSession))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> fixture.service().login(new LoginRequest("first-change", INITIAL_PASSWORD, 1L)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("LOGIN_FAILED");
    LoginResponse authenticated = fixture.service().login(new LoginRequest("first-change", NEW_PASSWORD, 1L));
    assertThat(authenticated.status()).isEqualTo("AUTHENTICATED");
    assertThat(authenticated.token()).isNotBlank();
    assertThat(fixture.countLogs()).isEqualTo(logsBefore + 1);
    String audit = fixture.jdbc().queryForObject("""
        select concat_ws(' ', action, reason, before_json, after_json)
        from operation_log where tenant_id = 1 and target_type = 'auth_user' and target_id = ?
        order by id desc limit 1
        """, String.class, String.valueOf(userId));
    assertThat(audit)
        .contains("首次登录修改密码")
        .doesNotContain(INITIAL_PASSWORD, NEW_PASSWORD, pending.passwordChangeCredential());
  }

  @Test
  void mismatchedWeakWrongAndExpiredCredentialsLeaveAccountUnchanged() {
    Fixture fixture = fixture(new AuditRepositoryHolder());
    long userId = fixture.createPendingEmployee("no-write", INITIAL_PASSWORD);
    LoginResponse pending = fixture.service().login(new LoginRequest("no-write", INITIAL_PASSWORD, 1L));
    String originalHash = fixture.passwordHash(userId);

    assertThatThrownBy(() -> fixture.service().changeInitialPassword(
        new InitialPasswordChangeRequest(pending.passwordChangeCredential(), NEW_PASSWORD, "different")))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("PASSWORD_CONFIRMATION_MISMATCH");
    assertThatThrownBy(() -> fixture.service().changeInitialPassword(
        new InitialPasswordChangeRequest(pending.passwordChangeCredential(), "weak", "weak")))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("PASSWORD_INVALID");
    assertThatThrownBy(() -> fixture.service().changeInitialPassword(
        new InitialPasswordChangeRequest("wrong-test-only-credential", NEW_PASSWORD, NEW_PASSWORD)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("PASSWORD_CHANGE_CREDENTIAL_INVALID");

    AuthService immediatelyExpiring = new AuthService(
        fixture.repository(), new PasswordService(), fixture.auditRepository(), 12, 0);
    LoginResponse expired = immediatelyExpiring.login(new LoginRequest("no-write", INITIAL_PASSWORD, 1L));
    assertThatThrownBy(() -> immediatelyExpiring.changeInitialPassword(
        new InitialPasswordChangeRequest(expired.passwordChangeCredential(), NEW_PASSWORD, NEW_PASSWORD)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("PASSWORD_CHANGE_CREDENTIAL_INVALID");

    assertThat(fixture.passwordHash(userId)).isEqualTo(originalHash);
    assertThat(fixture.repository().passwordChangeRequired(1L, userId)).isTrue();
    assertThat(fixture.countLogs()).isZero();
  }

  @Test
  void auditFailureRollsBackPasswordStateVersionAndAudit() {
    AuditRepositoryHolder holder = new AuditRepositoryHolder();
    Fixture fixture = fixture(holder);
    long userId = fixture.createPendingEmployee("rollback-change", INITIAL_PASSWORD);
    String originalHash = fixture.passwordHash(userId);
    long originalVersion = fixture.permissionVersion(userId);
    LoginResponse pending = fixture.service().login(
        new LoginRequest("rollback-change", INITIAL_PASSWORD, 1L));

    holder.failWrites = true;
    assertThatThrownBy(() -> fixture.inTransaction(() -> fixture.service().changeInitialPassword(
        new InitialPasswordChangeRequest(pending.passwordChangeCredential(), NEW_PASSWORD, NEW_PASSWORD))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("test audit failure");

    assertThat(fixture.passwordHash(userId)).isEqualTo(originalHash);
    assertThat(fixture.repository().passwordChangeRequired(1L, userId)).isTrue();
    assertThat(fixture.permissionVersion(userId)).isEqualTo(originalVersion);
    assertThat(fixture.countLogs()).isZero();
  }

  private Fixture fixture(AuditRepositoryHolder holder) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:initial_password_" + UUID.randomUUID()
        + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("""
        insert into store_branch(id, tenant_id, code, name, status, created_at)
        values ('INITIAL_PASSWORD_STORE', 1, 'IP-S', '首次改密合成门店', '营业中', current_timestamp)
        """);
    AuthRepository repository = new AuthRepository(jdbc);
    AuditRepository auditRepository = new AuditRepository(jdbc) {
      @Override
      public void writeLog(AuthUser user, AuditLogRequest request) {
        if (holder.failWrites) {
          throw new IllegalStateException("test audit failure");
        }
        super.writeLog(user, request);
      }
    };
    AuthService service = new AuthService(repository, new PasswordService(), auditRepository, 12);
    return new Fixture(dataSource, jdbc, repository, auditRepository, service);
  }

  private static final class AuditRepositoryHolder {
    private boolean failWrites;
  }

  private record Fixture(
      DataSource dataSource,
      JdbcTemplate jdbc,
      AuthRepository repository,
      AuditRepository auditRepository,
      AuthService service
  ) {
    long createPendingEmployee(String username, String password) {
      repository.createUser(1L, username, new PasswordService().hash(password), username,
          "EMPLOYEE", "INITIAL_PASSWORD_STORE", true);
      return repository.findByUsername(1L, username).orElseThrow().id();
    }

    String passwordHash(long userId) {
      return repository.user(1L, userId).orElseThrow().passwordHash();
    }

    long permissionVersion(long userId) {
      return repository.user(1L, userId).orElseThrow().permissionVersion();
    }

    int countLogs() {
      return jdbc.queryForObject("select count(*) from operation_log", Integer.class);
    }

    void inTransaction(Runnable action) {
      new TransactionTemplate(new DataSourceTransactionManager(dataSource))
          .executeWithoutResult(status -> action.run());
    }
  }
}
