package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InspectionScoreScaleMigrationTest {
  @Test
  void convertsOnlyLegacyHundredPointRowsAndKeepsAnAuditableBackup() {
    DataSource dataSource = dataSource("conversion");
    migrateTo(dataSource, "40");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String storeId = createStore(jdbc);

    insertRecord(jdbc, storeId, "legacy-98", "100", "98", true, "PASSED", "[]");
    insertRecord(jdbc, storeId, "legacy-82", "100", "82", true, "PASSED", "[]");
    insertRecord(jdbc, storeId, "legacy-red", "100", "98", true,
        "RED_LINE_FAILED", "[{\"code\":\"R1\"}]");
    insertRecord(jdbc, storeId, "current-179", "200", "179", true, "PASSED", "[]");
    insertRecord(jdbc, storeId, "current-180", "200", "180", false, "FAILED", "[]");
    insertRecord(jdbc, storeId, "current-200", "200", "200", true, "PASSED", "[]");

    jdbc.update("""
        update inspection_record
        set material_score = 30, hygiene_score = 30, service_score = 38
        where id = 'legacy-98'
        """);
    jdbc.update("""
        update inspection_record
        set material_score = 25, hygiene_score = 25, service_score = 32
        where id = 'legacy-82'
        """);
    jdbc.update("""
        update inspection_record
        set material_score = 10, hygiene_score = null, service_score = null
        where id = 'legacy-red'
        """);

    jdbc.update("""
        insert into inspection_record_standard_snapshot(
          tenant_id, inspection_record_id, standard_id, standard_version, dimension,
          standard_title, suggested_score, actual_deduction_score, red_line,
          problem_description, sort_order, actual_score, risk_level
        ) values
          (1, 'legacy-98', null, 'legacy', 'A', 'A1', 60, 2, 0, null, 1, 58, 'NORMAL'),
          (1, 'legacy-98', null, 'legacy', 'B', 'B1', 40, 0, 0, null, 2, 40, 'NORMAL'),
          (1, 'legacy-82', null, 'legacy', '扣分', '问题项', 0, 8, 0, '问题快照', 1, 0, 'NORMAL')
        """);

    var migrated = migrateTo(dataSource, "41");
    assertThat(migrated.success).isTrue();
    assertThat(migrated.targetSchemaVersion).isEqualTo("41");

    assertRecord(jdbc, "legacy-98", "196.00", true, "PASSED");
    assertRecord(jdbc, "legacy-82", "164.00", false, "FAILED");
    assertRecord(jdbc, "legacy-red", "196.00", false, "RED_LINE_FAILED");
    assertRecord(jdbc, "current-179", "179.00", false, "FAILED");
    assertRecord(jdbc, "current-180", "180.00", true, "PASSED");
    assertRecord(jdbc, "current-200", "200.00", true, "PASSED");

    assertThat(jdbc.queryForMap("""
        select material_score, hygiene_score, service_score
        from inspection_record where id = 'legacy-98'
        """)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "material_score", new BigDecimal("60.00"),
            "hygiene_score", new BigDecimal("60.00"),
            "service_score", new BigDecimal("76.00")));
    assertThat(jdbc.queryForMap("""
        select material_score, hygiene_score, service_score
        from inspection_record where id = 'legacy-82'
        """)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "material_score", new BigDecimal("50.00"),
            "hygiene_score", new BigDecimal("50.00"),
            "service_score", new BigDecimal("64.00")));
    Map<String, Object> partialCategory = jdbc.queryForMap("""
        select material_score, hygiene_score, service_score
        from inspection_record where id = 'legacy-red'
        """);
    assertThat(partialCategory.get("material_score")).isEqualTo(new BigDecimal("20.00"));
    assertThat(partialCategory.get("hygiene_score")).isNull();
    assertThat(partialCategory.get("service_score")).isNull();

    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_score_scale_migration_audit
        where migration_key = 'V41_100_TO_200'
        """, Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_score_scale_migration_audit
        where migration_key = 'V41_RESULT_RECALC_200'
        """, Integer.class)).isEqualTo(2);
    Map<String, Object> backup = jdbc.queryForMap("""
        select original_full_score, original_pass_score, original_score, original_passed,
               converted_full_score, converted_pass_score, converted_score, converted_passed
        from inspection_score_scale_migration_audit
        where inspection_record_id = 'legacy-82'
          and migration_key = 'V41_100_TO_200'
        """);
    assertThat(backup).containsEntry("original_full_score", new BigDecimal("100.00"));
    assertThat(backup).containsEntry("original_pass_score", null);
    assertThat(backup).containsEntry("original_score", new BigDecimal("82.00"));
    assertThat(((Number) backup.get("original_passed")).intValue()).isEqualTo(1);
    assertThat(backup).containsEntry("converted_full_score", new BigDecimal("200.00"));
    assertThat(backup).containsEntry("converted_pass_score", new BigDecimal("180.00"));
    assertThat(backup).containsEntry("converted_score", new BigDecimal("164.00"));
    assertThat(((Number) backup.get("converted_passed")).intValue()).isZero();

    assertThat(jdbc.queryForList("""
        select suggested_score, actual_deduction_score, actual_score
        from inspection_record_standard_snapshot
        where inspection_record_id = 'legacy-98'
        order by sort_order
        """)).containsExactly(
            Map.of(
                "suggested_score", new BigDecimal("120.00"),
                "actual_deduction_score", new BigDecimal("4.00"),
                "actual_score", new BigDecimal("116.00")),
            Map.of(
                "suggested_score", new BigDecimal("80.00"),
                "actual_deduction_score", new BigDecimal("0.00"),
                "actual_score", new BigDecimal("80.00"))
        );
    assertThat(jdbc.queryForMap("""
        select suggested_score, actual_deduction_score, actual_score
        from inspection_record_standard_snapshot
        where inspection_record_id = 'legacy-82'
        """)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "suggested_score", new BigDecimal("0.00"),
            "actual_deduction_score", new BigDecimal("16.00"),
            "actual_score", new BigDecimal("0.00")));
    assertThat(jdbc.queryForObject(
        "select count(*) from inspection_score_scale_item_migration_audit", Integer.class))
        .isEqualTo(3);
  }

  @Test
  void rerunningFlywayCannotDoubleConvertHistoricalRows() {
    DataSource dataSource = dataSource("idempotent");
    migrateTo(dataSource, "40");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String storeId = createStore(jdbc);
    insertRecord(jdbc, storeId, "legacy-once", "100", "98", true, "PASSED", "[]");

    migrateTo(dataSource, "41");
    migrateTo(dataSource, "41");

    assertRecord(jdbc, "legacy-once", "196.00", true, "PASSED");
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_score_scale_migration_audit
        where inspection_record_id = 'legacy-once'
        """, Integer.class)).isEqualTo(1);
  }

  @Test
  void v42VersionsOneTimeConversionKeepsConvertedDeductionsAndFlagsConflicts() {
    DataSource dataSource = dataSource("v42-audit");
    migrateTo(dataSource, "40");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String storeId = createStore(jdbc);
    Long activeVersionId = jdbc.queryForObject("""
        select id from inspection_standard_version
        where tenant_id = 1 and status = 'ACTIVE'
        """, Long.class);
    Long activeItemId = jdbc.queryForObject("""
        select min(id) from inspection_standard_item
        where tenant_id = 1 and standard_version_id = ? and enabled = 1
        """, Long.class, activeVersionId);

    insertRecord(jdbc, storeId, "legacy-safe", "100", "98", true, "PASSED", "[]");
    insertRecord(jdbc, storeId, "legacy-conflict", "100", "98", true, "PASSED", "[]");
    insertRecord(jdbc, storeId, "current-200", "200", "199", true, "PASSED", "[]");
    jdbc.update("""
        update inspection_record
        set standard_version_id = ?, standard_version = '2025.11.06-R1'
        where id in ('legacy-conflict', 'current-200')
        """, activeVersionId);
    jdbc.update("""
        insert into inspection_record_standard_snapshot(
          tenant_id, inspection_record_id, standard_id, standard_version, dimension,
          standard_title, suggested_score, actual_deduction_score, red_line,
          problem_description, sort_order, actual_score, risk_level
        ) values
          (1, 'legacy-safe', null, 'legacy-100', '旧制', '旧制扣分项', 60, 2, 0, null, 1, 58, 'NORMAL'),
          (1, 'legacy-conflict', ?, '2025.11.06-R1', '物料标准', '200分制条款', 2, 1, 0, null, 1, 1, 'NORMAL'),
          (1, 'current-200', ?, '2025.11.06-R1', '物料标准', '200分制原生条款', 2, 1, 0, null, 1, 1, 'NORMAL')
        """, activeItemId, activeItemId);

    var migrated = migrateTo(dataSource, "42");
    assertThat(migrated.success).isTrue();
    assertThat(migrated.targetSchemaVersion).isEqualTo("42");

    assertThat(jdbc.queryForMap("""
        select conversion_version, conversion_status, conversion_evidence
        from inspection_score_scale_migration_audit
        where inspection_record_id = 'legacy-safe' and migration_key = 'V41_100_TO_200'
        """)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "conversion_version", "100_TO_200_V1",
            "conversion_status", "CONFIRMED_CONVERTED",
            "conversion_evidence", "EXPLICIT_100_SCALE_AND_EXACT_X2"));
    assertThat(jdbc.queryForMap("""
        select conversion_version, conversion_status, conversion_evidence
        from inspection_score_scale_migration_audit
        where inspection_record_id = 'legacy-conflict' and migration_key = 'V41_100_TO_200'
        """)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "conversion_version", "100_TO_200_V1",
            "conversion_status", "MANUAL_REVIEW",
            "conversion_evidence", "CONFLICTING_OR_INCOMPLETE_SCALE_EVIDENCE"));

    assertThat(jdbc.queryForMap("""
        select suggested_score, actual_deduction_score, actual_score
        from inspection_record_standard_snapshot
        where inspection_record_id = 'legacy-safe'
        """)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "suggested_score", new BigDecimal("120.00"),
            "actual_deduction_score", new BigDecimal("4.00"),
            "actual_score", new BigDecimal("116.00")));
    assertThat(jdbc.queryForMap("""
        select suggested_score, actual_deduction_score, actual_score
        from inspection_record_standard_snapshot
        where inspection_record_id = 'current-200'
        """)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "suggested_score", new BigDecimal("2.00"),
            "actual_deduction_score", new BigDecimal("1.00"),
            "actual_score", new BigDecimal("1.00")));
    assertThat(jdbc.queryForObject("""
        select item_audit.conversion_version
        from inspection_score_scale_item_migration_audit item_audit
        join inspection_score_scale_migration_audit audit
          on audit.id = item_audit.score_migration_audit_id
        where audit.inspection_record_id = 'legacy-safe'
        """, String.class)).isEqualTo("100_TO_200_V1");
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_result_repair_audit
        where inspection_record_id = 'legacy-conflict'
          and repair_status = 'MANUAL_REVIEW'
        """, Integer.class)).isEqualTo(1);

    migrateTo(dataSource, "42");
    assertThat(jdbc.queryForObject("""
        select actual_deduction_score from inspection_record_standard_snapshot
        where inspection_record_id = 'legacy-safe'
        """, BigDecimal.class)).isEqualByComparingTo("4.00");
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_result_repair_audit
        where inspection_record_id = 'legacy-conflict'
        """, Integer.class)).isEqualTo(1);
  }

  private void assertRecord(
      JdbcTemplate jdbc,
      String id,
      String expectedScore,
      boolean expectedPassed,
      String expectedResultCode
  ) {
    Map<String, Object> record = jdbc.queryForMap("""
        select full_score, pass_score, score, passed, result_code
        from inspection_record where id = ?
        """, id);
    assertThat(record.get("full_score")).isEqualTo(new BigDecimal("200.00"));
    assertThat(record.get("pass_score")).isEqualTo(new BigDecimal("180.00"));
    assertThat(record.get("score")).isEqualTo(new BigDecimal(expectedScore));
    assertThat(((Number) record.get("passed")).intValue()).isEqualTo(expectedPassed ? 1 : 0);
    assertThat(record.get("result_code")).isEqualTo(expectedResultCode);
  }

  private void insertRecord(
      JdbcTemplate jdbc,
      String storeId,
      String id,
      String fullScore,
      String score,
      boolean passed,
      String resultCode,
      String redlinesJson
  ) {
    jdbc.update("""
        insert into inspection_record(
          id, tenant_id, store_id, inspection_date, inspector, brand,
          full_score, score, passed, deductions_json, redlines_json,
          photos_json, result_code
        ) values (?, 1, ?, '2026-07-13', '督导', '茹菓', ?, ?, ?, '[]', ?, '[]', ?)
        """, id, storeId, new BigDecimal(fullScore), new BigDecimal(score),
        passed ? 1 : 0, redlinesJson, resultCode);
  }

  private String createStore(JdbcTemplate jdbc) {
    jdbc.update("insert into brand(tenant_id, code, name) values (1, 'TEST', '测试品牌')");
    jdbc.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name, status)
        values ('score-test-store', 1,
          (select id from brand where tenant_id = 1 and code = 'TEST'),
          'SCORE-TEST', '评分测试店', '营业中')
        """);
    return "score-test-store";
  }

  private org.flywaydb.core.api.output.MigrateResult migrateTo(
      DataSource dataSource,
      String version
  ) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(version)
        .baselineOnMigrate(true)
        .load()
        .migrate();
  }

  private DataSource dataSource(String name) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(("""
        jdbc:h2:mem:inspection-score-scale-%s;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """).formatted(name).replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
