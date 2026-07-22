package com.storeprofit.system.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseIdentityValidatorTest {
  private static final String DATABASE = "ai_profit_qa_r102";
  private static final int PORT = 3312;
  private static final List<String> SCOPED_GRANTS = List.of(
      "GRANT USAGE ON *.* TO `bootstrap_user`@`127.0.0.1`",
      "GRANT SELECT, INSERT ON `ai\\_profit\\_qa\\_r102`.* TO `bootstrap_user`@`127.0.0.1`");

  @Test
  void acceptsParameterizedQaTargetAndExactScopedGrant() {
    DatabaseIdentityValidator.validate(
        "8.0.46", PORT, DATABASE, "bootstrap_user@127.0.0.1", SCOPED_GRANTS, PORT, DATABASE);
  }

  @Test
  void rejectsRuntimeTargetThatDiffersFromExpectedTarget() {
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.0.46", PORT + 1, DATABASE, "bootstrap_user@127.0.0.1", SCOPED_GRANTS, PORT, DATABASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("3312");
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.0.46", PORT, DATABASE.toUpperCase(), "bootstrap_user@127.0.0.1", SCOPED_GRANTS, PORT, DATABASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approved database");
  }

  @Test
  void acceptsExplicitQaDockerBootstrapWithMappedPortAndContainerAccount() {
    DatabaseIdentityValidator.validate(
        "8.4.3",
        3306,
        DATABASE,
        "bootstrap_user@%",
        List.of(
            "GRANT USAGE ON *.* TO `bootstrap_user`@`%`",
            "GRANT SELECT, INSERT ON `ai\\_profit\\_qa\\_r102`.* TO `bootstrap_user`@`%`"),
        PORT,
        DATABASE,
        "bootstrap_user",
        true);
  }

  @Test
  void qaDockerBootstrapStillRejectsNonMysql8AndUnsafeAccountOrGrant() {
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "5.7.44", 3306, DATABASE, "bootstrap_user@%", SCOPED_GRANTS,
        PORT, DATABASE, "bootstrap_user", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MySQL 8");
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.4.3", 3306, DATABASE, "root@%", SCOPED_GRANTS,
        PORT, DATABASE, "root", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("root");
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.4.3", 3306, DATABASE, "bootstrap_user@%", SCOPED_GRANTS,
        PORT, DATABASE, "other_user", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MYSQL_USERNAME");
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.4.3", 3306, "other_qa", "bootstrap_user@%", SCOPED_GRANTS,
        PORT, DATABASE, "bootstrap_user", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approved database");
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.4.3", 3306, DATABASE, "bootstrap_user@remote.example", SCOPED_GRANTS,
        PORT, DATABASE, "bootstrap_user", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("local or container host");
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.4.3",
        3306,
        DATABASE,
        "bootstrap_user@%",
        List.of("GRANT ALL ON *.* TO `bootstrap_user`@`%`"),
        PORT,
        DATABASE,
        "bootstrap_user",
        true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("global privileges");
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.4.3",
        3306,
        DATABASE,
        "bootstrap_user@%",
        List.of("GRANT SELECT ON `ai\\_profit\\_qa\\_r102`.* TO `bootstrap_user`@`%` WITH GRANT OPTION"),
        PORT,
        DATABASE,
        "bootstrap_user",
        true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GRANT OPTION");
  }

  @Test
  void rejectsAccountThatDoesNotMatchConfiguredUsername() {
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.0.46",
        PORT,
        DATABASE,
        "proxy_user@127.0.0.1",
        SCOPED_GRANTS,
        PORT,
        DATABASE,
        "bootstrap_user"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MYSQL_USERNAME");
  }

  @Test
  void rejectsRootAndNonLocalAccountsForEveryEnvironment() {
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.0.46", PORT, DATABASE, "root@localhost", SCOPED_GRANTS, PORT, DATABASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("root");
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.0.46", PORT, DATABASE, "bootstrap_user@%", SCOPED_GRANTS, PORT, DATABASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("local host");
  }

  @Test
  void rejectsGlobalCrossDatabaseGrantOptionAndRoleGrants() {
    assertRejectedGrant(
        "GRANT SELECT ON *.* TO `bootstrap_user`@`127.0.0.1`", "global privileges");
    assertRejectedGrant(
        "GRANT SELECT ON `other\\_qa`.* TO `bootstrap_user`@`127.0.0.1`", "outside");
    assertRejectedGrant(
        "GRANT SELECT ON `ai\\_profit\\_qa\\_r102`.* TO `bootstrap_user`@`127.0.0.1` WITH GRANT OPTION",
        "GRANT OPTION");
    assertRejectedGrant(
        "GRANT `database_admin`@`localhost` TO `bootstrap_user`@`127.0.0.1`", "unsupported grant");
  }

  @Test
  void rejectsUnescapedUnderscoresAndRequiresScopedGrant() {
    assertRejectedGrant(
        "GRANT SELECT ON `ai_profit_qa_r102`.* TO `bootstrap_user`@`127.0.0.1`", "wildcard");
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.0.46",
        PORT,
        DATABASE,
        "bootstrap_user@127.0.0.1",
        List.of("GRANT USAGE ON *.* TO `bootstrap_user`@`127.0.0.1`"),
        PORT,
        DATABASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no scoped grant");
  }

  private void assertRejectedGrant(String grant, String message) {
    assertThatThrownBy(() -> DatabaseIdentityValidator.validate(
        "8.0.46",
        PORT,
        DATABASE,
        "bootstrap_user@127.0.0.1",
        List.of("GRANT USAGE ON *.* TO `bootstrap_user`@`127.0.0.1`", grant),
        PORT,
        DATABASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(message);
  }
}
