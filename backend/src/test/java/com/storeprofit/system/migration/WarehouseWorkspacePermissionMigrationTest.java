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
