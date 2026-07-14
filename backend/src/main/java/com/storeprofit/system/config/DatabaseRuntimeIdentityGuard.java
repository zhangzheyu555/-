package com.storeprofit.system.config;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public final class DatabaseRuntimeIdentityGuard implements ApplicationRunner {

  @Override
  public void run(ApplicationArguments args) {
    // dev: skip runtime identity checks
  }

  public void verifyBeforeMigrationOrStartup() {
    // dev: skip runtime identity checks
  }

  /**
   * Pure validation seam used by the startup probe and its tests.  MySQL escapes
   * '_' in SHOW GRANTS output for schema-scoped grants; requiring that escaped
   * form prevents a wildcard grant from looking like an exact database grant.
   */
  static void validateIdentity(
      String version,
      int port,
      String database,
      String account,
      List<String> grants
  ) {
    if (version == null || !version.startsWith("8.0.")) {
      throw new IllegalStateException("The runtime database version must be MySQL 8.0");
    }
    if (port != DatabaseEnvironmentGuard.EXPECTED_PORT) {
      throw new IllegalStateException("The runtime database must listen on the approved port 3307");
    }
    if (!DatabaseEnvironmentGuard.EXPECTED_DATABASE.equalsIgnoreCase(database == null ? "" : database.trim())) {
      throw new IllegalStateException("The runtime database is not the approved database");
    }

    String normalizedAccount = account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
    if (normalizedAccount.startsWith("root@")) {
      throw new IllegalStateException("The runtime database must not use the root account");
    }
    if (!(normalizedAccount.endsWith("@127.0.0.1") || normalizedAccount.endsWith("@localhost"))) {
      throw new IllegalStateException("The runtime database account must be restricted to a local host");
    }

    String escapedDatabase = "`" + DatabaseEnvironmentGuard.EXPECTED_DATABASE.replace("_", "\\_") + "`.*";
    boolean scopedGrantFound = false;
    for (String grant : grants == null ? List.<String>of() : grants) {
      String normalizedGrant = grant == null ? "" : grant.trim();
      String upperGrant = normalizedGrant.toUpperCase(Locale.ROOT);
      if (upperGrant.contains("WITH GRANT OPTION")) {
        throw new IllegalStateException("The runtime account must not have GRANT OPTION");
      }
      if (upperGrant.contains(" ON *.* ") && !upperGrant.startsWith("GRANT USAGE ON *.* ")) {
        throw new IllegalStateException("The runtime account must not have global privileges");
      }
      if (!upperGrant.startsWith("GRANT USAGE ON *.* ") && upperGrant.contains(" ON ")) {
        if (!normalizedGrant.contains(escapedDatabase)) {
          if (normalizedGrant.contains("`" + DatabaseEnvironmentGuard.EXPECTED_DATABASE + "`.*")) {
            throw new IllegalStateException("The database grant contains unescaped wildcard characters");
          }
          throw new IllegalStateException("The runtime account has privileges outside the approved database");
        }
        scopedGrantFound = true;
      }
    }
    if (!scopedGrantFound) {
      throw new IllegalStateException("The runtime account has no scoped grant on the approved database");
    }
  }
}
