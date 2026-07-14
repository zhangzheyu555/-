package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class MultiWarehouseTopologyMigrationTest {
  @Test
  void migratesExactlyThirtyEightStoresAndPreservesLegacyWarehouseHistory() {
    DataSource dataSource = dataSource("thirty-eight");
    migrateTo(dataSource, "42");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    insertStores(jdbc, 38);
    insertAuthorizationFixtures(jdbc);
    insertLegacyWarehouseFixtures(jdbc);

    List<Map<String, Object>> batchesBefore = jdbc.queryForList("""
        select id, item_id, batch_no, quantity, unit_cost
        from warehouse_stock_batch
        where tenant_id = 1
        order by id
        """);
    List<Map<String, Object>> movementsBefore = jdbc.queryForList("""
        select id, item_id, batch_id, quantity_delta, source_type, source_id
        from warehouse_stock_movement
        where tenant_id = 1
        order by id
        """);

    var migrated = migrateTo(dataSource, "43");

    assertThat(migrated.success).isTrue();
    assertThat(migrated.targetSchemaVersion).isEqualTo("43");

    Map<String, Object> central = facility(jdbc, "JZ-CENTRAL");
    Map<String, Object> regional = facility(jdbc, "SD-REGIONAL");
    long centralId = ((Number) central.get("id")).longValue();
    long regionalId = ((Number) regional.get("id")).longValue();

    assertThat(central)
        .containsEntry("name", "荆州总仓")
        .containsEntry("warehouse_type", "CENTRAL")
        .containsEntry("region_code", "JINGZHOU");
    assertThat(((Number) central.get("external_purchase_allowed")).intValue()).isOne();
    assertThat(regional)
        .containsEntry("name", "山东分仓")
        .containsEntry("warehouse_type", "REGIONAL")
        .containsEntry("region_code", "SHANDONG");
    assertThat(((Number) regional.get("parent_warehouse_id")).longValue()).isEqualTo(centralId);
    assertThat(((Number) regional.get("external_purchase_allowed")).intValue()).isZero();

    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_transfer_route
        where tenant_id = 1 and source_warehouse_id = ? and target_warehouse_id = ? and enabled = 1
        """, Integer.class, centralId, regionalId)).isOne();

    assertThat(jdbc.queryForObject("""
        select count(*) from store_branch
        where tenant_id = 1 and region_code = 'JINGZHOU' and supply_warehouse_id = ?
        """, Integer.class, centralId)).isEqualTo(38);
    assertThat(jdbc.queryForObject("""
        select count(*) from store_branch
        where tenant_id = 1 and status = '停用' and supply_warehouse_id = ?
        """, Integer.class, centralId)).isOne();
    assertThat(jdbc.queryForMap("""
        select expected_business_store_count, actual_business_store_count,
               bound_store_count, binding_status
        from warehouse_topology_migration_audit
        where tenant_id = 1 and migration_key = 'V43_JINGZHOU_38_STORE_BINDING'
        """)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "expected_business_store_count", 38,
            "actual_business_store_count", 38,
            "bound_store_count", 38,
            "binding_status", "BOUND_38"));

    assertThat(jdbc.queryForList("""
        select id, item_id, batch_no, quantity, unit_cost
        from warehouse_stock_batch
        where tenant_id = 1
        order by id
        """)).isEqualTo(batchesBefore);
    assertThat(jdbc.queryForList("""
        select id, item_id, batch_id, quantity_delta, source_type, source_id
        from warehouse_stock_movement
        where tenant_id = 1
        order by id
        """)).isEqualTo(movementsBefore);
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_stock_batch
        where tenant_id = 1 and warehouse_id = ?
        """, Integer.class, centralId)).isEqualTo(batchesBefore.size());
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_stock_movement
        where tenant_id = 1 and warehouse_id = ?
        """, Integer.class, centralId)).isEqualTo(movementsBefore.size());
    assertThat(jdbc.queryForObject(
        "select warehouse_id from warehouse_purchase_order where id = 'legacy-purchase'",
        Long.class)).isEqualTo(centralId);
    assertThat(jdbc.queryForObject(
        "select supply_warehouse_id from store_requisition where id = 'legacy-requisition'",
        Long.class)).isEqualTo(centralId);

    BigDecimal batchQuantity = jdbc.queryForObject("""
        select coalesce(sum(quantity), 0) from warehouse_stock_batch
        where tenant_id = 1 and warehouse_id = ?
        """, BigDecimal.class, centralId);
    BigDecimal centralQuantity = jdbc.queryForObject("""
        select coalesce(sum(on_hand_quantity), 0) from warehouse_inventory
        where tenant_id = 1 and warehouse_id = ?
        """, BigDecimal.class, centralId);
    assertThat(centralQuantity).isEqualByComparingTo(batchQuantity);
    assertThat(jdbc.queryForObject("""
        select coalesce(sum(on_hand_quantity + reserved_quantity + in_transit_quantity), 0)
        from warehouse_inventory where tenant_id = 1 and warehouse_id = ?
        """, BigDecimal.class, regionalId)).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_stock_batch
        where tenant_id = 1 and warehouse_id = ?
        """, Integer.class, regionalId)).isZero();

    Long existingItemId = jdbc.queryForObject("""
        select item_id from warehouse_stock_batch
        where tenant_id = 1 and warehouse_id = ?
        order by id limit 1
        """, Long.class, centralId);
    String existingBatchNo = jdbc.queryForObject("""
        select batch_no from warehouse_stock_batch
        where tenant_id = 1 and warehouse_id = ? and item_id = ?
        order by id limit 1
        """, String.class, centralId, existingItemId);
    jdbc.update("""
        insert into warehouse_stock_batch(
          tenant_id, warehouse_id, item_id, batch_no, received_date,
          quantity, reserved_quantity, version, unit_cost, created_at
        ) values (1, ?, ?, ?, current_date, 1, 0, 0, 1, current_timestamp)
        """, regionalId, existingItemId, existingBatchNo);
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_stock_batch
        where tenant_id = 1 and item_id = ? and batch_no = ?
        """, Integer.class, existingItemId, existingBatchNo)).isEqualTo(2);

    assertPermissionsAndScopes(jdbc, centralId, regionalId);
    assertDatabaseGuards(jdbc, centralId, regionalId);
  }

  @Test
  void recordsCountMismatchAndDoesNotGuessOrPartiallyBindStores() {
    DataSource dataSource = dataSource("mismatch");
    migrateTo(dataSource, "42");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    insertStores(jdbc, 2);

    var migrated = migrateTo(dataSource, "43");

    assertThat(migrated.success).isTrue();
    assertThat(jdbc.queryForObject("""
        select count(*) from store_branch
        where tenant_id = 1 and (region_code is not null or supply_warehouse_id is not null)
        """, Integer.class)).isZero();
    Map<String, Object> audit = jdbc.queryForMap("""
        select expected_business_store_count, actual_business_store_count,
               bound_store_count, binding_status, difference_message
        from warehouse_topology_migration_audit where tenant_id = 1
        """);
    assertThat(audit)
        .containsEntry("expected_business_store_count", 38)
        .containsEntry("actual_business_store_count", 2)
        .containsEntry("bound_store_count", 0)
        .containsEntry("binding_status", "SKIPPED_COUNT_MISMATCH");
    assertThat((String) audit.get("difference_message"))
        .contains("期望38家门店")
        .contains("实际2家")
        .contains("未按门店名称或模糊区域更新");
  }

  @Test
  void emptyDatabaseStillMigratesWithTwoFacilitiesAndZeroShandongStock() {
    DataSource dataSource = dataSource("empty");
    var migrated = migrateTo(dataSource, "43");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(migrated.success).isTrue();
    assertThat(migrated.targetSchemaVersion).isEqualTo("43");
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_facility where tenant_id = 1",
        Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("""
        select binding_status from warehouse_topology_migration_audit where tenant_id = 1
        """, String.class)).isEqualTo("SKIPPED_EMPTY");
    assertThat(jdbc.queryForObject("""
        select coalesce(sum(i.on_hand_quantity + i.reserved_quantity + i.in_transit_quantity), 0)
        from warehouse_inventory i
        join warehouse_facility w on w.id = i.warehouse_id
        where w.tenant_id = 1 and w.code = 'SD-REGIONAL'
        """, BigDecimal.class)).isEqualByComparingTo("0");
  }

  private void assertPermissionsAndScopes(
      JdbcTemplate jdbc,
      long centralId,
      long regionalId
  ) {
    List<String> newPermissions = List.of(
        "warehouse.read",
        "warehouse.purchase",
        "warehouse.transfer.request",
        "warehouse.transfer.approve",
        "warehouse.transfer.ship",
        "warehouse.transfer.receive",
        "warehouse.requisition.process",
        "warehouse.configure"
    );
    assertThat(jdbc.queryForList("""
        select permission_code from permission_catalog
        where permission_code in (
          'warehouse.read', 'warehouse.purchase', 'warehouse.transfer.request',
          'warehouse.transfer.approve', 'warehouse.transfer.ship',
          'warehouse.transfer.receive', 'warehouse.requisition.process', 'warehouse.configure'
        ) order by permission_code
        """, String.class)).containsExactlyInAnyOrderElementsOf(newPermissions);
    assertThat(jdbc.queryForList("""
        select permission_code from role_permission
        where tenant_id = 1 and role_code = 'WAREHOUSE'
          and permission_code in (
            'warehouse.read', 'warehouse.purchase', 'warehouse.transfer.request',
            'warehouse.transfer.approve', 'warehouse.transfer.ship',
            'warehouse.transfer.receive', 'warehouse.requisition.process', 'warehouse.configure'
          )
        """, String.class)).containsExactlyInAnyOrder(
            "warehouse.read",
            "warehouse.purchase",
            "warehouse.transfer.request",
            "warehouse.transfer.approve",
            "warehouse.transfer.ship",
            "warehouse.transfer.receive",
            "warehouse.requisition.process",
            "warehouse.configure"
        );

    Map<String, Object> dataScope = jdbc.queryForMap("""
        select scope_type, scope_value_json from user_data_scope
        where tenant_id = 1 and user_id = 9001 and domain_code = 'WAREHOUSE'
        """);
    assertThat(dataScope.get("scope_type")).isEqualTo("WAREHOUSE_LIST");
    String scopeJson = String.valueOf(dataScope.get("scope_value_json"));
    assertThat(scopeJson)
        .contains("\"" + centralId + "\"")
        .contains("\"" + regionalId + "\"");
    assertThat(jdbc.queryForObject("""
        select count(*) from user_permission_override
        where tenant_id = 1 and user_id = 9001
          and permission_code = 'warehouse.central.manage' and effect = 'DENY'
        """, Integer.class)).isOne();
    assertThat(jdbc.queryForList("""
        select permission_code from user_permission_override
        where tenant_id = 1 and user_id = 9001 and effect = 'DENY'
          and permission_code in (
            'warehouse.purchase', 'warehouse.transfer.request',
            'warehouse.transfer.approve', 'warehouse.transfer.ship',
            'warehouse.transfer.receive', 'warehouse.requisition.process',
            'warehouse.configure'
          )
        """, String.class)).containsExactlyInAnyOrder(
            "warehouse.purchase",
            "warehouse.transfer.request",
            "warehouse.transfer.approve",
            "warehouse.transfer.ship",
            "warehouse.transfer.receive",
            "warehouse.requisition.process",
            "warehouse.configure");
    assertThat(jdbc.queryForObject(
        "select permission_version from auth_user where id = 9001",
        Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject(
        "select count(*) from auth_token where user_id in (9001, 9002)",
        Integer.class)).isZero();
  }

  private void assertDatabaseGuards(JdbcTemplate jdbc, long centralId, long regionalId) {
    assertThatThrownBy(() -> jdbc.update("""
        update warehouse_facility set external_purchase_allowed = 1 where id = ?
        """, regionalId)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("""
        update warehouse_inventory set on_hand_quantity = -1
        where tenant_id = 1 and warehouse_id = ?
        """, regionalId)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("""
        insert into warehouse_transfer_order(
          id, tenant_id, transfer_no, source_warehouse_id, target_warehouse_id,
          status, idempotency_key, total_amount
        ) values ('invalid-same-warehouse', 1, 'TR-INVALID', ?, ?, 'DRAFT', 'same', 0)
        """, centralId, centralId)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("""
        insert into user_data_scope(
          tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at
        ) values (1, 9002, 'STORE', 'WAREHOUSE_LIST', '[\"1\"]', current_timestamp)
        """)).isInstanceOf(DataAccessException.class);
  }

  private Map<String, Object> facility(JdbcTemplate jdbc, String code) {
    return jdbc.queryForMap("""
        select id, name, warehouse_type, region_code, parent_warehouse_id,
               external_purchase_allowed, store_supply_allowed, enabled
        from warehouse_facility where tenant_id = 1 and code = ?
        """, code);
  }

  private void insertStores(JdbcTemplate jdbc, int count) {
    for (int index = 1; index <= count; index++) {
      String id = "jz-%02d".formatted(index);
      String code = "JZ%03d".formatted(index);
      String status = index == count ? "停用" : "营业中";
      jdbc.update("""
          insert into store_branch(id, tenant_id, code, name, area, status, created_at)
          values (?, 1, ?, ?, '荆州', ?, current_timestamp)
          """, id, code, "荆州测试门店" + index, status);
    }
  }

  private void insertAuthorizationFixtures(JdbcTemplate jdbc) {
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role,
          enabled, permission_version, created_at
        ) values
          (9001, 1, 'warehouse-v43', 'hash', '现有总仓管理员', 'WAREHOUSE', 1, 1, current_timestamp),
          (9002, 1, 'boss-v43', 'hash', '老板', 'BOSS', 1, 1, current_timestamp)
        """);
    jdbc.update("""
        insert into user_data_scope(
          tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at
        ) values (1, 9001, 'WAREHOUSE', 'CENTRAL_WAREHOUSE', null, current_timestamp)
        """);
    jdbc.update("""
        insert into user_permission_override(
          tenant_id, user_id, permission_code, effect, created_at
        ) values (1, 9001, 'warehouse.central.manage', 'DENY', current_timestamp)
        """);
    jdbc.update("""
        insert into auth_token(
          token, tenant_id, user_id, permission_version, expires_at, created_at
        ) values
          ('warehouse-v43-token', 1, 9001, 1, timestamp '2099-01-01 00:00:00', current_timestamp),
          ('boss-v43-token', 1, 9002, 1, timestamp '2099-01-01 00:00:00', current_timestamp)
        """);
  }

  private void insertLegacyWarehouseFixtures(JdbcTemplate jdbc) {
    Long itemId = jdbc.queryForObject(
        "select min(id) from warehouse_item where tenant_id = 1",
        Long.class);
    Long batchId = jdbc.queryForObject("""
        select min(id) from warehouse_stock_batch where tenant_id = 1 and item_id = ?
        """, Long.class, itemId);
    jdbc.update("""
        insert into warehouse_stock_movement(
          tenant_id, item_id, batch_id, movement_type, quantity_delta,
          source_type, source_id, note, created_at
        ) values (1, ?, ?, 'ADJUST_IN', 1, 'LEGACY_TEST', 'legacy-movement',
          '保留历史流水主键和值', current_timestamp)
        """, itemId, batchId);
    jdbc.update("""
        insert into warehouse_purchase_order(
          id, tenant_id, supplier_id, status, total_amount, note, created_at
        ) values ('legacy-purchase', 1, null, 'DRAFT', 10, '旧采购单', current_timestamp)
        """);
    jdbc.update("""
        insert into store_requisition(
          id, tenant_id, store_id, status, total_amount, note, submitted_at
        ) values ('legacy-requisition', 1, 'jz-01', 'SUBMITTED', 10, '旧叫货单', current_timestamp)
        """);
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
        jdbc:h2:mem:multi-warehouse-topology-%s;
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
