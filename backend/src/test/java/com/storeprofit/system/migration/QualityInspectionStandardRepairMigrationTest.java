package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QualityInspectionStandardRepairMigrationTest {
  private static final String CORRECTED_VERSION = "2025.11.06-R1";

  @Test
  void mysqlMigrationUsesHardFailureGatesBeforeActivatingTheCorrectedStandard()
      throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(
        "db/migration/V40__correct_rugua_quality_standard_and_repair_audit.sql")) {
      assertThat(input).isNotNull();
      String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

      assertThat(sql.lines()
          .filter(line -> line.matches(
              "\\s*\\('(物料标准|卫生标准|服务标准)'.*"))
          .count()).isEqualTo(105);
      assertThat(countOccurrences(sql, "signal sqlstate '45000'")).isEqualTo(4);

      int lastHardFailure = sql.lastIndexOf("signal sqlstate '45000'");
      int archivePrevious = sql.indexOf("""
          update inspection_standard_version
          set status = 'ARCHIVED'""");
      int activateCorrected = sql.indexOf("""
          update inspection_standard_version
          set status = 'ACTIVE'""");
      assertThat(lastHardFailure).isGreaterThan(0);
      assertThat(archivePrevious).isGreaterThan(lastHardFailure);
      assertThat(activateCorrected).isGreaterThan(archivePrevious);
    }
  }

  @Test
  void installsCorrectedStandardWithoutOverwritingTheInvalidHistoricalVersion() {
    DataSource dataSource = dataSource();
    migrateTo(dataSource, "39");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Long invalidVersionId = jdbc.queryForObject("""
        select id
        from inspection_standard_version
        where tenant_id = 1 and version = '2025.11.06'
        """, Long.class);

    // Reproduce the observed post-import defect while keeping 105 total rows:
    // 43 material / 49 points, 47 hygiene / 66 points, 15 service / 85 points.
    jdbc.update("""
        update inspection_standard_item
        set dimension = '物料标准', suggested_score = 4, category_score = 49
        where standard_version_id = ?
          and code in ('S-A-01', 'S-A-02', 'S-A-03')
        """, invalidVersionId);
    jdbc.update("""
        update inspection_standard_item
        set suggested_score = 4
        where standard_version_id = ? and code = 'H-1.1'
        """, invalidVersionId);
    jdbc.update("""
        update inspection_standard_item
        set category_score = 66
        where standard_version_id = ? and dimension = '卫生标准'
        """, invalidVersionId);

    assertCategory(jdbc, "2025.11.06", 1L, "物料标准", 43, "49.00");
    assertCategory(jdbc, "2025.11.06", 1L, "卫生标准", 47, "66.00");
    assertCategory(jdbc, "2025.11.06", 1L, "服务标准", 15, "85.00");

    jdbc.update("""
        insert into tenant(id, name, industry, scale, status, created_at)
        values (2, '第二租户', 'chain_store', 'test', 'ACTIVE', current_timestamp)
        """);

    var result = migrateTo(dataSource, "40");

    assertThat(result.success).isTrue();
    assertThat(result.targetSchemaVersion).isEqualTo("40");
    assertThat(jdbc.queryForObject("""
        select count(*)
        from inspection_standard_version
        where version = ?
          and title = '茹菓门店品质稽核标准 2025.11.06（修正版 R1）'
          and full_score = 200
          and pass_score = 180
          and status = 'ACTIVE'
        """, Integer.class, CORRECTED_VERSION)).isEqualTo(2);

    List<Map<String, Object>> tenantCounts = jdbc.queryForList("""
        select version.tenant_id, count(item.id) item_count
        from inspection_standard_version version
        join inspection_standard_item item on item.standard_version_id = version.id
        where version.version = ?
        group by version.tenant_id
        order by version.tenant_id
        """, CORRECTED_VERSION);
    assertThat(tenantCounts).containsExactly(
        Map.of("tenant_id", 1L, "item_count", 105L),
        Map.of("tenant_id", 2L, "item_count", 105L)
    );

    assertCategory(jdbc, CORRECTED_VERSION, 1L, "物料标准", 40, "37.00");
    assertCategory(jdbc, CORRECTED_VERSION, 1L, "卫生标准", 47, "63.00");
    assertCategory(jdbc, CORRECTED_VERSION, 1L, "服务标准", 18, "100.00");
    assertThat(jdbc.queryForObject("""
        select count(*)
        from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.tenant_id = 1
          and version.version = ?
          and item.risk_level = 'RED'
          and item.red_line = 1
        """, Integer.class, CORRECTED_VERSION)).isEqualTo(21);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.tenant_id = 1
          and version.version = ?
          and item.risk_level = 'YELLOW'
          and item.red_line = 0
        """, Integer.class, CORRECTED_VERSION)).isEqualTo(9);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.tenant_id = 1
          and version.version = ?
          and item.source_sheet is not null
          and item.source_row is not null
          and item.subitems_json is not null
        """, Integer.class, CORRECTED_VERSION)).isEqualTo(105);

    // The invalid standard is evidence and must remain byte-for-byte repairable.
    assertThat(jdbc.queryForObject("""
        select status
        from inspection_standard_version
        where tenant_id = 1 and version = '2025.11.06'
        """, String.class)).isEqualTo("ARCHIVED");
    assertCategory(jdbc, "2025.11.06", 1L, "物料标准", 43, "49.00");
    assertCategory(jdbc, "2025.11.06", 1L, "卫生标准", 47, "66.00");
    assertCategory(jdbc, "2025.11.06", 1L, "服务标准", 15, "85.00");

    assertThat(jdbc.queryForObject("""
        select count(*)
        from information_schema.columns
        where lower(table_name) = 'inspection_result_repair_audit'
          and lower(column_name) in (
            'id', 'tenant_id', 'inspection_record_id',
            'original_standard_version_id', 'original_standard_version',
            'original_full_score', 'original_pass_score', 'original_score',
            'original_material_score', 'original_hygiene_score', 'original_service_score',
            'original_result_code', 'original_passed',
            'repaired_standard_version_id', 'repaired_standard_version',
            'repaired_full_score', 'repaired_pass_score', 'repaired_score',
            'repaired_material_score', 'repaired_hygiene_score', 'repaired_service_score',
            'repaired_result_code', 'repaired_passed', 'repair_status',
            'repair_reason', 'snapshot_item_count', 'expected_item_count',
            'repaired_by', 'repaired_at'
          )
        """, Integer.class)).isEqualTo(29);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from information_schema.table_constraints
        where lower(table_name) = 'inspection_result_repair_audit'
          and lower(constraint_name) = 'uk_inspection_result_repair_target'
          and constraint_type = 'UNIQUE'
        """, Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from information_schema.columns
        where lower(table_name) = 'inspection_result_repair_audit'
          and lower(column_name) = 'repaired_standard_version_id'
          and is_nullable = 'NO'
        """, Integer.class)).isEqualTo(1);
  }

  private void assertCategory(
      JdbcTemplate jdbc,
      String version,
      long tenantId,
      String dimension,
      int count,
      String score) {
    Map<String, Object> result = jdbc.queryForMap("""
        select count(*) item_count, sum(item.suggested_score) score_sum
        from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.version = ?
          and version.tenant_id = ?
          and item.dimension = ?
        """, version, tenantId, dimension);
    assertThat(result.get("item_count")).isEqualTo((long) count);
    assertThat(result.get("score_sum")).isEqualTo(new BigDecimal(score));
  }

  private org.flywaydb.core.api.output.MigrateResult migrateTo(
      DataSource dataSource, String version) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(version)
        .baselineOnMigrate(true)
        .load()
        .migrate();
  }

  private int countOccurrences(String value, String expected) {
    return (value.length() - value.replace(expected, "").length()) / expected.length();
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("""
        jdbc:h2:mem:quality-inspection-standard-repair-migration;
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
