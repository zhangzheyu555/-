package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LegacySeedCleanupMigrationTest {
  @Test
  void removesUntouchedV4AndV7SeedsFromAnEmptyDatabase() {
    DataSource dataSource = dataSource("fresh");

    var migrated = migrateTo(dataSource, "59");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(migrated.success).isTrue();
    assertThat(migrated.targetSchemaVersion).isEqualTo("59");
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_item where tenant_id = 1", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_stock_batch where tenant_id = 1", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_inventory where tenant_id = 1", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_supplier
        where tenant_id = 1 and name = '总部默认供应商'
        """, Integer.class)).isZero();
  }

  @Test
  void preservesSeedLikeRecordsWhenTheyHaveBusinessReferences() {
    DataSource dataSource = dataSource("referenced");
    migrateTo(dataSource, "56");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    long itemId = jdbc.queryForObject("""
        select id from warehouse_item
        where tenant_id = 1 and code = 'CUP-700'
        """, Long.class);
    long batchId = jdbc.queryForObject("""
        select id from warehouse_stock_batch
        where tenant_id = 1 and item_id = ? and batch_no = 'CUP-700-SEED'
        """, Long.class, itemId);
    long centralWarehouseId = jdbc.queryForObject("""
        select id from warehouse_facility
        where tenant_id = 1 and code = 'JZ-CENTRAL'
        """, Long.class);
    long supplierId = jdbc.queryForObject("""
        select id from warehouse_supplier
        where tenant_id = 1 and name = '总部默认供应商'
        """, Long.class);

    jdbc.update("""
        insert into warehouse_stock_movement(
          tenant_id, warehouse_id, item_id, batch_id, movement_type,
          quantity_delta, reserved_quantity_delta, in_transit_quantity_delta,
          unit_cost, source_type, source_id, note, created_at
        ) values (1, ?, ?, ?, 'ADJUST_IN', 1, 0, 0, 138,
          'TEST', 'v59-real-flow', '真实业务流水', current_timestamp)
        """, centralWarehouseId, itemId, batchId);
    jdbc.update("""
        insert into warehouse_purchase_order(
          id, tenant_id, warehouse_id, supplier_id, status, total_amount, created_at
        ) values ('v59-real-purchase', 1, ?, ?, 'DRAFT', 0, current_timestamp)
        """, centralWarehouseId, supplierId);

    var migrated = migrateTo(dataSource, "59");

    assertThat(migrated.success).isTrue();
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_item where id = ?", Integer.class, itemId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_stock_batch where id = ?", Integer.class, batchId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_supplier where id = ?", Integer.class, supplierId)).isOne();
  }

  @Test
  void preservesASeedLikeItemAfterManualMaintenance() {
    DataSource dataSource = dataSource("manual-edit");
    migrateTo(dataSource, "56");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("""
        update warehouse_item
        set item_description = '人工维护的物料说明'
        where tenant_id = 1 and code = 'COCONUT-POWDER'
        """);

    var migrated = migrateTo(dataSource, "59");

    assertThat(migrated.success).isTrue();
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_item
        where tenant_id = 1 and code = 'COCONUT-POWDER'
        """, Integer.class)).isOne();
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
        jdbc:h2:mem:legacy-seed-cleanup-%s;
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
