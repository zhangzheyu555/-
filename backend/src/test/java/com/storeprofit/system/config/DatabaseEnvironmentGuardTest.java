package com.storeprofit.system.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseEnvironmentGuardTest {
  @Test
  void missingVariablesFailBeforeApplicationStartup() {
    assertThatThrownBy(() -> DatabaseEnvironmentGuard.validate(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.environment")
        .hasMessageContaining("spring.datasource.password");
  }

  @Test
  void testEnvironmentCannotPointAtUnmarkedDatabase() {
    MockEnvironment environment = validEnvironment()
        .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/store_profit");

    assertThatThrownBy(() -> DatabaseEnvironmentGuard.validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MYSQL_DATABASE")
        .hasMessageNotContaining("TEST_ONLY_PASSWORD_NOT_A_SECRET");
  }

  @Test
  void springCommandLineStylePropertiesPassValidation() {
    DatabaseEnvironmentGuard.validate(validEnvironment());
  }

  @Test
  void markedTestDatabasePassesValidation() {
    DatabaseEnvironmentGuard.validate(validEnvironment());
  }

  @Test
  void productionRequiresVerifiedDatabaseTls() {
    MockEnvironment environment = validEnvironment()
        .withProperty("app.environment", "PRODUCTION")
        .withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3307/store_profit_mysql8?sslMode=DISABLED");

    assertThatThrownBy(() -> DatabaseEnvironmentGuard.validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MYSQL_SSL_MODE=VERIFY_IDENTITY");
  }

  @Test
  void productionRejectsPublicKeyRetrievalEvenWithVerifiedTls() {
    MockEnvironment environment = validEnvironment()
        .withProperty("app.environment", "PRODUCTION")
        .withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3307/store_profit_mysql8?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=true");

    assertThatThrownBy(() -> DatabaseEnvironmentGuard.validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL");
  }

  @Test
  void productionAllowsVerifiedDatabaseTls() {
    MockEnvironment environment = validEnvironment()
        .withProperty("app.environment", "PRODUCTION")
        .withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3307/store_profit_mysql8?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false");

    DatabaseEnvironmentGuard.validate(environment);
  }

  @Test
  void stagingRejectsPort3306() {
    MockEnvironment environment = validEnvironment()
        .withProperty("app.environment", "STAGING")
        .withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3306/store_profit_mysql8?sslMode=DISABLED");

    assertThatThrownBy(() -> DatabaseEnvironmentGuard.validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("port 3307");
  }

  @Test
  void stagingRejectsUnapprovedDatabase() {
    MockEnvironment environment = validEnvironment()
        .withProperty("app.environment", "STAGING")
        .withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3307/store_profit_mysql8_final?sslMode=DISABLED");

    assertThatThrownBy(() -> DatabaseEnvironmentGuard.validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approved");
  }

  @Test
  void stagingAllowsOnlyApprovedDatabaseOn3307() {
    MockEnvironment environment = validEnvironment()
        .withProperty("app.environment", "STAGING")
        .withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3307/store_profit_mysql8?sslMode=DISABLED");

    DatabaseEnvironmentGuard.validate(environment);
  }

  @Test
  void stagingRejectsRootDatabaseAccount() {
    MockEnvironment environment = validEnvironment()
        .withProperty("app.environment", "STAGING")
        .withProperty("spring.datasource.username", "root")
        .withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3307/store_profit_mysql8?sslMode=DISABLED");

    assertThatThrownBy(() -> DatabaseEnvironmentGuard.validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("root");
  }

  @Test
  void stagingRejectsIndependentFlywayConnection() {
    MockEnvironment environment = validEnvironment()
        .withProperty("app.environment", "STAGING")
        .withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3307/store_profit_mysql8?sslMode=DISABLED")
        .withProperty("spring.flyway.url", "jdbc:mysql://127.0.0.1:3307/other_database");

    assertThatThrownBy(() -> DatabaseEnvironmentGuard.validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("separate Flyway");
  }

  @Test
  void stagingRejectsUnsafeFlywayOverrides() {
    MockEnvironment environment = validEnvironment()
        .withProperty("app.environment", "STAGING")
        .withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3307/store_profit_mysql8?sslMode=DISABLED")
        .withProperty("spring.flyway.baseline-on-migrate", "true");

    assertThatThrownBy(() -> DatabaseEnvironmentGuard.validate(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("baseline-on-migrate");
  }

  private MockEnvironment validEnvironment() {
    return new MockEnvironment()
        .withProperty("app.environment", "TEST")
        .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/store_profit_test")
        .withProperty("spring.datasource.username", "TEST_ONLY_USER")
        .withProperty("spring.datasource.password", "TEST_ONLY_PASSWORD_NOT_A_SECRET")
        .withProperty("spring.flyway.enabled", "true")
        .withProperty("spring.flyway.locations", "classpath:db/migration")
        .withProperty("spring.flyway.baseline-on-migrate", "false")
        .withProperty("spring.flyway.out-of-order", "false")
        .withProperty("spring.flyway.clean-disabled", "true")
        .withProperty("spring.flyway.validate-on-migrate", "true")
        .withProperty("spring.flyway.table", "flyway_schema_history");
  }
}
