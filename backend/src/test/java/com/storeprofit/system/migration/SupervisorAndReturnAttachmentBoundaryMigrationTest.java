package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SupervisorAndReturnAttachmentBoundaryMigrationTest {
  @Test
  void v62RebuildsSupervisorTemplateAndRepairsReturnAttachments() {
    DataSource dataSource = dataSource();
    migrate(dataSource, "61");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("insert into store_branch(id, tenant_id, name) values ('ret-store', 1, '退货门店')");
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role, enabled, permission_version
        ) values (900, 1, 'supervisor-v62', 'hash', '督导', 'SUPERVISOR', 1, 5)
        """);
    jdbc.update("""
        insert into auth_token(token_hash, tenant_id, user_id, permission_version, expires_at)
        values ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1, 900, 5, dateadd('day', 1, current_timestamp))
        """);
    jdbc.update("""
        insert into role_permission(tenant_id, role_code, permission_code, created_at)
        values (1, 'SUPERVISOR', 'platform.manage', current_timestamp)
        """);
    jdbc.update("""
        insert into user_permission_override(tenant_id, user_id, permission_code, effect, created_at)
        values (1, 900, 'platform.manage', 'ALLOW', current_timestamp)
        """);
    jdbc.update("""
        insert into user_data_scope(tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at)
        values
          (1, 900, 'STORE', 'STORE_LIST', '["ret-store"]', current_timestamp),
          (1, 900, 'INSPECTION', 'STORE_LIST', '["ret-store"]', current_timestamp),
          (1, 900, 'PLATFORM', 'ALL', null, current_timestamp)
        """);
    jdbc.update("""
        insert into warehouse_return_order(
          id, tenant_id, warehouse_id, receive_warehouse_code_snapshot,
          receive_warehouse_name_snapshot, return_no, return_store_id, return_store_name, return_date
        )
        select 'return-v62', 1, facility.id, facility.code, facility.name,
               'R-V62', 'ret-store', '退货门店', current_date
        from warehouse_facility facility
        where facility.tenant_id = 1 and facility.code = 'JZ-CENTRAL'
        """);
    jdbc.update("""
        insert into warehouse_attachment(
          tenant_id, business_type, business_id, file_name
        ) values (1, 'RETURN_ORDER', 'return-v62', 'return.pdf')
        """);

    migrate(dataSource, "62");

    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'SUPERVISOR'
        order by permission_code
        """, String.class)).containsExactly(
            "assistant.use",
            "attachment.read",
            "attachment.write",
            "employee_assistant.handoff_manage",
            "employee_assistant.use",
            "inspection.manage",
            "inspection.read",
            "store.read",
            "todo.read",
            "todo.transition"
        );
    assertThat(jdbc.queryForObject("""
        select count(*) from user_permission_override
        where user_id = 900 and permission_code = 'platform.manage'
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select scope_type from user_data_scope
        where user_id = 900 and domain_code = 'PLATFORM'
        """, String.class)).isEqualTo("NONE");
    assertThat(jdbc.queryForObject("""
        select store_id from warehouse_attachment
        where business_id = 'return-v62'
        """, String.class)).isEqualTo("ret-store");
    assertThat(jdbc.queryForObject("""
        select business_type from warehouse_attachment
        where business_id = 'return-v62'
        """, String.class)).isEqualTo("WAREHOUSE_RETURN");
    assertThat(jdbc.queryForObject("select permission_version from auth_user where id = 900", Long.class))
        .isEqualTo(6L);
    assertThat(jdbc.queryForObject("select count(*) from auth_token where user_id = 900", Integer.class)).isZero();
  }

  private void migrate(DataSource dataSource, String target) {
    var result = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(target)
        .baselineOnMigrate(false)
        .load()
        .migrate();
    assertThat(result.success).isTrue();
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:supervisor-return-v62"
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
