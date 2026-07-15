package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FinanceProfitImportPermissionMigrationTest {
  @Test
  void v50AddsFinanceOnlyMonthlyImportPermissionAndInvalidatesAffectedSessions() {
    DataSource dataSource = dataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("48")
        .baselineOnMigrate(false)
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertUser(jdbc, 9501L, "finance-v50", "FINANCE");
    insertUser(jdbc, 9502L, "boss-v50", "BOSS");
    insertUser(jdbc, 9503L, "manager-v50", "STORE_MANAGER");
    insertToken(jdbc, "finance-v50-token", 9501L);
    insertToken(jdbc, "boss-v50-token", 9502L);
    insertToken(jdbc, "manager-v50-token", 9503L);

    var result = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("50")
        .baselineOnMigrate(false)
        .load()
        .migrate();

    assertThat(result.success).isTrue();
    assertThat(jdbc.queryForObject("""
        select count(*) from permission_catalog where permission_code = 'finance.profit.import'
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select permission_name from permission_catalog where permission_code = 'finance.profit.import'
        """, String.class)).isEqualTo("导入月度经营数据");
    assertThat(jdbc.queryForList("""
        select distinct role_code from role_permission
        where permission_code = 'finance.profit.import'
        order by role_code
        """, String.class)).containsExactly("FINANCE");
    assertThat(permissionVersion(jdbc, 9501L)).isEqualTo(4L);
    assertThat(permissionVersion(jdbc, 9502L)).isEqualTo(4L);
    assertThat(permissionVersion(jdbc, 9503L)).isEqualTo(3L);
    assertThat(tokenExists(jdbc, "finance-v50-token")).isFalse();
    assertThat(tokenExists(jdbc, "boss-v50-token")).isFalse();
    assertThat(tokenExists(jdbc, "manager-v50-token")).isTrue();
  }

  private void insertUser(JdbcTemplate jdbc, long id, String username, String role) {
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, enabled,
          permission_version, created_at
        ) values (?, 1, ?, 'hash', ?, ?, 1, 3, current_timestamp)
        """, id, username, role, role);
  }

  private void insertToken(JdbcTemplate jdbc, String token, long userId) {
    jdbc.update("""
        insert into auth_token(token, tenant_id, user_id, permission_version, expires_at, created_at)
        values (?, 1, ?, 3, timestamp '2099-01-01 00:00:00', current_timestamp)
        """, token, userId);
  }

  private long permissionVersion(JdbcTemplate jdbc, long userId) {
    return jdbc.queryForObject(
        "select permission_version from auth_user where id = ?", Long.class, userId);
  }

  private boolean tokenExists(JdbcTemplate jdbc, String token) {
    return jdbc.queryForObject(
        "select count(*) from auth_token where token = ?", Integer.class, token) == 1;
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:finance-profit-import-v50"
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
