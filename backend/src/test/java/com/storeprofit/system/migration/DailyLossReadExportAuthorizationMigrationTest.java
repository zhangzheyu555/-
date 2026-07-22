package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DailyLossReadExportAuthorizationMigrationTest {
  @Test
  void v75RestrictsDailyLossQueryAndExcelTemplatesToFinanceAndInvalidatesAffectedSessions() {
    JdbcDataSource dataSource = dataSource("v75");
    migrate(dataSource, "74");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertUser(jdbc, 9701L, "boss-v75", "BOSS");
    insertUser(jdbc, 9702L, "finance-v75", "FINANCE");
    insertUser(jdbc, 9703L, "manager-v75", "STORE_MANAGER");
    insertUser(jdbc, 9704L, "supervisor-v75", "SUPERVISOR");
    insertUser(jdbc, 9705L, "warehouse-v75", "WAREHOUSE");
    insertUser(jdbc, 9706L, "employee-v75", "EMPLOYEE");
    for (long id = 9701L; id <= 9706L; id++) {
      insertToken(jdbc, id);
    }
    jdbc.update("""
        insert into permission_catalog(permission_code, module_code, permission_name, description, risk_level, enabled, sort_order)
        values ('daily_loss.export', 'DAILY_LOSS', '导出本月报损 Excel', '旧模板', 'HIGH', 1, 618)
        """);
    for (String role : List.of("STORE_MANAGER", "SUPERVISOR", "WAREHOUSE", "EMPLOYEE")) {
      jdbc.update("""
          insert into role_permission(tenant_id, role_code, permission_code, created_at)
          values (1, ?, 'daily_loss.export', current_timestamp)
          """, role);
    }

    migrate(dataSource, "75");

    assertThat(jdbc.queryForList("""
        select role_code || ':' || permission_code
        from role_permission
        where permission_code in ('daily_loss.read', 'daily_loss.export')
        order by role_code, permission_code
        """, String.class)).containsExactly(
            "FINANCE:daily_loss.export", "FINANCE:daily_loss.read"
        );
    assertThat(permissionVersion(jdbc, 9701L)).isEqualTo(3L);
    assertThat(permissionVersion(jdbc, 9702L)).isEqualTo(3L);
    for (long id = 9703L; id <= 9706L; id++) {
      assertThat(permissionVersion(jdbc, id)).isEqualTo(4L);
      assertThat(tokenExists(jdbc, id)).isFalse();
    }
    assertThat(tokenExists(jdbc, 9701L)).isTrue();
    assertThat(tokenExists(jdbc, 9702L)).isTrue();
  }

  @Test
  void v81MovesDailyLossReadReviewAndExportFromFinanceToSupervisor() {
    JdbcDataSource dataSource = dataSource("v81");
    migrate(dataSource, "80");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertUser(jdbc, 9801L, "finance-v81", "FINANCE");
    insertUser(jdbc, 9802L, "supervisor-v81", "SUPERVISOR");
    insertToken(jdbc, 9801L);
    insertToken(jdbc, 9802L);
    jdbc.update("""
        insert into user_permission_override(
          tenant_id, user_id, permission_code, effect, created_at
        ) values (1, 9801, 'daily_loss.review', 'ALLOW', current_timestamp)
        """);

    migrate(dataSource, "81");

    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'FINANCE' and permission_code like 'daily_loss.%'
        """, String.class)).isEmpty();
    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'SUPERVISOR' and permission_code like 'daily_loss.%'
        order by permission_code
        """, String.class)).containsExactly(
            "daily_loss.export", "daily_loss.read", "daily_loss.review");
    assertThat(jdbc.queryForObject("""
        select count(*) from user_permission_override
        where user_id = 9801 and permission_code like 'daily_loss.%' and effect = 'ALLOW'
        """, Integer.class)).isZero();
    assertThat(permissionVersion(jdbc, 9801L)).isEqualTo(4L);
    assertThat(permissionVersion(jdbc, 9802L)).isEqualTo(4L);
    assertThat(tokenExists(jdbc, 9801L)).isFalse();
    assertThat(tokenExists(jdbc, 9802L)).isFalse();
  }

  @Test
  void v82GrantsStoreManagerReadWhileKeepingSubmissionAndSupervisorReviewBoundaries() {
    JdbcDataSource dataSource = dataSource("v82");
    migrate(dataSource, "81");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertUser(jdbc, 9901L, "manager-v82", "STORE_MANAGER");
    insertUser(jdbc, 9902L, "supervisor-v82", "SUPERVISOR");
    insertUser(jdbc, 9903L, "finance-v82", "FINANCE");
    insertToken(jdbc, 9901L);
    insertToken(jdbc, 9902L);
    insertToken(jdbc, 9903L);

    migrate(dataSource, "82");

    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'STORE_MANAGER' and permission_code like 'daily_loss.%'
        order by permission_code
        """, String.class)).containsExactly(
            "daily_loss.create", "daily_loss.read");
    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'SUPERVISOR' and permission_code like 'daily_loss.%'
        order by permission_code
        """, String.class)).containsExactly(
            "daily_loss.export", "daily_loss.read", "daily_loss.review");
    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'FINANCE' and permission_code like 'daily_loss.%'
        """, String.class)).isEmpty();
    assertThat(permissionVersion(jdbc, 9901L)).isEqualTo(4L);
    assertThat(permissionVersion(jdbc, 9902L)).isEqualTo(3L);
    assertThat(permissionVersion(jdbc, 9903L)).isEqualTo(3L);
    assertThat(tokenExists(jdbc, 9901L)).isFalse();
    assertThat(tokenExists(jdbc, 9902L)).isTrue();
    assertThat(tokenExists(jdbc, 9903L)).isTrue();
  }

  @Test
  void v83RemovesSupervisorWarehouseEmployeeAndEmployeeAssistantAccessButKeepsDailyLoss() {
    JdbcDataSource dataSource = dataSource("v83");
    migrate(dataSource, "82");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertUser(jdbc, 9951L, "supervisor-v83", "SUPERVISOR");
    insertUser(jdbc, 9952L, "finance-v83", "FINANCE");
    insertToken(jdbc, 9951L);
    insertToken(jdbc, 9952L);
    for (String permissionCode : List.of(
        "warehouse.read",
        "employee.read",
        "employee_assistant.use",
        "employee_assistant.handoff_manage")) {
      jdbc.update("""
          insert into user_permission_override(
            tenant_id, user_id, permission_code, effect, created_at
          ) values (1, 9951, ?, 'ALLOW', current_timestamp)
          """, permissionCode);
    }
    jdbc.update("""
        insert into user_data_scope(
          tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at
        ) values (1, 9951, 'WAREHOUSE', 'WAREHOUSE_LIST', '[1]', current_timestamp)
        """);

    migrate(dataSource, "83");

    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'SUPERVISOR'
          and (permission_code like 'warehouse.%'
            or permission_code in (
              'employee.read', 'employee.manage',
              'employee_assistant.use', 'employee_assistant.knowledge_manage',
              'employee_assistant.handoff_manage'))
        """, String.class)).isEmpty();
    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'SUPERVISOR' and permission_code like 'daily_loss.%'
        order by permission_code
        """, String.class)).containsExactly(
            "daily_loss.export", "daily_loss.read", "daily_loss.review");
    assertThat(jdbc.queryForObject("""
        select count(*) from user_permission_override where user_id = 9951
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from user_data_scope
        where user_id = 9951 and domain_code = 'WAREHOUSE'
        """, Integer.class)).isZero();
    assertThat(permissionVersion(jdbc, 9951L)).isEqualTo(4L);
    assertThat(tokenExists(jdbc, 9951L)).isFalse();
    assertThat(permissionVersion(jdbc, 9952L)).isEqualTo(3L);
    assertThat(tokenExists(jdbc, 9952L)).isTrue();
  }

  @Test
  void v84RemovesSupervisorOperatingAssistantAccessButKeepsDailyLoss() {
    JdbcDataSource dataSource = dataSource("v84");
    migrate(dataSource, "83");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertUser(jdbc, 9961L, "supervisor-v84", "SUPERVISOR");
    insertUser(jdbc, 9962L, "manager-v84", "STORE_MANAGER");
    insertToken(jdbc, 9961L);
    insertToken(jdbc, 9962L);
    jdbc.update("""
        insert into user_permission_override(
          tenant_id, user_id, permission_code, effect, created_at
        ) values (1, 9961, 'assistant.use', 'ALLOW', current_timestamp)
        """);

    assertThat(jdbc.queryForObject("""
        select count(*) from role_permission
        where role_code = 'SUPERVISOR' and permission_code = 'assistant.use'
        """, Integer.class)).isOne();

    migrate(dataSource, "84");

    assertThat(jdbc.queryForObject("""
        select count(*) from role_permission
        where role_code = 'SUPERVISOR' and permission_code = 'assistant.use'
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from user_permission_override
        where user_id = 9961 and permission_code = 'assistant.use' and effect = 'ALLOW'
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'SUPERVISOR' and permission_code like 'daily_loss.%'
        order by permission_code
        """, String.class)).containsExactly(
            "daily_loss.export", "daily_loss.read", "daily_loss.review");
    assertThat(jdbc.queryForObject("""
        select count(*) from role_permission
        where role_code = 'STORE_MANAGER' and permission_code = 'assistant.use'
        """, Integer.class)).isOne();
    assertThat(permissionVersion(jdbc, 9961L)).isEqualTo(4L);
    assertThat(tokenExists(jdbc, 9961L)).isFalse();
    assertThat(permissionVersion(jdbc, 9962L)).isEqualTo(3L);
    assertThat(tokenExists(jdbc, 9962L)).isTrue();
  }

  private void migrate(JdbcDataSource dataSource, String target) {
    var result = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(target)
        .baselineOnMigrate(false)
        .load()
        .migrate();
    assertThat(result.success).isTrue();
  }

  private void insertUser(JdbcTemplate jdbc, long id, String username, String role) {
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, enabled, permission_version, created_at
        ) values (?, 1, ?, 'hash', ?, ?, 1, 3, current_timestamp)
        """, id, username, role, role);
  }

  private void insertToken(JdbcTemplate jdbc, long userId) {
    String hash = String.format("%064x", userId);
    jdbc.update("""
        insert into auth_token(token_hash, tenant_id, user_id, permission_version, expires_at, created_at)
        values (?, 1, ?, 3, timestamp '2099-01-01 00:00:00', current_timestamp)
        """, hash, userId);
  }

  private long permissionVersion(JdbcTemplate jdbc, long userId) {
    return jdbc.queryForObject("select permission_version from auth_user where id = ?", Long.class, userId);
  }

  private boolean tokenExists(JdbcTemplate jdbc, long userId) {
    return jdbc.queryForObject("select count(*) from auth_token where user_id = ?", Integer.class, userId) == 1;
  }

  private JdbcDataSource dataSource(String testCase) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:daily-loss-read-export-" + testCase
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
