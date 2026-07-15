package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class EmployeeAssistantPermissionMigrationTest {
  @Test
  void v46AddsPermissionOnlyForExistingBusinessRoleTemplates() {
    DataSource dataSource = dataSource("v46");
    var result = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("46")
        .baselineOnMigrate(false)
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(result.success).isTrue();
    assertThat(jdbc.queryForObject("""
        select count(*) from permission_catalog where permission_code = 'employee_assistant.use'
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForList("""
        select distinct role_code from role_permission
        where permission_code = 'employee_assistant.use'
        order by role_code
        """, String.class)).containsExactly("FINANCE", "OPERATIONS", "STORE_MANAGER", "WAREHOUSE");
    assertThat(jdbc.queryForObject("""
        select count(*) from role_permission
        where permission_code = 'employee_assistant.use' and role_code = 'EMPLOYEE'
        """, Integer.class)).isZero();
  }

  @Test
  void v48AddsOnlyEmployeeAssistantUsePermissionForOrdinaryEmployees() {
    DataSource dataSource = dataSource("v48");
    var result = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("48")
        .baselineOnMigrate(false)
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(result.success).isTrue();
    assertThat(jdbc.queryForList("""
        select distinct role_code from role_permission
        where permission_code = 'employee_assistant.use'
        order by role_code
        """, String.class)).containsExactly(
            "EMPLOYEE", "FINANCE", "OPERATIONS", "STORE_MANAGER", "WAREHOUSE"
        );
    assertThat(jdbc.queryForList("""
        select permission_code from role_permission
        where role_code = 'EMPLOYEE'
        order by permission_code
        """, String.class)).containsExactly("employee_assistant.use", "exam.learn");
  }

  private DataSource dataSource(String migrationVersion) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:employee-assistant-" + migrationVersion
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
