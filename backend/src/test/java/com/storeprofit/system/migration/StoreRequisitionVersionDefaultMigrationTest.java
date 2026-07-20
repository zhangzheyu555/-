package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class StoreRequisitionVersionDefaultMigrationTest {

  @Test
  void migrationsRestoreRequisitionDefaultsRemovedByCommentMigration() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(("""
        jdbc:h2:mem:store-requisition-version-%s;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """).formatted(UUID.randomUUID()).replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");

    Flyway flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("71")
        .load();
    flyway.migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Long supplyWarehouseId = jdbc.queryForObject(
        "select id from warehouse_facility where tenant_id = 1 order by id limit 1",
        Long.class);
    String storeId = "version-test-store";
    jdbc.update("""
        insert into store_branch(
          id, tenant_id, code, name, status, region_code, supply_warehouse_id, created_at
        ) values (?, 1, 'VERSION-TEST', '版本测试门店', '营业中', 'JINGZHOU', ?, current_timestamp)
        """, storeId, supplyWarehouseId);
    jdbc.execute("alter table store_requisition alter column version drop default");

    assertThatThrownBy(() -> insertRequisition(
        jdbc, "REQ-BEFORE-V72", storeId, supplyWarehouseId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(error -> assertThat(rootCauseMessage(error)).containsIgnoringCase("version"));

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("72")
        .load()
        .migrate();

    insertRequisition(jdbc, "REQ-AFTER-V72", storeId, supplyWarehouseId);
    assertThat(jdbc.queryForObject(
        "select version from store_requisition where tenant_id = 1 and id = 'REQ-AFTER-V72'",
        Long.class)).isZero();

    jdbc.update("""
        insert into warehouse_item(
          tenant_id, code, name, unit, unit_price, cups_per_unit, daily_usage_estimate,
          min_stock_days, max_stock_days, active, min_stock_quantity, alert_enabled,
          sort_order, created_at
        ) values (1, 'VERSION-ITEM', '默认值测试物料', '件', 1, 0, 0, 7, 60, 1, 0, 1, 1,
          current_timestamp)
        """);
    Long itemId = jdbc.queryForObject(
        "select id from warehouse_item where tenant_id = 1 and code = 'VERSION-ITEM'",
        Long.class);
    jdbc.execute("alter table store_requisition_line alter column shipped_quantity drop default");
    assertThatThrownBy(() -> insertRequisitionLine(jdbc, "REQ-AFTER-V72", itemId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(error -> assertThat(rootCauseMessage(error))
            .containsIgnoringCase("shipped_quantity"));

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("73")
        .load()
        .migrate();

    insertRequisitionLine(jdbc, "REQ-AFTER-V72", itemId);
    assertThat(jdbc.queryForObject("""
        select shipped_quantity from store_requisition_line
        where tenant_id = 1 and requisition_id = 'REQ-AFTER-V72'
        """, Long.class)).isZero();
  }

  private void insertRequisition(
      JdbcTemplate jdbc,
      String id,
      String storeId,
      long supplyWarehouseId
  ) {
    jdbc.update("""
        insert into store_requisition(
          id, tenant_id, store_id, supply_warehouse_id, status, total_amount, submitted_at
        ) values (?, 1, ?, ?, 'SUBMITTED', 0, current_timestamp)
        """, id, storeId, supplyWarehouseId);
  }

  private String rootCauseMessage(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root.getMessage();
  }

  private void insertRequisitionLine(JdbcTemplate jdbc, String requisitionId, long itemId) {
    jdbc.update("""
        insert into store_requisition_line(
          tenant_id, requisition_id, item_id, requested_quantity, unit_price, amount
        ) values (1, ?, ?, 1, 1, 1)
        """, requisitionId, itemId);
  }
}
