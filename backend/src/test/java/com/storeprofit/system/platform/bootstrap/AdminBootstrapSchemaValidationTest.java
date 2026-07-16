package com.storeprofit.system.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminBootstrapSchemaValidationTest {
  @Test
  void acceptsExactlySuccessfulV1ThroughV56AndRequiredSchema() throws Exception {
    try (Connection connection = schemaConnection(56, false, true)) {
      assertThatNoException().isThrownBy(
          () -> AdminBootstrapCommand.validateFlywayHistory(connection));
      assertThatNoException().isThrownBy(
          () -> AdminBootstrapCommand.validateRequiredSchema(connection));
    }
  }

  @Test
  void rejectsMissingOrFailedMigrationWithoutRunningFlyway() throws Exception {
    try (Connection connection = schemaConnection(55, false, true)) {
      assertThatThrownBy(() -> AdminBootstrapCommand.validateFlywayHistory(connection))
          .isInstanceOf(IllegalStateException.class);
    }
    try (Connection connection = schemaConnection(56, true, true)) {
      assertThatThrownBy(() -> AdminBootstrapCommand.validateFlywayHistory(connection))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void rejectsMissingRequiredTableOrColumn() throws Exception {
    try (Connection connection = schemaConnection(56, false, false)) {
      assertThatThrownBy(() -> AdminBootstrapCommand.validateRequiredSchema(connection))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void requiresInnoDbAndTenantUsernameIndexesForAtomicProvisioning() {
    List<AdminBootstrapCommand.IndexMetadata> validIndexes = List.of(
        new AdminBootstrapCommand.IndexMetadata(
            true, List.of("tenant_id", "username")));
    assertThatNoException().isThrownBy(() ->
        AdminBootstrapCommand.validateTransactionalMetadata(
            Map.of(
                "tenant", "InnoDB",
                "auth_user", "InnoDB",
                "operation_log", "InnoDB"),
            validIndexes));

    assertThatThrownBy(() -> AdminBootstrapCommand.validateTransactionalMetadata(
        Map.of(
            "tenant", "InnoDB",
            "auth_user", "MyISAM",
            "operation_log", "InnoDB"),
        validIndexes)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> AdminBootstrapCommand.validateTransactionalMetadata(
        Map.of(
            "tenant", "InnoDB",
            "auth_user", "InnoDB",
            "operation_log", "InnoDB"),
        List.of(new AdminBootstrapCommand.IndexMetadata(false, List.of("tenant_id")))))
        .isInstanceOf(IllegalStateException.class);
  }

  private Connection schemaConnection(
      int successfulVersions,
      boolean failLast,
      boolean includeAllRequiredTables
  ) throws SQLException {
    Connection connection = DriverManager.getConnection(
        "jdbc:h2:mem:schema_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
    try (Statement statement = connection.createStatement()) {
      statement.execute("""
          create table flyway_schema_history(
            installed_rank int primary key, version varchar(50), success boolean
          )
          """);
      for (int version = 1; version <= successfulVersions; version++) {
        boolean success = !(failLast && version == successfulVersions);
        statement.execute("insert into flyway_schema_history values ("
            + version + ", '" + version + "', " + success + ")");
      }
      statement.execute("create table tenant(id bigint, name varchar(160), status varchar(40))");
      statement.execute("""
          create table auth_user(
            id bigint, tenant_id bigint, username varchar(80), password_hash varchar(255),
            display_name varchar(120), role varchar(40), store_id varchar(64), enabled tinyint,
            permission_version bigint
          )
          """);
      statement.execute("create table auth_token(token varchar(96), tenant_id bigint, user_id bigint, permission_version bigint)");
      statement.execute("""
          create table operation_log(
            id bigint, tenant_id bigint, operator_id bigint, operator_name varchar(120),
            action varchar(80), target_type varchar(80), target_id varchar(120),
            after_json clob, reason varchar(255)
          )
          """);
      statement.execute("create table role_permission(tenant_id bigint, role_code varchar(40), permission_code varchar(120))");
      statement.execute("create table user_store_scope(tenant_id bigint, user_id bigint, store_id varchar(64))");
      statement.execute("create table user_data_scope(tenant_id bigint, user_id bigint, domain_code varchar(40))");
      if (includeAllRequiredTables) {
        statement.execute("create table user_permission_override(tenant_id bigint, user_id bigint, permission_code varchar(120))");
      }
    }
    return connection;
  }
}
