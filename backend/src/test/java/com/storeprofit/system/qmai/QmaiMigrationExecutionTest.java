package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QmaiMigrationExecutionTest {
  @Test
  void migratesAnEmptyH2DatabaseThroughVersion60() {
    DataSource dataSource = dataSource();
    var result = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("60")
        .baselineOnMigrate(true)
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(result.success).isTrue();
    assertThat(result.targetSchemaVersion).isEqualTo("60");
    assertThat(jdbc.queryForObject("""
        select count(*) from information_schema.tables
        where table_schema = 'public' and table_name like 'qmai_%'
        """, Integer.class)).isEqualTo(5);
    assertThat(jdbc.queryForObject("""
        select count(*) from information_schema.columns
        where table_schema = 'public' and table_name = 'qmai_platform_config'
          and column_name in ('open_key', 'grant_code', 'console_token', 'cookie', 'password')
        """, Integer.class)).isZero();
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("""
        jdbc:h2:mem:qmai-v60;
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
