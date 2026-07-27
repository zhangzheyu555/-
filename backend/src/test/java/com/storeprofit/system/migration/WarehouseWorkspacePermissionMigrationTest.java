package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WarehouseWorkspacePermissionMigrationTest {
  private static final String[] WAREHOUSE_WORKSPACE_PERMISSIONS = {
      "warehouse.read",
      "warehouse.purchase",
      "warehouse.transfer.request",
      "warehouse.transfer.approve",
      "warehouse.transfer.ship",
      "warehouse.transfer.receive",
      "warehouse.requisition.process",
      "warehouse.configure"
  };

  @Test
  void v61RepairsWarehouseRoleTemplateWhenNewWorkspacePermissionsWereMissing() {
    DataSource dataSource = dataSource("v61");
    migrate(dataSource, "60");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    for (String permission : WAREHOUSE_WORKSPACE_PERMISSIONS) {
      jdbc.update("""
          delete from role_permission
          where role_code = 'WAREHOUSE' and permission_code = ?
          """, permission);
    }

    migrate(dataSource, "61");

    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'WAREHOUSE'
          and permission_code in (
            'warehouse.read',
            'warehouse.purchase',
            'warehouse.transfer.request',
            'warehouse.transfer.approve',
            'warehouse.transfer.ship',
            'warehouse.transfer.receive',
            'warehouse.requisition.process',
            'warehouse.configure'
          )
        order by permission_code
        """, String.class)).containsExactly(
            "warehouse.configure",
            "warehouse.purchase",
            "warehouse.read",
            "warehouse.requisition.process",
            "warehouse.transfer.approve",
            "warehouse.transfer.receive",
            "warehouse.transfer.request",
            "warehouse.transfer.ship"
        );
  }

  @Test
  void v78GrantsFinanceOnlyTheScopedWarehouseReadTemplatePermission() {
    DataSource dataSource = dataSource("v78");
    migrate(dataSource, "77");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("""
        delete from role_permission
        where role_code = 'FINANCE' and permission_code = 'warehouse.read'
        """);

    migrate(dataSource, "78");

    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'FINANCE'
          and permission_code like 'warehouse.%'
        order by permission_code
        """, String.class)).containsExactly("warehouse.read");
  }

  @Test
  void v80RemovesWarehouseAndEmployeeAssistantFromFinanceRoleTemplate() {
    DataSource dataSource = dataSource("v80");
    migrate(dataSource, "79");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'FINANCE'
          and permission_code in ('warehouse.read', 'employee_assistant.use')
        order by permission_code
        """, String.class)).containsExactly("employee_assistant.use", "warehouse.read");

    migrate(dataSource, "80");

    assertThat(jdbc.queryForList("""
        select permission_code
        from role_permission
        where role_code = 'FINANCE'
          and permission_code in ('warehouse.read', 'employee_assistant.use')
        """, String.class)).isEmpty();
  }

  @Test
  void v108MakesFinanceSupervisorAndWarehouseInventoryReadOnlyAndRevokesOldSessions() {
    DataSource dataSource = dataSource("v108");
    migrate(dataSource, "107.20260727120000002");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("insert into tenant(id, name) values (105, '盘存权限迁移租户')");

    String[] roles = {"FINANCE", "SUPERVISOR", "WAREHOUSE"};
    for (int index = 0; index < roles.length; index++) {
      long userId = 10501L + index;
      String role = roles[index];
      jdbc.update("""
          insert into auth_user(
            id, tenant_id, username, password_hash, display_name, role,
            enabled, permission_version, created_at
          ) values (?, 105, ?, 'hash', ?, ?, 1, 7, current_timestamp)
          """, userId, "inventory-readonly-" + role.toLowerCase(), role, role);
      jdbc.update("""
          insert into auth_token(
            token_hash, tenant_id, user_id, permission_version, expires_at, created_at
          ) values (?, 105, ?, 7, timestamp '2099-01-01 00:00:00', current_timestamp)
          """, String.format("%064x", userId), userId);
      jdbc.update("""
          insert into role_permission(tenant_id, role_code, permission_code, created_at)
          values (105, ?, 'inventory.manage', current_timestamp),
                 (105, ?, 'inventory.review', current_timestamp)
          """, role, role);
      jdbc.update("""
          insert into user_permission_override(
            tenant_id, user_id, permission_code, effect, created_at
          ) values (105, ?, 'inventory.manage', 'ALLOW', current_timestamp),
                   (105, ?, 'inventory.review', 'ALLOW', current_timestamp)
          """, userId, userId);
    }

    migrate(dataSource, "108.20260727120000003");

    assertThat(jdbc.queryForList("""
        select role_code
        from role_permission
        where tenant_id = 105
          and permission_code = 'inventory.read'
        order by role_code
        """, String.class)).containsExactly("FINANCE", "SUPERVISOR", "WAREHOUSE");
    assertThat(jdbc.queryForObject("""
        select count(*)
        from role_permission
        where upper(role_code) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE')
          and permission_code in ('inventory.manage', 'inventory.review')
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*)
        from user_permission_override
        where tenant_id = 105
          and effect = 'ALLOW'
          and permission_code in ('inventory.manage', 'inventory.review')
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForList("""
        select permission_version
        from auth_user
        where tenant_id = 105
        order by id
        """, Long.class)).containsExactly(8L, 8L, 8L);
    assertThat(jdbc.queryForObject(
        "select count(*) from auth_token where tenant_id = 105", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*)
        from tenant tenant_row
        where not exists (
          select 1
          from role_permission permission_row
          where permission_row.tenant_id = tenant_row.id
            and upper(permission_row.role_code) = 'WAREHOUSE'
            and permission_row.permission_code = 'inventory.read'
        )
        """, Integer.class)).isZero();
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

  private DataSource dataSource(String testCase) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:warehouse-workspace-permission-" + testCase
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
