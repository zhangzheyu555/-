package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class PermissionArchitectureMigrationTest {
  @Test
  void upgradesV36RolesPermissionsScopesAndOpenTodosWithoutExpandingLegacyScope() {
    DataSource dataSource = dataSource("upgrade");
    migrateTo(dataSource, "36");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertUpgradeFixtures(jdbc);

    var result = migrateTo(dataSource, "37");

    assertThat(result.success).isTrue();
    assertThat(result.targetSchemaVersion).isEqualTo("37");
    assertThat(jdbc.queryForObject("select count(*) from permission_catalog", Integer.class)).isEqualTo(42);
    assertThat(jdbc.queryForList(
        "select permission_code from permission_catalog order by permission_code",
        String.class
    )).contains(
        "system.user.manage",
        "system.audit.write",
        "finance.profit.delete",
        "operations.dashboard.read",
        "attachment.read",
        "employee.manage",
        "inventory.read",
        "inventory.manage",
        "inventory.review",
        "todo.transition",
        "warehouse.central.manage"
    );

    assertCanonicalRoleTemplates(jdbc);
    assertLegacyRoleMigration(jdbc);
    assertScopeMigration(jdbc);
    assertOpenWorkMigration(jdbc);
    assertPermissionVersionAndTokenRevocation(jdbc);
    assertDatabaseConstraints(jdbc);
  }

  @Test
  void restoresPermissionDataRemovedByAPostV37LegacyImport() {
    DataSource dataSource = dataSource("repair");
    migrateTo(dataSource, "38");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertUpgradeFixtures(jdbc);

    jdbc.update("delete from user_permission_override");
    jdbc.update("delete from user_data_scope");
    jdbc.update("delete from role_permission");
    jdbc.update("delete from permission_catalog");
    jdbc.update("""
        insert into permission_catalog(
          permission_code, module_code, permission_name, risk_level, enabled, sort_order
        ) values ('store.read', 'STORE', '查看门店', 'LOW', 1, 100)
        """);
    jdbc.update("""
        insert into user_permission_override(
          tenant_id, user_id, permission_code, effect, created_at
        ) values (1, 103, 'store.read', 'DENY', current_timestamp)
        """);

    var result = migrateTo(dataSource, "39");

    assertThat(result.success).isTrue();
    assertThat(result.targetSchemaVersion).isEqualTo("39");
    assertThat(jdbc.queryForObject("select count(*) from permission_catalog", Integer.class))
        .isEqualTo(42);
    assertThat(jdbc.queryForObject(
        "select count(*) from role_permission where role_code = 'FINANCE'", Integer.class))
        .isEqualTo(18);
    assertThat(jdbc.queryForObject(
        "select count(*) from role_permission where role_code = 'STORE_MANAGER'", Integer.class))
        .isEqualTo(22);
    assertThat(jdbc.queryForObject("""
        select count(*) from role_permission
        where role_code = 'STORE_MANAGER' and permission_code = 'store.read'
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select effect from user_permission_override
        where tenant_id = 1 and user_id = 103 and permission_code = 'store.read'
        """, String.class)).isEqualTo("DENY");
    assertThat(jdbc.queryForObject(
        "select count(*) from user_data_scope where user_id = 105", Integer.class))
        .isEqualTo(4);
    assertThat(jdbc.queryForObject(
        "select count(*) from user_data_scope where user_id = 103 and scope_type = 'OWN_STORE'",
        Integer.class)).isEqualTo(6);
    assertThat(role(jdbc, 101L)).isEqualTo("OPERATIONS");
    assertThat(jdbc.queryForObject(
        "select min(permission_version) from auth_user", Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("select count(*) from auth_token", Integer.class)).isZero();
  }

  private void assertCanonicalRoleTemplates(JdbcTemplate jdbc) {
    assertThat(jdbc.queryForObject(
        "select count(*) from role_permission where role_code = 'BOSS'",
        Integer.class
    )).isZero();
    assertThat(jdbc.queryForList(
        "select permission_code from role_permission where role_code = 'EMPLOYEE' order by permission_code",
        String.class
    )).containsExactly("exam.learn");
    assertThat(jdbc.queryForList(
        "select permission_code from role_permission where role_code = 'OPERATIONS' order by permission_code",
        String.class
    )).contains(
            "inspection.read",
            "inspection.manage",
            "inventory.read",
            "inventory.manage",
            "inventory.review",
            "operations.dashboard.read"
        )
        .doesNotContain("salary.read", "finance.profit.read", "system.dashboard.read");
    assertThat(jdbc.queryForList(
        "select permission_code from role_permission where role_code = 'FINANCE' order by permission_code",
        String.class
    )).contains("inventory.read")
        .doesNotContain(
        "inventory.review",
        "warehouse.central.read",
        "finance.profit.delete",
        "system.dashboard.read",
        "system.audit.write"
    );
    assertThat(jdbc.queryForObject("""
        select count(*)
        from role_permission
        where role_code in ('ADMIN', 'OWNER', 'OPS', 'SUPERVISOR')
        """, Integer.class)).isZero();
  }

  private void assertLegacyRoleMigration(JdbcTemplate jdbc) {
    assertThat(role(jdbc, 101L)).isEqualTo("OPERATIONS");
    assertThat(role(jdbc, 102L)).isEqualTo("EMPLOYEE");
    assertThat(role(jdbc, 107L)).isEqualTo("BOSS");
    assertThat(role(jdbc, 108L)).isEqualTo("OPERATIONS");
    assertThat(jdbc.queryForList(
        "select password_hash from auth_user where id in (101, 102, 107, 108) order by id",
        String.class
    )).containsExactly("hash-supervisor", "hash-employee", "hash-admin", "hash-ops");

    List<Map<String, Object>> overrides = jdbc.queryForList("""
        select permission_code, effect
        from user_permission_override
        where tenant_id = 1 and user_id = 101
        order by permission_code
        """);
    assertThat(overrides).containsExactly(
        Map.of("permission_code", "inspection.manage", "effect", "ALLOW"),
        Map.of("permission_code", "inspection.read", "effect", "ALLOW")
    );
  }

  private void assertScopeMigration(JdbcTemplate jdbc) {
    assertThat(jdbc.queryForObject(
        "select store_id from auth_user where id = 101", String.class
    )).isEqualTo("rg2");
    assertThat(jdbc.queryForObject("""
        select scope_type from user_data_scope
        where tenant_id = 1 and user_id = 101 and domain_code = 'INSPECTION'
        """, String.class)).isEqualTo("STORE_LIST");
    assertThat(jdbc.queryForObject("""
        select scope_value_json from user_data_scope
        where tenant_id = 1 and user_id = 101 and domain_code = 'INSPECTION'
        """, String.class)).contains("rg2").doesNotContain("all");

    assertThat(jdbc.queryForObject(
        "select store_id from auth_user where id = 103", String.class
    )).isEqualTo("rg1");
    assertThat(jdbc.queryForList("""
        select distinct scope_type from user_data_scope
        where tenant_id = 1 and user_id = 103
        """, String.class)).containsExactly("OWN_STORE");
    assertThat(jdbc.queryForObject("""
        select count(*) from user_data_scope
        where tenant_id = 1 and user_id = 103 and scope_value_json is not null
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from user_store_scope
        where tenant_id = 1 and user_id = 103
        """, Integer.class)).isEqualTo(2);

    assertThat(jdbc.queryForObject("""
        select scope_type from user_data_scope
        where tenant_id = 1 and user_id = 104 and domain_code = 'STORE'
        """, String.class)).isEqualTo("NONE");
    assertThat(jdbc.queryForObject("""
        select scope_type from user_data_scope
        where tenant_id = 1 and user_id = 102 and domain_code = 'EXAM'
        """, String.class)).isEqualTo("SELF");
    assertThat(jdbc.queryForObject("""
        select count(*) from user_data_scope
        where tenant_id = 1 and user_id = 105 and scope_type = 'ALL'
        """, Integer.class)).isEqualTo(4);
    assertThat(jdbc.queryForObject("""
        select count(*) from user_data_scope
        where tenant_id = 1 and user_id = 105 and domain_code = 'WAREHOUSE'
          and scope_type = 'ALL'
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select scope_type from user_data_scope
        where tenant_id = 1 and user_id = 106 and domain_code = 'WAREHOUSE'
        """, String.class)).isEqualTo("CENTRAL_WAREHOUSE");
    assertThat(jdbc.queryForObject("""
        select count(*) from user_store_scope
        where tenant_id = 1 and user_id in (101, 102, 103)
        """, Integer.class)).isEqualTo(4);
  }

  private void assertOpenWorkMigration(JdbcTemplate jdbc) {
    assertThat(jdbc.queryForMap(
        "select assignee_role, review_role from business_todo where id = 'todo-open'"
    )).containsEntry("assignee_role", "OPERATIONS")
        .containsEntry("review_role", "OPERATIONS");
    assertThat(jdbc.queryForMap(
        "select assignee_role, review_role from business_todo where id = 'todo-completed'"
    )).containsEntry("assignee_role", "SUPERVISOR")
        .containsEntry("review_role", "SUPERVISOR");
    assertThat(jdbc.queryForObject(
        "select source_role from todo_escalation where id = 'escalation-open'",
        String.class
    )).isEqualTo("OPERATIONS");
    assertThat(jdbc.queryForObject(
        "select source_role from todo_escalation where id = 'escalation-resolved'",
        String.class
    )).isEqualTo("SUPERVISOR");
  }

  private void assertPermissionVersionAndTokenRevocation(JdbcTemplate jdbc) {
    assertThat(jdbc.queryForObject(
        "select min(permission_version) from auth_user", Long.class
    )).isEqualTo(2L);
    assertThat(jdbc.queryForObject(
        "select max(permission_version) from auth_user", Long.class
    )).isEqualTo(2L);
    assertThat(jdbc.queryForObject("select count(*) from auth_token", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from information_schema.columns
        where lower(table_name) = 'auth_token' and lower(column_name) = 'permission_version'
        """, Integer.class)).isEqualTo(1);
  }

  private void assertDatabaseConstraints(JdbcTemplate jdbc) {
    assertThatThrownBy(() -> jdbc.update("""
        insert into user_permission_override(
          tenant_id, user_id, permission_code, effect, created_at
        ) values (1, 104, 'store.read', 'UNKNOWN', current_timestamp)
        """)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("""
        insert into user_permission_override(
          tenant_id, user_id, permission_code, effect, created_at
        ) values (1, 101, 'inspection.read', 'DENY', current_timestamp)
        """)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("""
        insert into user_data_scope(
          tenant_id, user_id, domain_code, scope_type, created_at
        ) values (1, 104, 'UNKNOWN', 'ALL', current_timestamp)
        """)).isInstanceOf(DataAccessException.class);
  }

  private String role(JdbcTemplate jdbc, long userId) {
    return jdbc.queryForObject("select role from auth_user where id = ?", String.class, userId);
  }

  private org.flywaydb.core.api.output.MigrateResult migrateTo(DataSource dataSource, String version) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(version)
        .baselineOnMigrate(true)
        .load()
        .migrate();
  }

  private DataSource dataSource(String suffix) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("""
        jdbc:h2:mem:permission-architecture-migration-%s;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """.formatted(suffix).replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private void insertUpgradeFixtures(JdbcTemplate jdbc) {
    jdbc.update("""
        insert into store_branch(id, tenant_id, name, status, created_at)
        values
          ('rg1', 1, '一店', '营业中', current_timestamp),
          ('rg2', 1, '二店', '营业中', current_timestamp)
        """);
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, store_id, enabled, created_at
        ) values
          (101, 1, 'legacy-supervisor', 'hash-supervisor', '原督导', 'SUPERVISOR', 'rg2', 1, current_timestamp),
          (102, 1, 'legacy-employee', 'hash-employee', '原员工', 'EMPLOYEE', 'rg1', 1, current_timestamp),
          (103, 1, 'legacy-manager', 'hash-manager', '原店长', 'STORE_MANAGER', null, 1, current_timestamp),
          (104, 1, 'operations-no-scope', 'hash-operations', '无范围运营', 'OPERATIONS', null, 1, current_timestamp),
          (105, 1, 'finance-global', 'hash-finance', '财务', 'FINANCE', null, 1, current_timestamp),
          (106, 1, 'warehouse-central', 'hash-warehouse', '仓库', 'WAREHOUSE', null, 1, current_timestamp),
          (107, 1, 'legacy-admin', 'hash-admin', '旧管理员', 'ADMIN', 'rg2', 1, current_timestamp),
          (108, 1, 'legacy-ops', 'hash-ops', '旧运营', 'OPS', 'rg2', 1, current_timestamp)
        """);
    jdbc.update("""
        insert into user_store_scope(tenant_id, user_id, store_id, created_at)
        values
          (1, 101, 'rg2', current_timestamp),
          (1, 102, 'rg1', current_timestamp),
          (1, 103, 'rg2', current_timestamp),
          (1, 103, 'rg1', current_timestamp)
        """);
    jdbc.update("""
        insert into auth_token(token, tenant_id, user_id, expires_at, created_at)
        values
          ('supervisor-token', 1, 101, timestamp '2099-01-01 00:00:00', current_timestamp),
          ('employee-token', 1, 102, timestamp '2099-01-01 00:00:00', current_timestamp)
        """);
    jdbc.update("""
        insert into role_permission(tenant_id, role_code, permission_code, created_at)
        values
          (1, 'SUPERVISOR', 'inspection.read', current_timestamp),
          (1, 'EMPLOYEE', 'store.manage', current_timestamp),
          (1, 'FINANCE', 'platform.manage', current_timestamp)
        """);
    jdbc.update("""
        insert into business_todo(
          id, tenant_id, rule_code, source_module, source_record_id, source_key,
          occurrence_no, title, assignee_role, review_role, status, condition_active, created_at
        ) values
          ('todo-open', 1, 'RULE_OPEN', 'inspection', 'source-open', 'open',
           1, '开放待办', 'SUPERVISOR', 'SUPERVISOR', 'PENDING', 1, current_timestamp),
          ('todo-completed', 1, 'RULE_DONE', 'inspection', 'source-done', 'done',
           1, '历史待办', 'SUPERVISOR', 'SUPERVISOR', 'COMPLETED', 0, current_timestamp)
        """);
    jdbc.update("""
        insert into todo_escalation(
          id, tenant_id, source_role, source_module, source_id, source_todo_id,
          reason, severity, boss_todo_id, status, created_at
        ) values
          ('escalation-open', 1, 'SUPERVISOR', 'inspection', 'source-open', 'todo-open',
           '仍需处理', 'HIGH', 'boss-open', 'OPEN', current_timestamp),
          ('escalation-resolved', 1, 'SUPERVISOR', 'inspection', 'source-done', 'todo-completed',
           '历史完成', 'LOW', 'boss-done', 'RESOLVED', current_timestamp)
        """);
  }
}
