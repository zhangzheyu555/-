package com.storeprofit.system.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseRuntimeIdentityGuardTest {
  private static final List<String> SCOPED_GRANTS = List.of(
      "GRANT USAGE ON *.* TO `app_user`@`127.0.0.1`",
      "GRANT ALL PRIVILEGES ON `store\\_profit\\_mysql8`.* TO `app_user`@`127.0.0.1`");

  @Test
  void acceptsApprovedRuntimeAndScopedLocalAccount() {
    DatabaseRuntimeIdentityGuard.validateIdentity(
        "8.0.46", 3307, "store_profit_mysql8", "app_user@127.0.0.1", SCOPED_GRANTS);
  }

  @Test
  void rejectsWrongVersionBeforeFlywayMigration() {
    assertThatThrownBy(() -> DatabaseRuntimeIdentityGuard.validateIdentity(
        "5.5.62", 3307, "store_profit_mysql8", "app_user@127.0.0.1", SCOPED_GRANTS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("version");
  }

  @Test
  void rejectsWrongRuntimePort() {
    assertThatThrownBy(() -> DatabaseRuntimeIdentityGuard.validateIdentity(
        "8.0.46", 3306, "store_profit_mysql8", "app_user@127.0.0.1", SCOPED_GRANTS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("3307");
  }

  @Test
  void rejectsUnapprovedRuntimeDatabase() {
    assertThatThrownBy(() -> DatabaseRuntimeIdentityGuard.validateIdentity(
        "8.0.46", 3307, "store_profit_mysql8_final", "app_user@127.0.0.1", SCOPED_GRANTS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approved");
  }

  @Test
  void rejectsRootAndWildcardAccounts() {
    assertThatThrownBy(() -> DatabaseRuntimeIdentityGuard.validateIdentity(
        "8.0.46", 3307, "store_profit_mysql8", "root@localhost", SCOPED_GRANTS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("root");
    assertThatThrownBy(() -> DatabaseRuntimeIdentityGuard.validateIdentity(
        "8.0.46", 3307, "store_profit_mysql8", "app_user@%", SCOPED_GRANTS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("local host");
  }

  @Test
  void rejectsGlobalCrossDatabaseAndGrantOptionPrivileges() {
    assertThatThrownBy(() -> DatabaseRuntimeIdentityGuard.validateIdentity(
        "8.0.46", 3307, "store_profit_mysql8", "app_user@127.0.0.1",
        List.of("GRANT SELECT ON *.* TO `app_user`@`127.0.0.1`")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("global privileges");
    assertThatThrownBy(() -> DatabaseRuntimeIdentityGuard.validateIdentity(
        "8.0.46", 3307, "store_profit_mysql8", "app_user@127.0.0.1",
        List.of("GRANT SELECT ON `other\\_database`.* TO `app_user`@`127.0.0.1`")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside");
    assertThatThrownBy(() -> DatabaseRuntimeIdentityGuard.validateIdentity(
        "8.0.46", 3307, "store_profit_mysql8", "app_user@127.0.0.1",
        List.of("GRANT ALL ON `store_profit_mysql8`.* TO `app_user`@`127.0.0.1` WITH GRANT OPTION")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GRANT OPTION");
  }

  @Test
  void rejectsUnescapedDatabaseWildcardCharacters() {
    assertThatThrownBy(() -> DatabaseRuntimeIdentityGuard.validateIdentity(
        "8.0.46", 3307, "store_profit_mysql8", "app_user@127.0.0.1",
        List.of("GRANT ALL ON `store_profit_mysql8`.* TO `app_user`@`127.0.0.1`")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("wildcard");
  }
}
