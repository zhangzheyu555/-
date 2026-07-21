package com.storeprofit.system.platform.bootstrap;

import com.storeprofit.system.config.DatabaseEnvironmentGuard;
import com.storeprofit.system.config.DatabaseIdentityValidator;
import com.storeprofit.system.platform.auth.PasswordService;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class AdminBootstrapCommand {
  public static final String COMMAND_ARGUMENT = "--admin-bootstrap";
  public static final String ENABLED_ENVIRONMENT = "APP_BOOTSTRAP_ADMIN_ENABLED";
  static final int EXPECTED_FLYWAY_VERSION = 78;

  private final PasswordService passwordService;
  private final ConnectionFactory connectionFactory;
  private final DatabaseValidator databaseValidator;

  public AdminBootstrapCommand() {
    this(new PasswordService(), AdminBootstrapCommand::openConnection,
        AdminBootstrapCommand::validateDatabase);
  }

  AdminBootstrapCommand(
      PasswordService passwordService,
      ConnectionFactory connectionFactory,
      DatabaseValidator databaseValidator
  ) {
    this.passwordService = passwordService;
    this.connectionFactory = connectionFactory;
    this.databaseValidator = databaseValidator;
  }

  public AdminBootstrapResult execute(
      String[] arguments,
      Map<String, String> environment,
      AdminBootstrapPasswordSource passwordSource
  ) {
    char[] password = null;
    try {
      if (!hasOnlyCommandArgument(arguments)) {
        return AdminBootstrapResult.inputInvalid();
      }
      if (environment == null || !"true".equals(environment.get(ENABLED_ENVIRONMENT))) {
        return AdminBootstrapResult.safetyRejected();
      }

      BootstrapInput input;
      try {
        input = BootstrapInput.from(environment);
      } catch (IllegalArgumentException exception) {
        return AdminBootstrapResult.inputInvalid();
      }

      DatabaseConfig databaseConfig;
      try {
        databaseConfig = DatabaseConfig.from(environment);
      } catch (IllegalArgumentException | IllegalStateException exception) {
        return AdminBootstrapResult.safetyRejected();
      }

      try {
        password = passwordSource == null ? null : passwordSource.readPassword();
      } catch (IOException exception) {
        return AdminBootstrapResult.inputInvalid();
      }
      if (!AdminBootstrapPasswordPolicy.isValid(password, input.username())) {
        return AdminBootstrapResult.inputInvalid();
      }

      String passwordHash = passwordService.hash(password);
      Connection connection = null;
      try {
        connection = connectionFactory.open(databaseConfig);
        try {
          databaseValidator.validate(connection, databaseConfig);
        } catch (SQLException | IllegalStateException exception) {
          return AdminBootstrapResult.safetyRejected();
        }
        return provision(connection, input, passwordHash);
      } catch (SQLException exception) {
        return AdminBootstrapResult.safetyRejected();
      } finally {
        closeQuietly(connection);
      }
    } catch (RuntimeException exception) {
      return AdminBootstrapResult.unexpectedFailure();
    } finally {
      if (password != null) {
        Arrays.fill(password, '\0');
      }
    }
  }

  static boolean hasOnlyCommandArgument(String[] arguments) {
    return arguments != null
        && arguments.length == 1
        && COMMAND_ARGUMENT.equals(arguments[0]);
  }

  private AdminBootstrapResult provision(
      Connection connection,
      BootstrapInput input,
      String passwordHash
  ) {
    try {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);

      Tenant tenant = lockTenant(connection, input.tenantId());
      if (tenant == null
          || !"ACTIVE".equals(tenant.status())
          || !input.tenantNameConfirm().equals(tenant.name())) {
        connection.rollback();
        return AdminBootstrapResult.tenantRejected();
      }
      if (hasAnyAccount(connection, input.tenantId())
          || hasInitializationAudit(connection, input.tenantId())) {
        connection.rollback();
        return AdminBootstrapResult.alreadyInitialized();
      }

      long userId = insertBoss(connection, input, passwordHash);
      insertInitializationAudit(connection, input, userId);
      connection.commit();
      return AdminBootstrapResult.created();
    } catch (SQLException exception) {
      rollbackQuietly(connection);
      return isConcurrencyFailure(exception)
          ? AdminBootstrapResult.concurrentFailure()
          : AdminBootstrapResult.transactionFailed();
    } catch (RuntimeException exception) {
      rollbackQuietly(connection);
      return AdminBootstrapResult.unexpectedFailure();
    }
  }

  private Tenant lockTenant(Connection connection, long tenantId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "select name, status from tenant where id = ? for update")) {
      statement.setLong(1, tenantId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? new Tenant(rows.getString("name"), rows.getString("status")) : null;
      }
    }
  }

  private boolean hasAnyAccount(Connection connection, long tenantId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "select id from auth_user where tenant_id = ? order by id for update")) {
      statement.setLong(1, tenantId);
      try (ResultSet rows = statement.executeQuery()) {
        boolean found = false;
        while (rows.next()) {
          found = true;
        }
        return found;
      }
    }
  }

  private boolean hasInitializationAudit(Connection connection, long tenantId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select id
        from operation_log
        where tenant_id = ? and action = 'first_boss_provisioned'
        order by id
        for update
        """)) {
      statement.setLong(1, tenantId);
      try (ResultSet rows = statement.executeQuery()) {
        boolean found = false;
        while (rows.next()) {
          found = true;
        }
        return found;
      }
    }
  }

  private long insertBoss(
      Connection connection,
      BootstrapInput input,
      String passwordHash
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        insert into auth_user(
          tenant_id, username, password_hash, display_name, role,
          store_id, enabled, permission_version, created_at
        )
        values (?, ?, ?, ?, 'BOSS', null, 1, 1, current_timestamp)
        """, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, input.tenantId());
      statement.setString(2, input.username());
      statement.setString(3, passwordHash);
      statement.setString(4, input.displayName());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Unexpected account insert count");
      }
      try (ResultSet keys = statement.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new SQLException("Generated account identifier is unavailable");
        }
        return keys.getLong(1);
      }
    }
  }

  private void insertInitializationAudit(
      Connection connection,
      BootstrapInput input,
      long userId
  ) throws SQLException {
    String afterJson = "{\"tenantId\":" + input.tenantId()
        + ",\"username\":\"" + jsonEscape(input.username())
        + "\",\"displayName\":\"" + jsonEscape(input.displayName())
        + "\",\"role\":\"BOSS\",\"storeId\":null,\"enabled\":true,"
        + "\"permissionVersion\":1}";
    try (PreparedStatement statement = connection.prepareStatement("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type,
          target_id, store_id, before_json, after_json, reason, created_at
        )
        values (?, null, ?, 'first_boss_provisioned', 'auth_user', ?,
                null, null, ?, ?, current_timestamp)
        """)) {
      statement.setLong(1, input.tenantId());
      statement.setString(2, input.operatorName());
      statement.setString(3, Long.toString(userId));
      statement.setString(4, afterJson);
      statement.setString(5, input.auditReason());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Unexpected audit insert count");
      }
    }
  }

  private static Connection openConnection(DatabaseConfig config) throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", config.username());
    properties.setProperty("password", config.password());
    try {
      return DriverManager.getConnection(config.jdbcUrl(), properties);
    } finally {
      properties.remove("password");
    }
  }

  private static void validateDatabase(Connection connection, DatabaseConfig config)
      throws SQLException {
    String version;
    int actualPort;
    String actualDatabase;
    String account;
    try (Statement statement = connection.createStatement();
         ResultSet row = statement.executeQuery(
             "select version(), @@port, database(), current_user()")) {
      if (!row.next()) {
        throw new IllegalStateException("Database identity is unavailable");
      }
      version = row.getString(1);
      actualPort = row.getInt(2);
      actualDatabase = row.getString(3);
      account = row.getString(4);
    }
    List<String> grants = new ArrayList<>();
    try (Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("show grants for current_user")) {
      while (rows.next()) {
        grants.add(rows.getString(1));
      }
    }
    DatabaseIdentityValidator.validate(
        version,
        actualPort,
        actualDatabase,
        account,
        grants,
        config.port(),
        config.database(),
        config.username());
    validateFlywayHistory(connection);
    validateRequiredSchema(connection);
    validateTransactionalSchema(connection, config.database());
    try (Statement statement = connection.createStatement()) {
      statement.execute("set session innodb_lock_wait_timeout = 5");
    }
  }

  static void validateFlywayHistory(Connection connection) throws SQLException {
    Set<Integer> versions = new HashSet<>();
    try (Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("""
             select version, success
             from flyway_schema_history
             order by installed_rank
             """)) {
      while (rows.next()) {
        String versionText = rows.getString("version");
        if (!rows.getBoolean("success")
            || versionText == null
            || !versionText.matches("[1-9][0-9]*")) {
          throw new IllegalStateException("Flyway history is incomplete");
        }
        int version;
        try {
          version = Integer.parseInt(versionText);
        } catch (NumberFormatException exception) {
          throw new IllegalStateException("Flyway history is incomplete", exception);
        }
        if (!versions.add(version)) {
          throw new IllegalStateException("Flyway history contains a duplicate version");
        }
      }
    }
    if (versions.size() != EXPECTED_FLYWAY_VERSION) {
      throw new IllegalStateException("Flyway history does not match the candidate");
    }
    for (int version = 1; version <= EXPECTED_FLYWAY_VERSION; version++) {
      if (!versions.contains(version)) {
        throw new IllegalStateException("Flyway history does not match the candidate");
      }
    }
  }

  static void validateRequiredSchema(Connection connection) throws SQLException {
    for (String query : List.of(
        "select id, name, status from tenant where 1 = 0",
        "select id, tenant_id, username, password_hash, display_name, role, store_id, "
            + "enabled, permission_version from auth_user where 1 = 0",
        "select token_hash, tenant_id, user_id, permission_version from auth_token where 1 = 0",
        "select id, tenant_id, operator_id, operator_name, action, target_type, target_id, "
            + "after_json, reason from operation_log where 1 = 0",
        "select tenant_id, role_code, permission_code from role_permission where 1 = 0",
        "select tenant_id, user_id, store_id from user_store_scope where 1 = 0",
        "select tenant_id, user_id, domain_code from user_data_scope where 1 = 0",
        "select tenant_id, user_id, permission_code from user_permission_override where 1 = 0")) {
      try (Statement statement = connection.createStatement();
           ResultSet ignored = statement.executeQuery(query)) {
        // Column resolution is the validation.
      }
    }
  }

  static void validateTransactionalSchema(Connection connection, String database)
      throws SQLException {
    Map<String, String> engines = new java.util.HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        select table_name, engine
        from information_schema.tables
        where table_schema = ?
          and table_name in ('tenant', 'auth_user', 'operation_log')
        """)) {
      statement.setString(1, database);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          engines.put(rows.getString("table_name").toLowerCase(Locale.ROOT),
              rows.getString("engine"));
        }
      }
    }

    Map<String, MutableIndex> indexes = new java.util.LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        select index_name, non_unique, seq_in_index, column_name
        from information_schema.statistics
        where table_schema = ? and table_name = 'auth_user'
        order by index_name, seq_in_index
        """)) {
      statement.setString(1, database);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String name = rows.getString("index_name");
          MutableIndex index = indexes.computeIfAbsent(
              name,
              ignored -> new MutableIndex(rowsBoolean(rows, "non_unique") == false));
          String columnName = rows.getString("column_name");
          if (columnName == null) {
            throw new IllegalStateException("Database index metadata is unavailable");
          }
          index.columns().add(columnName.toLowerCase(Locale.ROOT));
        }
      }
    }
    validateTransactionalMetadata(engines, indexes.values().stream()
        .map(index -> new IndexMetadata(index.unique(), List.copyOf(index.columns())))
        .toList());
  }

  static void validateTransactionalMetadata(
      Map<String, String> engines,
      List<IndexMetadata> authUserIndexes
  ) {
    for (String table : List.of("tenant", "auth_user", "operation_log")) {
      if (!"innodb".equalsIgnoreCase(engines.get(table))) {
        throw new IllegalStateException("Bootstrap transaction requires InnoDB tables");
      }
    }
    boolean tenantIndex = authUserIndexes.stream()
        .anyMatch(index -> !index.columns().isEmpty()
            && "tenant_id".equals(index.columns().get(0)));
    boolean tenantUsernameUnique = authUserIndexes.stream()
        .anyMatch(index -> index.unique()
            && index.columns().equals(List.of("tenant_id", "username")));
    if (!tenantIndex || !tenantUsernameUnique) {
      throw new IllegalStateException("The auth_user tenant indexes are incomplete");
    }
  }

  private static boolean rowsBoolean(ResultSet rows, String column) {
    try {
      return rows.getBoolean(column);
    } catch (SQLException exception) {
      throw new IllegalStateException("Database index metadata is unavailable", exception);
    }
  }

  private static boolean isConcurrencyFailure(SQLException exception) {
    for (SQLException current = exception; current != null; current = current.getNextException()) {
      if (current.getErrorCode() == 1062
          || current.getErrorCode() == 1205
          || current.getErrorCode() == 1213
          || current.getErrorCode() == 3572
          || "40001".equals(current.getSQLState())
          || "41000".equals(current.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // The fixed result must not expose transaction or credential details.
    }
  }

  private static void closeQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException ignored) {
      // A close failure after a confirmed commit must not change the fixed result.
    }
  }

  private static String jsonEscape(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '"' || character == '\\') {
        escaped.append('\\');
      }
      escaped.append(character);
    }
    return escaped.toString();
  }

  @FunctionalInterface
  interface ConnectionFactory {
    Connection open(DatabaseConfig config) throws SQLException;
  }

  @FunctionalInterface
  interface DatabaseValidator {
    void validate(Connection connection, DatabaseConfig config) throws SQLException;
  }

  static final class DatabaseConfig {
    private static final Set<String> SSL_MODES = Set.of(
        "DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY");

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int port;
    private final String database;

    private DatabaseConfig(
        String jdbcUrl,
        String username,
        String password,
        int port,
        String database
    ) {
      this.jdbcUrl = jdbcUrl;
      this.username = username;
      this.password = password;
      this.port = port;
      this.database = database;
    }

    static DatabaseConfig from(Map<String, String> environment) {
      String appEnvironment = required(environment, "APP_ENV").toUpperCase(Locale.ROOT);
      String host = required(environment, "MYSQL_HOST");
      String database = required(environment, "MYSQL_DATABASE");
      String username = required(environment, "MYSQL_USERNAME");
      String password = requiredSecret(environment, "MYSQL_PASSWORD");
      String sslMode = environment.getOrDefault("MYSQL_SSL_MODE", "DISABLED")
          .trim().toUpperCase(Locale.ROOT);
      if (!host.matches("[A-Za-z0-9.-]+")
          || !database.matches("[A-Za-z0-9_]+")
          || !SSL_MODES.contains(sslMode)
          || "root".equalsIgnoreCase(username.trim())) {
        throw new IllegalArgumentException("Unsafe database target");
      }
      int port;
      try {
        port = Integer.parseInt(required(environment, "MYSQL_PORT"));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("Invalid database port", exception);
      }
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("Invalid database port");
      }
      String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
          + "?createDatabaseIfNotExist=false&useUnicode=true&characterEncoding=UTF-8"
          + "&serverTimezone=Asia/Shanghai&sslMode=" + sslMode
          + "&allowPublicKeyRetrieval=false&connectTimeout=5000&socketTimeout=30000";
      DatabaseEnvironmentGuard.ConnectionTarget target =
          DatabaseEnvironmentGuard.validateConnectionTarget(appEnvironment, jdbcUrl, username);
      return new DatabaseConfig(
          jdbcUrl, username, password, target.port(), target.database());
    }

    String jdbcUrl() {
      return jdbcUrl;
    }

    String username() {
      return username;
    }

    String password() {
      return password;
    }

    int port() {
      return port;
    }

    String database() {
      return database;
    }

    @Override
    public String toString() {
      return "DatabaseConfig[redacted]";
    }
  }

  private record BootstrapInput(
      long tenantId,
      String tenantNameConfirm,
      String username,
      String displayName,
      String operatorName,
      String auditReason
  ) {
    private static final String TENANT_ID = "APP_BOOTSTRAP_ADMIN_TENANT_ID";
    private static final String TENANT_NAME = "APP_BOOTSTRAP_ADMIN_TENANT_NAME_CONFIRM";
    private static final String USERNAME = "APP_BOOTSTRAP_ADMIN_USERNAME";
    private static final String DISPLAY_NAME = "APP_BOOTSTRAP_ADMIN_DISPLAY_NAME";
    private static final String OPERATOR = "APP_BOOTSTRAP_ADMIN_OPERATOR";
    private static final String TICKET = "APP_BOOTSTRAP_ADMIN_TICKET";
    private static final String REASON = "APP_BOOTSTRAP_ADMIN_REASON";

    static BootstrapInput from(Map<String, String> environment) {
      long tenantId;
      try {
        tenantId = Long.parseLong(required(environment, TENANT_ID));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("Invalid tenant id", exception);
      }
      if (tenantId < 1) {
        throw new IllegalArgumentException("Invalid tenant id");
      }
      String tenantName = environment.get(TENANT_NAME);
      if (tenantName == null || tenantName.isBlank() || hasControlCharacter(tenantName)) {
        throw new IllegalArgumentException("Invalid tenant confirmation");
      }
      String username = required(environment, USERNAME).toLowerCase(Locale.ROOT);
      String displayName = required(environment, DISPLAY_NAME);
      String operator = required(environment, OPERATOR);
      String ticket = required(environment, TICKET);
      String reason = required(environment, REASON);
      String auditReason = ticket + " | " + reason;
      if (!username.matches("[a-z0-9_.-]{3,40}")
          || displayName.length() > 120
          || operator.length() > 120
          || auditReason.length() > 255
          || hasControlCharacter(displayName)
          || hasControlCharacter(operator)
          || hasControlCharacter(ticket)
          || hasControlCharacter(reason)) {
        throw new IllegalArgumentException("Invalid bootstrap input");
      }
      return new BootstrapInput(
          tenantId, tenantName, username, displayName, operator, auditReason);
    }
  }

  private static String required(Map<String, String> environment, String name) {
    String value = environment == null ? null : environment.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required input");
    }
    return value.trim();
  }

  private static String requiredSecret(Map<String, String> environment, String name) {
    String value = environment == null ? null : environment.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required secret");
    }
    return value;
  }

  private static boolean hasControlCharacter(String value) {
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) {
        return true;
      }
    }
    return false;
  }

  private record Tenant(String name, String status) {
  }

  static record IndexMetadata(boolean unique, List<String> columns) {
  }

  private record MutableIndex(boolean unique, List<String> columns) {
    private MutableIndex(boolean unique) {
      this(unique, new ArrayList<>());
    }
  }
}
