package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SupervisorTakesOperationsRoleMigrationTest {
  @Test
  void migratesTenantTwoLegacyOperationsWithoutCrossTenantLoss() {
    DataSource dataSource = dataSource();
    migrateTo(dataSource, "70");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("insert into tenant(id, name) values (2, '迁移租户')");
    jdbc.update("delete from role_permission where role_code in ('OPERATIONS', 'SUPERVISOR')");
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, store_id,
          enabled, permission_version, created_at
        ) values (9901, 2, 'legacy-operations', 'hash', '历史运营', 'OPERATIONS', 'legacy-store',
          1, 7, current_timestamp)
        """);
    jdbc.update("""
        insert into user_data_scope(tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at)
        values (2, 9901, 'PLATFORM', 'STORE_LIST', '[\"legacy-store\"]', current_timestamp)
        """);
    jdbc.update("""
        insert into role_permission(tenant_id, role_code, permission_code, created_at)
        values (2, 'SUPERVISOR', 'operations.dashboard.read', current_timestamp),
               (2, 'OPERATIONS', 'operations.dashboard.read', current_timestamp),
               (2, 'OPERATIONS', 'platform.manage', current_timestamp)
        """);
    jdbc.update("""
        insert into user_permission_override(
          tenant_id, user_id, permission_code, effect, created_by, created_at
        ) values (2, 9901, 'todo.read', 'ALLOW', null, current_timestamp)
        """);
    jdbc.update("""
        insert into auth_token(token_hash, tenant_id, user_id, permission_version, expires_at, created_at)
        values ('migration-test-token', 2, 9901, 7, timestamp '2099-01-01 00:00:00', current_timestamp)
        """);
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, store_id,
          enabled, permission_version, created_at
        ) values (9902, 1, 'other-tenant-employee', 'hash', '其他租户员工', 'EMPLOYEE', 'other-store',
          1, 4, current_timestamp)
        """);
    jdbc.update("""
        insert into auth_token(token_hash, tenant_id, user_id, permission_version, expires_at, created_at)
        values ('other-tenant-token', 1, 9902, 4, timestamp '2099-01-01 00:00:00', current_timestamp)
        """);

    var migrated = migrateTo(dataSource, "71");

    assertThat(migrated.success).isTrue();
    assertThat(jdbc.queryForObject("select role from auth_user where id = 9901", String.class))
        .isEqualTo("SUPERVISOR");
    assertThat(jdbc.queryForObject("select permission_version from auth_user where id = 9901", Long.class))
        .isEqualTo(8L);
    assertThat(jdbc.queryForObject("select count(*) from auth_token where user_id = 9901", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from role_permission
        where tenant_id = 2 and role_code = 'SUPERVISOR'
          and permission_code in ('operations.dashboard.read', 'platform.manage')
        """, Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("""
        select count(*) from role_permission
        where tenant_id = 2 and role_code = 'SUPERVISOR' and permission_code = 'operations.dashboard.read'
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from role_permission where upper(role_code) in ('OPERATIONS', 'OPS')", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("""
        select scope_value_json from user_data_scope
        where tenant_id = 2 and user_id = 9901 and domain_code = 'PLATFORM'
        """, String.class)).contains("legacy-store");
    assertThat(jdbc.queryForObject("""
        select effect from user_permission_override
        where tenant_id = 2 and user_id = 9901 and permission_code = 'todo.read'
        """, String.class)).isEqualTo("ALLOW");
    assertThat(jdbc.queryForObject("""
        select count(*) from operation_log
        where tenant_id = 2 and action = '角色迁移' and target_type = 'auth_user' and target_id = '9901'
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select tenant_id from operation_log
        where action = '角色迁移' and target_type = 'auth_user' and target_id = '9901'
        """, Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("select role from auth_user where id = 9902", String.class))
        .isEqualTo("EMPLOYEE");
    assertThat(jdbc.queryForObject("select permission_version from auth_user where id = 9902", Long.class))
        .isEqualTo(4L);
    assertThat(jdbc.queryForObject("select count(*) from auth_token where user_id = 9902", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*) from operation_log
        where tenant_id = 1 and target_type = 'auth_user' and target_id = '9902'
        """, Integer.class)).isZero();
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:supervisor-takes-operations;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private org.flywaydb.core.api.output.MigrateResult migrateTo(DataSource dataSource, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(target)
        .baselineOnMigrate(true)
        .load()
        .migrate();
  }
}
