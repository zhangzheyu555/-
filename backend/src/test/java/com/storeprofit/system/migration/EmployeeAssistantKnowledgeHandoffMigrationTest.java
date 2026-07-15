package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class EmployeeAssistantKnowledgeHandoffMigrationTest {
  @Test
  void v47CreatesKnowledgeHandoffFeedbackTablesAndRoleScopedPermissions() {
    DataSource dataSource = dataSource();
    var result = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2").target("47")
        .baselineOnMigrate(false).load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(result.success).isTrue();
    for (String table : new String[] {"employee_assistant_knowledge", "employee_assistant_knowledge_version",
        "employee_assistant_handoff", "employee_assistant_feedback"}) {
      assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name) = ?",
          Integer.class, table)).isEqualTo(1);
    }
    assertThat(jdbc.queryForList("select permission_code from permission_catalog where permission_code like 'employee_assistant.%' order by permission_code",
        String.class)).contains("employee_assistant.knowledge_manage", "employee_assistant.handoff_manage");
    assertThat(jdbc.queryForObject("select count(*) from role_permission where role_code = 'OPERATIONS' and permission_code = 'employee_assistant.handoff_manage'",
        Integer.class)).isGreaterThan(0);
    assertThat(jdbc.queryForObject("select count(*) from role_permission where permission_code = 'employee_assistant.knowledge_manage'",
        Integer.class)).isZero();
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:employee-assistant-v47;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
