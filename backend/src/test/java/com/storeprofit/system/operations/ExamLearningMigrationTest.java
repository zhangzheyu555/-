package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ExamLearningMigrationTest {
  @Test
  void h2MigrationCreatesFullLearningAndExamSchema() {
    DataSource dataSource = dataSource();
    Flyway flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .baselineOnMigrate(true)
        .load();

    var result = flyway.migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(result.success).isTrue();
    assertThat(Integer.parseInt(result.targetSchemaVersion)).isGreaterThanOrEqualTo(30);
    assertThat(tableNames(jdbc)).contains(
        "TRAINING_COURSE",
        "TRAINING_COURSE_MATERIAL",
        "TRAINING_EXAM_QUESTION_CATEGORY",
        "TRAINING_EXAM_QUESTION_BANK",
        "TRAINING_EXAM_ATTEMPT_REVIEW",
        "TRAINING_EXAM_ANSWER_REVIEW",
        "TRAINING_EXAM_WRONG_QUESTION"
    );
    Integer categoryCount = jdbc.queryForObject(
        "select count(*) from training_exam_question_category where tenant_id = 1",
        Integer.class
    );
    assertThat(categoryCount).isEqualTo(4);
  }

  private List<String> tableNames(JdbcTemplate jdbc) {
    return jdbc.query(
        "select table_name from information_schema.tables where table_schema = 'PUBLIC'",
        (rs, rowNum) -> rs.getString("table_name")
    );
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:exam_migration;MODE=MySQL;NON_KEYWORDS=MONTH;DATABASE_TO_LOWER=FALSE;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }
}
