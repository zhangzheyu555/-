package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InspectionRectificationWorkflowMigrationTest {
  @Test
  void v55AddsASeparateWorkflowWithoutChangingExistingInspectionScoreOrSnapshotFacts() {
    DataSource dataSource = dataSource();
    migrateTo(dataSource, "54");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String storeId = createStore(jdbc);
    jdbc.update("""
        insert into inspection_record(
          id, tenant_id, store_id, inspection_date, inspector, brand,
          full_score, pass_score, score, passed, deductions_json, redlines_json,
          photos_json, standard_version, result_code
        ) values (
          'E2E_RECTIFICATION_HISTORY', 1, ?, '2026-07-15', 'Supervisor', 'Brand',
          200, 180, 176, 0, '[{\"deduct\":24}]', '[{\"code\":\"R-1\"}]',
          '[]', 'history-standard', 'RED_LINE_FAILED'
        )
        """, storeId);
    jdbc.update("""
        insert into inspection_record_standard_snapshot(
          tenant_id, inspection_record_id, standard_id, standard_version, dimension,
          standard_title, suggested_score, actual_deduction_score, actual_score,
          red_line, problem_description, sort_order, risk_level
        ) values (
          1, 'E2E_RECTIFICATION_HISTORY', null, 'history-standard', 'HYGIENE',
          'Historical evidence', 24, 24, 0, 1, 'Existing deduction', 1, 'RED'
        )
        """);
    Map<String, Object> scoreBefore = jdbc.queryForMap("""
        select full_score, pass_score, score, passed, deductions_json, redlines_json,
               standard_version, result_code
        from inspection_record where id = 'E2E_RECTIFICATION_HISTORY'
        """);
    Map<String, Object> snapshotBefore = jdbc.queryForMap("""
        select standard_version, suggested_score, actual_deduction_score, actual_score,
               red_line, problem_description
        from inspection_record_standard_snapshot
        where inspection_record_id = 'E2E_RECTIFICATION_HISTORY'
        """);

    var result = migrateTo(dataSource, "55");

    assertThat(result.success).isTrue();
    assertThat(result.targetSchemaVersion).isEqualTo("55");
    assertThat(jdbc.queryForObject("""
        select count(*) from information_schema.tables
        where lower(table_name) = 'inspection_rectification'
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*) from information_schema.tables
        where lower(table_name) = 'inspection_rectification_action'
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForMap("""
        select full_score, pass_score, score, passed, deductions_json, redlines_json,
               standard_version, result_code
        from inspection_record where id = 'E2E_RECTIFICATION_HISTORY'
        """)).isEqualTo(scoreBefore);
    assertThat(jdbc.queryForMap("""
        select standard_version, suggested_score, actual_deduction_score, actual_score,
               red_line, problem_description
        from inspection_record_standard_snapshot
        where inspection_record_id = 'E2E_RECTIFICATION_HISTORY'
        """)).isEqualTo(snapshotBefore);

    jdbc.update("""
        insert into inspection_rectification(
          id, tenant_id, inspection_record_id, store_id, status, version, created_at, updated_at
        ) values (
          'E2E_RECTIFICATION_WORKFLOW', 1, 'E2E_RECTIFICATION_HISTORY', ?,
          'PENDING_SUBMISSION', 0, current_timestamp, current_timestamp
        )
        """, storeId);
    jdbc.update("""
        insert into inspection_rectification_action(
          id, tenant_id, rectification_id, inspection_record_id, action, status, note,
          actor_user_id, actor_name, actor_role, created_at
        ) values (
          'E2E_RECTIFICATION_ACTION', 1, 'E2E_RECTIFICATION_WORKFLOW',
          'E2E_RECTIFICATION_HISTORY', 'EVIDENCE_UPLOADED', 'PENDING_SUBMISSION',
          'evidence received', 8, 'Manager', 'STORE_MANAGER', current_timestamp
        )
        """);
    assertThat(jdbc.queryForObject("select count(*) from inspection_rectification_action", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select score from inspection_record where id = 'E2E_RECTIFICATION_HISTORY'
        """, BigDecimal.class)).isEqualByComparingTo("176.00");
  }

  private String createStore(JdbcTemplate jdbc) {
    jdbc.update("insert into brand(tenant_id, code, name) values (1, 'E2E', 'E2E Brand')");
    jdbc.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name, status)
        values ('e2e-rectification-store', 1,
          (select id from brand where tenant_id = 1 and code = 'E2E'),
          'E2E-R', 'E2E Rectification Store', 'OPEN')
        """);
    return "e2e-rectification-store";
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

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("""
        jdbc:h2:mem:inspection-rectification-workflow;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """.replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
