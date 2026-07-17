package com.storeprofit.system.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.platform.auth.PasswordService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdminBootstrapCommandTest {
  private static final String STRONG_PASSWORD = "Zebra!Cloud4826";

  @Test
  void bootstrapFlywayCandidateMatchesContiguousMigrationResources() throws Exception {
    Path migrationDirectory = Path.of("src", "main", "resources", "db", "migration");
    List<Integer> versions;
    try (var files = Files.list(migrationDirectory)) {
      versions = files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.matches("V[1-9][0-9]*__.+\\.sql"))
          .map(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
          .sorted()
          .toList();
    }

    assertThat(versions)
        .containsExactlyElementsOf(
            java.util.stream.IntStream.rangeClosed(
                    1, AdminBootstrapCommand.EXPECTED_FLYWAY_VERSION)
                .boxed()
                .toList());
  }

  @Test
  void disabledMissingInvalidAndWeakInputsNeverOpenDatabase() {
    AtomicInteger connections = new AtomicInteger();
    AdminBootstrapCommand command = new AdminBootstrapCommand(
        new PasswordService(),
        config -> {
          connections.incrementAndGet();
          throw new SQLException("must not connect");
        },
        (connection, config) -> { });

    Map<String, String> disabled = validEnvironment();
    disabled.remove("APP_BOOTSTRAP_ADMIN_ENABLED");
    assertThat(command.execute(arguments(), disabled, () -> STRONG_PASSWORD.toCharArray()).exitCode())
        .isEqualTo(3);

    Map<String, String> missing = validEnvironment();
    missing.remove("APP_BOOTSTRAP_ADMIN_USERNAME");
    assertThat(command.execute(arguments(), missing, () -> STRONG_PASSWORD.toCharArray()).exitCode())
        .isEqualTo(2);

    assertThat(command.execute(arguments(), validEnvironment(), () -> null).exitCode())
        .isEqualTo(2);
    assertThat(command.execute(arguments(), validEnvironment(), () -> "weak-password".toCharArray())
        .exitCode()).isEqualTo(2);
    assertThat(command.execute(
        new String[] {"--admin-bootstrap", "--password=forbidden"},
        validEnvironment(),
        () -> STRONG_PASSWORD.toCharArray()).exitCode()).isEqualTo(2);
    assertThat(connections).hasValue(0);
  }

  @Test
  void tenantMustExistBeActiveAndMatchConfirmationExactly() throws Exception {
    Fixture missing = fixture(false);
    assertThat(missing.command().execute(arguments(), validEnvironment(), password()).exitCode())
        .isEqualTo(4);
    assertNoSecurityWrites(missing);

    Fixture inactive = fixture(false);
    inactive.insertTenant("Exact Tenant", "INACTIVE");
    assertThat(inactive.command().execute(arguments(), validEnvironment(), password()).exitCode())
        .isEqualTo(4);
    assertNoSecurityWrites(inactive);

    Fixture nameMismatch = fixture(false);
    nameMismatch.insertTenant("exact tenant", "ACTIVE");
    assertThat(nameMismatch.command().execute(arguments(), validEnvironment(), password()).exitCode())
        .isEqualTo(4);
    assertNoSecurityWrites(nameMismatch);
  }

  @Test
  void nonEmptyOrPreviouslyInitializedTenantIsRejectedWithoutWrites() throws Exception {
    Fixture existingAccount = fixture(false);
    existingAccount.insertTenant("Exact Tenant", "ACTIVE");
    existingAccount.execute("""
        insert into auth_user(
          tenant_id, username, password_hash, display_name, role, store_id, enabled,
          permission_version
        ) values (9, 'existing', 'hash', 'Existing', 'FINANCE', null, 1, 1)
        """);
    assertThat(existingAccount.command().execute(arguments(), validEnvironment(), password())
        .exitCode()).isEqualTo(5);
    assertThat(existingAccount.count("auth_user")).isEqualTo(1);
    assertThat(existingAccount.count("operation_log")).isZero();

    Fixture previousAudit = fixture(false);
    previousAudit.insertTenant("Exact Tenant", "ACTIVE");
    previousAudit.execute("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          after_json, reason
        ) values (9, null, 'operator', 'first_boss_provisioned', 'auth_user', '1',
                  '{}', 'ticket | approved')
        """);
    assertThat(previousAudit.command().execute(arguments(), validEnvironment(), password())
        .exitCode()).isEqualTo(5);
    assertThat(previousAudit.count("auth_user")).isZero();
    assertThat(previousAudit.count("operation_log")).isEqualTo(1);
  }

  @Test
  void successCreatesOnlyOneBossAndOneNonSensitiveAudit() throws Exception {
    Fixture fixture = fixture(false);
    fixture.insertTenant("Exact Tenant", "ACTIVE");
    char[] suppliedPassword = STRONG_PASSWORD.toCharArray();

    AdminBootstrapResult result = fixture.command().execute(
        arguments(), validEnvironment(), () -> suppliedPassword);

    assertThat(result).isEqualTo(AdminBootstrapResult.created());
    assertThat(suppliedPassword).containsOnly('\0');
    assertThat(fixture.count("auth_user")).isEqualTo(1);
    assertThat(fixture.singleString("select username from auth_user")).isEqualTo("first.boss");
    assertThat(fixture.countWhere("auth_user", "role = 'BOSS' and store_id is null "
        + "and enabled = 1 and permission_version = 1")).isEqualTo(1);
    assertThat(fixture.count("operation_log")).isEqualTo(1);
    assertThat(fixture.countWhere("operation_log",
        "operator_id is null and operator_name = 'Release Operator' "
            + "and action = 'first_boss_provisioned' and target_type = 'auth_user'"))
        .isEqualTo(1);
    assertThat(fixture.countWhere("operation_log",
        "target_id = cast((select id from auth_user) as varchar)"))
        .isEqualTo(1);
    assertThat(fixture.singleString("select after_json from operation_log"))
        .contains("\"role\":\"BOSS\"")
        .doesNotContain(STRONG_PASSWORD, "pbkdf2$", "DatabaseSecret");
    assertThat(fixture.singleString("select reason from operation_log"))
        .isEqualTo("CHG-20260715-001 | approved initial provisioning");
    assertThat(fixture.count("auth_token")).isZero();
    assertThat(fixture.count("role_permission")).isZero();
    assertThat(fixture.count("user_store_scope")).isZero();
    assertThat(fixture.count("user_data_scope")).isZero();
    assertThat(fixture.count("user_permission_override")).isZero();

    String hash = fixture.singleString("select password_hash from auth_user");
    assertThat(new PasswordService().matches(STRONG_PASSWORD, hash)).isTrue();

    Map<String, String> repeated = validEnvironment();
    repeated.put("APP_BOOTSTRAP_ADMIN_USERNAME", "second.candidate");
    assertThat(fixture.command().execute(arguments(), repeated, password()))
        .isEqualTo(AdminBootstrapResult.alreadyInitialized());
    assertThat(fixture.count("auth_user")).isEqualTo(1);
    assertThat(fixture.count("operation_log")).isEqualTo(1);
    assertThat(fixture.count("auth_token")).isZero();
  }

  @Test
  void auditFailureRollsBackTheAccount() throws Exception {
    Fixture fixture = fixture(true);
    fixture.insertTenant("Exact Tenant", "ACTIVE");

    AdminBootstrapResult result = fixture.command().execute(
        arguments(), validEnvironment(), password());

    assertThat(result).isEqualTo(AdminBootstrapResult.transactionFailed());
    assertThat(fixture.count("auth_user")).isZero();
    assertThat(fixture.count("operation_log")).isZero();
  }

  @Test
  void connectionCloseFailureAfterCommitDoesNotOverrideSuccess() throws Exception {
    Fixture fixture = fixture(false);
    fixture.insertTenant("Exact Tenant", "ACTIVE");
    Connection delegate = DriverManager.getConnection(fixture.url);
    Connection closeFailing = proxy(delegate, methodName -> {
      if ("close".equals(methodName)) {
        throw new SQLException("simulated close failure");
      }
    });
    AdminBootstrapCommand command = new AdminBootstrapCommand(
        new PasswordService(), config -> closeFailing, (connection, config) -> { });

    try {
      assertThat(command.execute(arguments(), validEnvironment(), password()))
          .isEqualTo(AdminBootstrapResult.created());
      assertThat(fixture.count("auth_user")).isEqualTo(1);
      assertThat(fixture.count("operation_log")).isEqualTo(1);
    } finally {
      delegate.close();
    }
  }

  @Test
  void mysqlLockFailureMapsToFixedConcurrentExitCode() throws Exception {
    Fixture fixture = fixture(false);
    Connection delegate = DriverManager.getConnection(fixture.url);
    Connection lockFailing = proxy(delegate, methodName -> {
      if ("prepareStatement".equals(methodName)) {
        throw new SQLException("simulated lock timeout", "41000", 1205);
      }
    });
    AdminBootstrapCommand command = new AdminBootstrapCommand(
        new PasswordService(), config -> lockFailing, (connection, config) -> { });

    assertThat(command.execute(arguments(), validEnvironment(), password()))
        .isEqualTo(AdminBootstrapResult.concurrentFailure());
    assertThat(fixture.count("auth_user")).isZero();
  }

  @Test
  void fixedResultAndClearedPasswordNeverExposeSecretsOrExceptions() throws Exception {
    String rawSecret = "LeakMarker!4826";
    char[] supplied = rawSecret.toCharArray();
    AdminBootstrapCommand command = new AdminBootstrapCommand(
        new PasswordService(),
        config -> DriverManager.getConnection(
            "jdbc:h2:mem:secret_" + UUID.randomUUID() + ";MODE=MySQL"),
        (connection, config) -> {
          throw new SQLException("password=" + rawSecret + "; hash=pbkdf2$SECRET");
        });

    AdminBootstrapResult result = command.execute(
        arguments(), validEnvironment(), () -> supplied);

    assertThat(result).isEqualTo(AdminBootstrapResult.safetyRejected());
    assertThat(result.toString()).doesNotContain(rawSecret, "pbkdf2$", "DatabaseSecret");
    assertThat(supplied).containsOnly('\0');
  }

  private AdminBootstrapPasswordSource password() {
    return () -> STRONG_PASSWORD.toCharArray();
  }

  private String[] arguments() {
    return new String[] {"--admin-bootstrap"};
  }

  private Map<String, String> validEnvironment() {
    Map<String, String> environment = new HashMap<>();
    environment.put("APP_BOOTSTRAP_ADMIN_ENABLED", "true");
    environment.put("APP_BOOTSTRAP_ADMIN_TENANT_ID", "9");
    environment.put("APP_BOOTSTRAP_ADMIN_TENANT_NAME_CONFIRM", "Exact Tenant");
    environment.put("APP_BOOTSTRAP_ADMIN_USERNAME", " First.Boss ");
    environment.put("APP_BOOTSTRAP_ADMIN_DISPLAY_NAME", "Initial Boss");
    environment.put("APP_BOOTSTRAP_ADMIN_OPERATOR", "Release Operator");
    environment.put("APP_BOOTSTRAP_ADMIN_TICKET", "CHG-20260715-001");
    environment.put("APP_BOOTSTRAP_ADMIN_REASON", "approved initial provisioning");
    environment.put("APP_ENV", "QA");
    environment.put("MYSQL_HOST", "127.0.0.1");
    environment.put("MYSQL_PORT", "3312");
    environment.put("MYSQL_DATABASE", "ai_profit_qa_r102_test");
    environment.put("MYSQL_USERNAME", "r102_app");
    environment.put("MYSQL_PASSWORD", " DatabaseSecret ");
    environment.put("MYSQL_SSL_MODE", "DISABLED");
    return environment;
  }

  private Fixture fixture(boolean rejectAudit) throws SQLException {
    return new Fixture(rejectAudit);
  }

  private void assertNoSecurityWrites(Fixture fixture) throws SQLException {
    assertThat(fixture.count("auth_user")).isZero();
    assertThat(fixture.count("auth_token")).isZero();
    assertThat(fixture.count("operation_log")).isZero();
    assertThat(fixture.count("role_permission")).isZero();
    assertThat(fixture.count("user_store_scope")).isZero();
    assertThat(fixture.count("user_data_scope")).isZero();
    assertThat(fixture.count("user_permission_override")).isZero();
  }

  private Connection proxy(Connection delegate, MethodInterceptor interceptor) {
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> {
          interceptor.before(method.getName());
          try {
            return method.invoke(delegate, arguments);
          } catch (InvocationTargetException exception) {
            throw exception.getCause();
          }
        });
  }

  @FunctionalInterface
  private interface MethodInterceptor {
    void before(String methodName) throws SQLException;
  }

  private static final class Fixture {
    private final String url = "jdbc:h2:mem:r102_" + UUID.randomUUID()
        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    private Fixture(boolean rejectAudit) throws SQLException {
      try (Connection connection = DriverManager.getConnection(url);
           Statement statement = connection.createStatement()) {
        statement.execute("""
            create table tenant(
              id bigint primary key, name varchar(160) not null, status varchar(40) not null
            )
            """);
        statement.execute("""
            create table auth_user(
              id bigint auto_increment primary key,
              tenant_id bigint not null,
              username varchar(80) not null,
              password_hash varchar(255) not null,
              display_name varchar(120) not null,
              role varchar(40) not null,
              store_id varchar(64),
              enabled tinyint not null,
              permission_version bigint not null,
              created_at timestamp default current_timestamp,
              unique(tenant_id, username)
            )
            """);
        statement.execute("""
            create table auth_token(
              token_hash varchar(64) primary key, tenant_id bigint, user_id bigint,
              permission_version bigint
            )
            """);
        String auditConstraint = rejectAudit
            ? ", constraint reject_bootstrap_audit check (action <> 'first_boss_provisioned')"
            : "";
        statement.execute("""
            create table operation_log(
              id bigint auto_increment primary key,
              tenant_id bigint not null,
              operator_id bigint,
              operator_name varchar(120),
              action varchar(80) not null,
              target_type varchar(80) not null,
              target_id varchar(120),
              store_id varchar(64),
              before_json clob,
              after_json clob,
              reason varchar(255),
              created_at timestamp default current_timestamp
            """ + auditConstraint + ")");
        statement.execute("create table role_permission(tenant_id bigint, role_code varchar(40), permission_code varchar(120))");
        statement.execute("create table user_store_scope(tenant_id bigint, user_id bigint, store_id varchar(64))");
        statement.execute("create table user_data_scope(tenant_id bigint, user_id bigint, domain_code varchar(40))");
        statement.execute("create table user_permission_override(tenant_id bigint, user_id bigint, permission_code varchar(120))");
      }
    }

    private AdminBootstrapCommand command() {
      return new AdminBootstrapCommand(
          new PasswordService(),
          config -> DriverManager.getConnection(url),
          (connection, config) -> { });
    }

    private void insertTenant(String name, String status) throws SQLException {
      try (Connection connection = DriverManager.getConnection(url);
           var statement = connection.prepareStatement(
               "insert into tenant(id, name, status) values (9, ?, ?)")) {
        statement.setString(1, name);
        statement.setString(2, status);
        statement.executeUpdate();
      }
    }

    private void execute(String sql) throws SQLException {
      try (Connection connection = DriverManager.getConnection(url);
           Statement statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }

    private int count(String table) throws SQLException {
      return countWhere(table, "1 = 1");
    }

    private int countWhere(String table, String predicate) throws SQLException {
      try (Connection connection = DriverManager.getConnection(url);
           Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery(
               "select count(*) from " + table + " where " + predicate)) {
        result.next();
        return result.getInt(1);
      }
    }

    private String singleString(String sql) throws SQLException {
      try (Connection connection = DriverManager.getConnection(url);
           Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery(sql)) {
        result.next();
        return result.getString(1);
      }
    }
  }
}
