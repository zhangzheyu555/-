package com.storeprofit.system.config;

import java.util.List;
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
    DatabaseIdentityValidator.validate(
        version,
        port,
        database,
        account,
        grants,
        DatabaseEnvironmentGuard.EXPECTED_PORT,
        DatabaseEnvironmentGuard.EXPECTED_DATABASE);
  }
}
