package com.storeprofit.system.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

public final class DatabaseEnvironmentGuard {
  static final String EXPECTED_MYSQL_VERSION = "8.0.46";
  static final String EXPECTED_DATABASE = "store_profit_mysql8";
  static final int EXPECTED_PORT = 3307;
  private static final List<String> REQUIRED = List.of(
      "app.environment", "spring.datasource.url", "spring.datasource.username", "spring.datasource.password");
  private static final Set<String> ALLOWED_ENVIRONMENTS = Set.of("TEST", "QA", "STAGING", "PRODUCTION");
  private static final Set<String> MYSQL8_ONLY_ENVIRONMENTS = Set.of("STAGING", "PRODUCTION");

  private DatabaseEnvironmentGuard() {
  }

  public static void validate(Environment environment) {
    List<String> missing = new ArrayList<>();
    for (String name : REQUIRED) {
      if (isBlank(environment.getProperty(name))) {
        missing.add(name);
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException("Missing required environment variables: " + String.join(", ", missing));
    }

    String appEnvironment = environment.getProperty("app.environment").trim().toUpperCase(Locale.ROOT);
    if (!ALLOWED_ENVIRONMENTS.contains(appEnvironment)) {
      throw new IllegalStateException("APP_ENV must be one of TEST, QA, STAGING or PRODUCTION");
    }

    String datasourceUrl = environment.getProperty("spring.datasource.url").trim();
    MysqlTarget target = mysqlTarget(datasourceUrl);
    String database = target.database();
    if (("TEST".equals(appEnvironment) || "QA".equals(appEnvironment))
        && !database.contains("test") && !database.contains("qa")) {
      throw new IllegalStateException("TEST or QA environments require MYSQL_DATABASE to contain test or qa");
    }

    if (MYSQL8_ONLY_ENVIRONMENTS.contains(appEnvironment)) {
      if (!"127.0.0.1".equals(target.host()) || target.port() != EXPECTED_PORT) {
        throw new IllegalStateException(
            "STAGING and PRODUCTION require the approved local MySQL 8 endpoint on port 3307");
      }
      if (!EXPECTED_DATABASE.equals(database)) {
        throw new IllegalStateException(
            "MYSQL_DATABASE is not the approved STAGING/PRODUCTION database");
      }
      if ("root".equalsIgnoreCase(environment.getRequiredProperty("spring.datasource.username").trim())) {
        throw new IllegalStateException("STAGING and PRODUCTION forbid the MySQL root account");
      }
      validateFlywayUsesApplicationDataSource(environment);
    }

    if ("PRODUCTION".equals(appEnvironment)) {
      String normalizedUrl = datasourceUrl.toLowerCase(Locale.ROOT);
      if (!normalizedUrl.contains("sslmode=verify_identity")) {
        throw new IllegalStateException(
            "PRODUCTION requires MYSQL_SSL_MODE=VERIFY_IDENTITY for database certificate and hostname verification");
      }
      if (normalizedUrl.contains("allowpublickeyretrieval=true")) {
        throw new IllegalStateException(
            "PRODUCTION must not enable MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL");
      }
    }
  }

  private static void validateFlywayUsesApplicationDataSource(Environment environment) {
    for (String property : List.of("spring.flyway.url", "spring.flyway.user", "spring.flyway.password")) {
      if (!isBlank(environment.getProperty(property))) {
        throw new IllegalStateException(
            "STAGING and PRODUCTION forbid a separate Flyway connection");
      }
    }
    if (!environment.getProperty("spring.flyway.enabled", Boolean.class, true)) {
      throw new IllegalStateException("Flyway must remain enabled");
    }
    if (environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, false)) {
      throw new IllegalStateException("Flyway baseline-on-migrate must remain disabled");
    }
    if (environment.getProperty("spring.flyway.out-of-order", Boolean.class, false)) {
      throw new IllegalStateException("Flyway out-of-order migrations are forbidden");
    }
    if (!environment.getProperty("spring.flyway.clean-disabled", Boolean.class, true)) {
      throw new IllegalStateException("Flyway clean must remain disabled");
    }
    if (!environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class, true)) {
      throw new IllegalStateException("Flyway validate-on-migrate must remain enabled");
    }
    String locations = environment.getProperty("spring.flyway.locations", "classpath:db/migration").trim();
    if (!"classpath:db/migration".equals(locations)) {
      throw new IllegalStateException("Flyway must use the approved MySQL migration directory");
    }
    String table = environment.getProperty("spring.flyway.table", "flyway_schema_history").trim();
    if (!"flyway_schema_history".equals(table)) {
      throw new IllegalStateException("Flyway history table override is forbidden");
    }
  }

  private static MysqlTarget mysqlTarget(String datasourceUrl) {
    if (!datasourceUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql://")) {
      throw new IllegalStateException("Only a MySQL JDBC datasource is allowed");
    }
    try {
      URI uri = URI.create(datasourceUrl.substring("jdbc:".length()));
      String path = uri.getPath();
      String database = path == null ? "" : path.replaceFirst("^/", "").trim();
      if (isBlank(uri.getHost()) || uri.getPort() < 1 || isBlank(database)
          || database.contains("/") || !database.matches("[A-Za-z0-9_]+")) {
        throw new IllegalStateException("The MySQL datasource host, port or database is invalid");
      }
      return new MysqlTarget(
          uri.getHost().toLowerCase(Locale.ROOT),
          uri.getPort(),
          database.toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("The MySQL datasource URL is invalid", exception);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record MysqlTarget(String host, int port, String database) {
  }
}
