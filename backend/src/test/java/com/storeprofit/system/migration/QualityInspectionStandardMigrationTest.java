package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QualityInspectionStandardMigrationTest {
  @Test
  void importsQualityStandardForEveryTenantAndKeepsHistoricalSnapshotImmutable() {
    DataSource dataSource = dataSource();
    migrateTo(dataSource, "37");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("""
        insert into tenant(id, name, industry, scale, status, created_at)
        values (2, '第二租户', 'chain_store', 'test', 'ACTIVE', current_timestamp)
        """);
    jdbc.update("""
        insert into inspection_standard_version(
          tenant_id, version, title, full_score, effective_date, status, created_at
        ) values
          (1, 'legacy-v1', '历史标准一', 100, '2024-01-01', 'ACTIVE', current_timestamp),
          (2, 'legacy-v1', '历史标准二', 100, '2024-01-01', 'ACTIVE', current_timestamp)
        """);
    jdbc.update("""
        insert into inspection_record_standard_snapshot(
          tenant_id, inspection_record_id, standard_version, standard_title,
          standard_description, suggested_score, actual_deduction_score,
          red_line, sort_order, created_at
        ) values (
          1, 'HISTORY-001', 'legacy-v1', '历史条款', '历史说明',
          10, 2, 0, 1, current_timestamp
        )
        """);

    var result = migrateTo(dataSource, "38");

    assertThat(result.success).isTrue();
    assertThat(result.targetSchemaVersion).isEqualTo("38");
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_standard_version
        where version = '2025.11.06'
          and title = '茹菓门店品质稽核标准 2025.11.06'
          and full_score = 200 and pass_score = 180 and status = 'ACTIVE'
        """, Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_standard_version
        where version = 'legacy-v1' and status = 'ARCHIVED'
        """, Integer.class)).isEqualTo(2);

    List<Map<String, Object>> tenantCounts = jdbc.queryForList("""
        select version.tenant_id, count(item.id) item_count
        from inspection_standard_version version
        join inspection_standard_item item on item.standard_version_id = version.id
        where version.version = '2025.11.06'
        group by version.tenant_id
        order by version.tenant_id
        """);
    assertThat(tenantCounts).containsExactly(
        Map.of("tenant_id", 1L, "item_count", 105L),
        Map.of("tenant_id", 2L, "item_count", 105L)
    );

    assertCategory(jdbc, "物料标准", 40, "37.00");
    assertCategory(jdbc, "卫生标准", 47, "63.00");
    assertCategory(jdbc, "服务标准", 18, "100.00");
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.version = '2025.11.06'
          and version.tenant_id = 1 and item.risk_level = 'RED' and item.red_line = 1
        """, Integer.class)).isEqualTo(21);
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.version = '2025.11.06'
          and version.tenant_id = 1 and item.risk_level = 'YELLOW' and item.red_line = 0
        """, Integer.class)).isEqualTo(9);
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.version = '2025.11.06'
          and version.tenant_id = 1 and item.risk_level in ('RED', 'YELLOW')
        """, Integer.class)).isEqualTo(30);
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.version = '2025.11.06'
          and version.tenant_id = 1
          and item.source_sheet is not null
          and item.source_row is not null
          and item.subitems_json is not null
        """, Integer.class)).isEqualTo(105);
    ObjectMapper objectMapper = new ObjectMapper();
    jdbc.queryForList("""
        select item.subitems_json
        from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.version = '2025.11.06' and version.tenant_id = 1
        """, String.class).forEach(json ->
        assertThatCode(() -> objectMapper.readTree(json)).doesNotThrowAnyException());

    assertThat(jdbc.queryForMap("""
        select standard_title, standard_description, suggested_score,
               actual_deduction_score, actual_score, standard_code, risk_level
        from inspection_record_standard_snapshot
        where inspection_record_id = 'HISTORY-001'
        """))
        .containsEntry("standard_title", "历史条款")
        .containsEntry("standard_description", "历史说明")
        .containsEntry("suggested_score", new BigDecimal("10.00"))
        .containsEntry("actual_score", new BigDecimal("8.00"))
        .containsEntry("actual_deduction_score", new BigDecimal("2.00"))
        .containsEntry("standard_code", null)
        .containsEntry("risk_level", "NORMAL");

    assertThat(jdbc.queryForObject("""
        select count(*) from information_schema.columns
        where lower(table_name) = 'inspection_record'
          and lower(column_name) in (
            'standard_version_id', 'standard_version', 'material_score',
            'hygiene_score', 'service_score', 'result_code'
          )
        """, Integer.class)).isEqualTo(6);
  }

  private void assertCategory(JdbcTemplate jdbc, String dimension, int count, String score) {
    Map<String, Object> result = jdbc.queryForMap("""
        select count(*) item_count, sum(item.suggested_score) score_sum,
               min(item.category_score) min_category_score,
               max(item.category_score) max_category_score
        from inspection_standard_item item
        join inspection_standard_version version on version.id = item.standard_version_id
        where version.version = '2025.11.06'
          and version.tenant_id = 1 and item.dimension = ?
        """, dimension);
    assertThat(result.get("item_count")).isEqualTo((long) count);
    assertThat(result.get("score_sum")).isEqualTo(new BigDecimal(score));
    assertThat(result.get("min_category_score")).isEqualTo(new BigDecimal(score));
    assertThat(result.get("max_category_score")).isEqualTo(new BigDecimal(score));
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

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("""
        jdbc:h2:mem:quality-inspection-standard-migration;
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
