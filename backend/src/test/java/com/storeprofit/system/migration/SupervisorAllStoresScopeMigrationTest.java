package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SupervisorAllStoresScopeMigrationTest {
  @Test
  void grantsEveryExistingTenantStoreWithoutOpeningFinanceScope() {
    DataSource dataSource = dataSource();
    migrateTo(dataSource, "77");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("insert into tenant(id, name) values (2, '督导迁移租户')");
    jdbc.update("""
        insert into store_branch(id, tenant_id, code, name, status, created_at)
        values ('scope-store-a', 2, 'SCOPE-A', '范围门店 A', '营业中', current_timestamp),
               ('scope-store-b', 2, 'SCOPE-B', '范围门店 B', '停业中', current_timestamp)
        """);
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, enabled,
          permission_version, created_at
        ) values (9910, 2, 'all-store-supervisor', 'hash', '全门店督导', 'SUPERVISOR', 1,
          5, current_timestamp)
        """);
    jdbc.update("""
        insert into user_data_scope(
          tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at
        ) values (2, 9910, 'STORE', 'NONE', null, current_timestamp),
                 (2, 9910, 'INSPECTION', 'NONE', null, current_timestamp),
                 (2, 9910, 'FINANCE', 'NONE', null, current_timestamp)
        """);
    jdbc.update("""
        insert into auth_token(
          token_hash, tenant_id, user_id, permission_version, expires_at, created_at
        ) values ('supervisor-scope-token', 2, 9910, 5, timestamp '2099-01-01 00:00:00', current_timestamp)
        """);

    var migrated = migrateTo(dataSource, "78");

    assertThat(migrated.success).isTrue();
    assertThat(jdbc.queryForList("""
        select store_id from user_store_scope
        where tenant_id = 2 and user_id = 9910
        order by store_id
        """, String.class)).containsExactly("scope-store-a", "scope-store-b");
    assertThat(jdbc.queryForObject("""
        select count(*) from user_data_scope
        where tenant_id = 2 and user_id = 9910
          and domain_code in ('STORE', 'WAREHOUSE', 'INSPECTION', 'EXAM', 'PLATFORM')
          and scope_type = 'STORE_LIST'
        """, Integer.class)).isEqualTo(5);
    assertThat(jdbc.queryForObject("""
        select scope_value_json from user_data_scope
        where tenant_id = 2 and user_id = 9910 and domain_code = 'INSPECTION'
        """, String.class)).contains("scope-store-a", "scope-store-b");
    assertThat(jdbc.queryForObject("""
        select scope_type from user_data_scope
        where tenant_id = 2 and user_id = 9910 and domain_code = 'FINANCE'
        """, String.class)).isEqualTo("NONE");
    assertThat(jdbc.queryForObject(
        "select permission_version from auth_user where id = 9910", Long.class)).isEqualTo(6L);
    assertThat(jdbc.queryForObject(
        "select count(*) from auth_token where user_id = 9910", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from operation_log
        where tenant_id = 2 and action = '修复督导门店范围'
          and target_type = 'auth_user' and target_id = '9910'
        """, Integer.class)).isEqualTo(1);
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:supervisor-all-stores;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
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
