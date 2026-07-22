package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseColumnCommentMigrationContractTest {
  private static final Pattern HAN_CHARACTER = Pattern.compile("\\p{IsHan}");

  @Test
  void v56FilesMatchAndEveryBusinessColumnHasAChineseRemark() throws IOException {
    Resource[] mysqlMigrations = resources("classpath*:db/migration/V56__*.sql");
    Resource[] h2Migrations = resources("classpath*:db/migration-h2/V56__*.sql");

    assertThat(mysqlMigrations)
        .as("MySQL must contain exactly one V56 migration")
        .hasSize(1);
    assertThat(h2Migrations)
        .as("H2 must contain exactly one V56 migration")
        .hasSize(1);
    assertThat(h2Migrations[0].getFilename())
        .as("MySQL and H2 V56 migrations must use the same filename")
        .isEqualTo(mysqlMigrations[0].getFilename());

    String mysqlSql = new String(mysqlMigrations[0].getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(Pattern.compile("ON UPDATE CURRENT_TIMESTAMP COMMENT").matcher(mysqlSql).results())
        .as("All 12 V55 ON UPDATE definitions must be preserved")
        .hasSize(12);
    for (String table : List.of(
        "permission_catalog", "user_permission_override", "user_data_scope")) {
      assertThat(mysqlSql)
          .as("%s.updated_at must support both known V55 definitions and fail closed", table)
          .contains(
              "ALTER TABLE `" + table + "` MODIFY COLUMN `updated_at` datetime NULL DEFAULT NULL COMMENT",
              "ALTER TABLE `" + table + "` MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT",
              "__v56_unsupported_" + table + "_updated_at__");
    }
    assertThat(mysqlSql).contains(
        "工资方案名称（例如：2026标准工资规则）",
        "方案失效日期（空值表示长期有效）",
        "月保底工资（空值表示无保底）",
        "薪资项目类型（EARNING收入、DEDUCTION扣款、EMPLOYER_COST雇主成本、INFORMATION信息）",
        "明细来源（MANUAL手工、ATTENDANCE考勤、PERFORMANCE绩效、CALCULATED计算、IMPORT导入）");

    DataSource dataSource = dataSource();
    var migrated = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("56")
        .baselineOnMigrate(false)
        .load()
        .migrate();

    assertThat(migrated.success).isTrue();
    assertThat(migrated.targetSchemaVersion).isEqualTo("56");

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<ColumnRemark> columns = jdbc.query("""
        select table_name, column_name, remarks
        from information_schema.columns
        where lower(table_schema) = 'public'
          and lower(table_name) <> 'flyway_schema_history'
        order by table_name, ordinal_position
        """, (resultSet, rowNumber) -> new ColumnRemark(
            resultSet.getString("table_name"),
            resultSet.getString("column_name"),
            resultSet.getString("remarks")));

    assertThat(columns)
        .as("The migrated schema must contain business columns")
        // V24 keeps H2 aligned with the MySQL employee.data_source schema. The salary birthday
        // benefit repeatable migration also runs when Flyway targets V56 and adds one column.
        .hasSize(1164)
        .allSatisfy(column -> {
          assertThat(column.remarks())
              .as("%s.%s must have a non-empty REMARKS value", column.tableName(), column.columnName())
              .isNotBlank();
          assertThat(HAN_CHARACTER.matcher(column.remarks()).find())
              .as("%s.%s REMARKS must contain at least one Chinese character: %s",
                  column.tableName(), column.columnName(), column.remarks())
              .isTrue();
        });
    assertThat(columns.stream().map(ColumnRemark::tableName).distinct())
        .as("H2 V55 contains 90 business tables")
        .hasSize(90);
    assertRemark(jdbc, "store_branch", "area", "所属区域");
    assertRemark(jdbc, "salary_record", "full_attendance", "全勤奖金额");
    assertRemark(jdbc, "training_material", "content", "培训资料正文");
    assertRemark(jdbc, "todo_action_attachment", "content", "附件二进制内容");
    assertRemark(jdbc, "tenant", "scale", "企业规模");
    assertRemark(jdbc, "training_exam_question", "score", "题目分值");
  }

  private void assertRemark(JdbcTemplate jdbc, String table, String column, String expected) {
    String actual = jdbc.queryForObject("""
        select remarks
        from information_schema.columns
        where lower(table_schema) = 'public'
          and lower(table_name) = ?
          and lower(column_name) = ?
        """, String.class, table, column);
    assertThat(actual).as("%s.%s Chinese remark", table, column).isEqualTo(expected);
  }

  private Resource[] resources(String pattern) throws IOException {
    return new PathMatchingResourcePatternResolver().getResources(pattern);
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:database-column-comments-" + System.nanoTime()
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private record ColumnRemark(String tableName, String columnName, String remarks) {
  }
}
