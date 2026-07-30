package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WarehousePurchaseUnitSnapshotMigrationTest {

  @Test
  void latestMigrationAddsPurchaseUnitSnapshotsToOrderLines() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("""
        jdbc:h2:mem:warehouse-purchase-unit-snapshots;
        MODE=MySQL;
        NON_KEYWORDS=MONTH;
        DATABASE_TO_LOWER=FALSE;
        DB_CLOSE_DELAY=-1
        """.replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");

    var result = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .load()
        .migrate();

    List<String> columns = new JdbcTemplate(dataSource).queryForList("""
        select column_name
        from information_schema.columns
        where table_schema = 'PUBLIC'
          and table_name = 'WAREHOUSE_PURCHASE_ORDER_LINE'
        """, String.class);

    assertThat(result.success).isTrue();
    assertThat(columns).contains(
        "ITEM_CODE_SNAPSHOT",
        "ITEM_NAME_SNAPSHOT",
        "SPEC_SNAPSHOT",
        "PURCHASE_UNIT_SNAPSHOT",
        "STOCK_UNIT_SNAPSHOT",
        "UNIT_CONVERSION_SNAPSHOT",
        "CONVERSION_FACTOR"
    );
  }
}
