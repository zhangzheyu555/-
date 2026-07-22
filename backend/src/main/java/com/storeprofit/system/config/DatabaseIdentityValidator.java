package com.storeprofit.system.config;

import java.util.List;
import java.util.Locale;

/**
 * Pure runtime database identity validation shared by non-Web entry points and
 * the Spring startup guard. This class deliberately has no Spring dependency
 * and performs no database I/O of its own.
 */
public final class DatabaseIdentityValidator {
  private DatabaseIdentityValidator() {
  }

  public static void validate(
      String version,
      int actualPort,
      String actualDatabase,
      String account,
      List<String> grants,
      int expectedPort,
      String expectedDatabase
  ) {
    validate(
        version,
        actualPort,
        actualDatabase,
        account,
        grants,
        expectedPort,
        expectedDatabase,
        null);
  }

  public static void validate(
      String version,
      int actualPort,
      String actualDatabase,
      String account,
      List<String> grants,
      int expectedPort,
      String expectedDatabase,
      String expectedUsername
  ) {
    validate(
        version,
        actualPort,
        actualDatabase,
        account,
        grants,
        expectedPort,
        expectedDatabase,
        expectedUsername,
        false);
  }

  /**
   * Validates the runtime identity for a one-off administrator bootstrap.
   *
   * <p>The Docker-compatible mode is intentionally narrower than ordinary QA
   * validation: callers must separately establish the explicit QA-only
   * environment guard before enabling it. It permits a host-to-container port
   * mapping and MySQL's container account host ({@code %}), while retaining
   * the exact schema, configured username and least-privilege grant checks.
   * It is never used by the application runtime identity guard.</p>
   */
  public static void validate(
      String version,
      int actualPort,
      String actualDatabase,
      String account,
      List<String> grants,
      int expectedPort,
      String expectedDatabase,
      String expectedUsername,
      boolean allowQaDockerBootstrap
  ) {
    if (expectedPort < 1 || expectedPort > 65_535) {
      throw new IllegalStateException("The expected MySQL port is invalid");
    }
    if (actualPort < 1 || actualPort > 65_535) {
      throw new IllegalStateException("The runtime MySQL port is invalid");
    }
    if (expectedDatabase == null || !expectedDatabase.matches("[A-Za-z0-9_]+")) {
      throw new IllegalStateException("The expected MySQL database is invalid");
    }
    if (version == null || !(allowQaDockerBootstrap
        ? version.startsWith("8.")
        : version.startsWith("8.0."))) {
      throw new IllegalStateException(allowQaDockerBootstrap
          ? "The runtime database version must be MySQL 8"
          : "The runtime database version must be MySQL 8.0");
    }
    if (!allowQaDockerBootstrap && actualPort != expectedPort) {
      throw new IllegalStateException(
          "The runtime database must listen on the approved port " + expectedPort);
    }
    if (!expectedDatabase.equals(actualDatabase)) {
      throw new IllegalStateException("The runtime database is not the approved database");
    }

    String accountValue = account == null ? "" : account.trim();
    int hostSeparator = accountValue.lastIndexOf('@');
    String accountUsername = hostSeparator < 0 ? "" : accountValue.substring(0, hostSeparator);
    String normalizedAccount = accountValue.toLowerCase(Locale.ROOT);
    if (normalizedAccount.startsWith("root@")) {
      throw new IllegalStateException("The runtime database must not use the root account");
    }
    if (expectedUsername != null && !expectedUsername.equals(accountUsername)) {
      throw new IllegalStateException("The runtime database account does not match MYSQL_USERNAME");
    }
    if (!isAllowedAccountHost(normalizedAccount, allowQaDockerBootstrap)) {
      throw new IllegalStateException(allowQaDockerBootstrap
          ? "The QA Docker bootstrap account must use a local or container host"
          : "The runtime database account must be restricted to a local host");
    }

    String escapedDatabase = "`" + expectedDatabase.replace("_", "\\_") + "`.*";
    String unescapedDatabase = "`" + expectedDatabase + "`.*";
    boolean scopedGrantFound = false;
    for (String grant : grants == null ? List.<String>of() : grants) {
      String normalizedGrant = grant == null ? "" : grant.trim();
      String upperGrant = normalizedGrant.toUpperCase(Locale.ROOT);
      if (upperGrant.contains("WITH GRANT OPTION")) {
        throw new IllegalStateException("The runtime account must not have GRANT OPTION");
      }
      if (upperGrant.startsWith("GRANT USAGE ON *.* ")) {
        continue;
      }
      int onIndex = upperGrant.indexOf(" ON ");
      if (onIndex < 0) {
        throw new IllegalStateException("The runtime account has an unsupported grant");
      }
      int resourceStart = onIndex + 4;
      int resourceEnd = normalizedGrant.indexOf(' ', resourceStart);
      String grantedResource = resourceEnd < 0
          ? normalizedGrant.substring(resourceStart)
          : normalizedGrant.substring(resourceStart, resourceEnd);
      if ("*.*".equals(grantedResource)) {
        throw new IllegalStateException("The runtime account must not have global privileges");
      }
      if (!escapedDatabase.equals(grantedResource)) {
        if (!escapedDatabase.equals(unescapedDatabase) && unescapedDatabase.equals(grantedResource)) {
          throw new IllegalStateException("The database grant contains unescaped wildcard characters");
        }
        throw new IllegalStateException("The runtime account has privileges outside the approved database");
      }
      scopedGrantFound = true;
    }
    if (!scopedGrantFound) {
      throw new IllegalStateException("The runtime account has no scoped grant on the approved database");
    }
  }

  private static boolean isAllowedAccountHost(String normalizedAccount, boolean allowQaDockerBootstrap) {
    if (normalizedAccount.endsWith("@127.0.0.1") || normalizedAccount.endsWith("@localhost")) {
      return true;
    }
    // The official MySQL Docker image commonly provisions its non-root
    // application user as user@%. This is accepted only by the explicit,
    // QA-only bootstrap policy above; all grants still have to be exactly
    // scoped to the configured QA schema.
    return allowQaDockerBootstrap && normalizedAccount.endsWith("@%");
  }
}
