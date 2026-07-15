package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WarehouseReturnReceiveWarehouseSnapshotMigrationTest {
  @Test
  void backfillsHistoricalReturnFromItsWarehouseIdWithoutUsingReceiveDepartment() {
    DataSource dataSource = dataSource("backfill");
    migrateTo(dataSource, "53");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String storeId = ensureStore(jdbc, "snapshot-store");
    long centralWarehouseId = jdbc.queryForObject("""
        select id from warehouse_facility
        where tenant_id = 1 and code = 'JZ-CENTRAL'
        """, Long.class);

    jdbc.update("""
        insert into warehouse_return_order(
          id, tenant_id, warehouse_id, return_no, return_store_id, return_store_name,
          receive_department, status, total_amount, return_date, created_at
        ) values ('legacy-return-snapshot', 1, ?, 'PSTH-LEGACY', ?, '历史门店',
          '伪造收货部门', 'RECEIVED', 0, current_date, current_timestamp)
        """, centralWarehouseId, storeId);

    migrateTo(dataSource, "54");

    assertThat(jdbc.queryForMap("""
        select receive_warehouse_code_snapshot, receive_warehouse_name_snapshot
        from warehouse_return_order
        where tenant_id = 1 and id = 'legacy-return-snapshot'
        """)).containsEntry("receive_warehouse_code_snapshot", "JZ-CENTRAL")
        .containsEntry("receive_warehouse_name_snapshot", "荆州总仓");
    assertThat(jdbc.queryForMap("""
        select failure_code, failure_message
        from warehouse_return_snapshot_backfill_audit
        where migration_key = 'V54_RETURN_RECEIVE_WAREHOUSE_SNAPSHOT'
          and tenant_id = 1 and return_order_id = 'legacy-return-snapshot'
        """)).containsEntry("failure_code", "BACKFILLED")
        .containsEntry("failure_message", "按 warehouse_id 回填收货仓快照。");
  }

  @Test
  void recordsCrossTenantWarehouseAuditAndFailsInsteadOfGuessing() {
    DataSource dataSource = dataSource("invalid-warehouse");
    migrateTo(dataSource, "53");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String storeId = ensureStore(jdbc, "invalid-snapshot-store");
    long centralWarehouseId = jdbc.queryForObject("""
        select id from warehouse_facility
        where tenant_id = 1 and code = 'JZ-CENTRAL'
        """, Long.class);
    jdbc.update("""
        insert into tenant(id, name, industry, scale, status, created_at)
        values (2, 'Snapshot Test Tenant', 'chain_store', 'test', 'ACTIVE', current_timestamp)
        """);
    jdbc.update("""
        insert into warehouse_facility(
          tenant_id, code, name, warehouse_type, region_code, parent_warehouse_id,
          external_purchase_allowed, store_supply_allowed, enabled, created_at
        ) values (2, 'T2-CENTRAL', '其他租户总仓', 'CENTRAL', 'JINGZHOU', null,
          1, 1, 1, current_timestamp)
        """);
    long otherTenantWarehouseId = jdbc.queryForObject("""
        select id from warehouse_facility
        where tenant_id = 2 and code = 'T2-CENTRAL'
        """, Long.class);
    jdbc.update("""
        insert into warehouse_return_order(
          id, tenant_id, warehouse_id, return_no, return_store_id, return_store_name,
          receive_department, status, total_amount, return_date, created_at
        ) values ('invalid-return-snapshot', 1, ?, 'PSTH-INVALID', ?, '历史门店',
          '仓库', 'RECEIVED', 0, current_date, current_timestamp)
        """, otherTenantWarehouseId, storeId);

    assertThatThrownBy(() -> migrateTo(dataSource, "54"))
        .hasStackTraceContaining("chk_v54_unresolved_return_snapshots");

    assertThat(jdbc.queryForMap("""
        select warehouse_id, failure_code, failure_message
        from warehouse_return_snapshot_backfill_audit
        where migration_key = 'V54_RETURN_RECEIVE_WAREHOUSE_SNAPSHOT'
          and tenant_id = 1 and return_order_id = 'invalid-return-snapshot'
        """)).containsEntry("warehouse_id", otherTenantWarehouseId)
        .containsEntry("failure_code", "WAREHOUSE_NOT_FOUND")
        .satisfies(audit -> assertThat(String.valueOf(audit.get("failure_message")))
            .contains("同租户 warehouse_facility"));

    repair(dataSource);
    jdbc.update("""
        update warehouse_return_order
        set warehouse_id = ?
        where tenant_id = 1 and id = 'invalid-return-snapshot'
        """, centralWarehouseId);
    migrateTo(dataSource, "54");

    assertThat(jdbc.queryForMap("""
        select receive_warehouse_code_snapshot, receive_warehouse_name_snapshot
        from warehouse_return_order
        where tenant_id = 1 and id = 'invalid-return-snapshot'
        """)).containsEntry("receive_warehouse_code_snapshot", "JZ-CENTRAL")
        .containsEntry("receive_warehouse_name_snapshot", "荆州总仓");
    assertThat(jdbc.queryForObject("""
        select failure_code
        from warehouse_return_snapshot_backfill_audit
        where migration_key = 'V54_RETURN_RECEIVE_WAREHOUSE_SNAPSHOT'
          and tenant_id = 1 and return_order_id = 'invalid-return-snapshot'
        """, String.class)).isEqualTo("BACKFILLED");
  }

  @Test
  void recordsMissingWarehouseIdAndFailsWithoutGuessing() {
    DataSource dataSource = dataSource("missing-warehouse-id");
    migrateTo(dataSource, "53");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String storeId = ensureStore(jdbc, "missing-warehouse-store");
    jdbc.execute("alter table warehouse_return_order alter column warehouse_id drop not null");

    jdbc.update("""
        insert into warehouse_return_order(
          id, tenant_id, warehouse_id, return_no, return_store_id, return_store_name,
          receive_department, status, total_amount, return_date, created_at
        ) values ('missing-warehouse-id-snapshot', 1, null, 'PSTH-MISSING', ?, '历史门店',
          '页面仓库', 'RECEIVED', 0, current_date, current_timestamp)
        """, storeId);

    assertThatThrownBy(() -> migrateTo(dataSource, "54"))
        .hasStackTraceContaining("chk_v54_unresolved_return_snapshots");
    assertThat(jdbc.queryForMap("""
        select failure_code, failure_message
        from warehouse_return_snapshot_backfill_audit
        where migration_key = 'V54_RETURN_RECEIVE_WAREHOUSE_SNAPSHOT'
          and tenant_id = 1 and return_order_id = 'missing-warehouse-id-snapshot'
        """)).containsEntry("failure_code", "MISSING_WAREHOUSE_ID")
        .satisfies(audit -> assertThat(String.valueOf(audit.get("failure_message")))
            .contains("禁止猜测收货仓"));
  }

  private String ensureStore(JdbcTemplate jdbc, String storeId) {
    jdbc.update("""
        insert into store_branch(
          id, tenant_id, code, name, status, created_at
        ) values (?, 1, ?, '快照迁移测试门店', '营业中', current_timestamp)
        """, storeId, storeId.toUpperCase());
    return storeId;
  }

  private void migrateTo(DataSource dataSource, String version) {
    flyway(dataSource, version)
        .migrate();
  }

  private void repair(DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .baselineOnMigrate(true)
        .load()
        .repair();
  }

  private Flyway flyway(DataSource dataSource, String version) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(version)
        .baselineOnMigrate(true)
        .load();
  }

  private DataSource dataSource(String name) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(("""
        jdbc:h2:mem:return-receive-warehouse-snapshot-%s;
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
