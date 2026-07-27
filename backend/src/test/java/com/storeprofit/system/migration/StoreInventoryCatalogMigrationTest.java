package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class StoreInventoryCatalogMigrationTest {
  private static final String RETIRED_ITEM_CODES = """
      'PD-HC-032',
      'PD-SG-017',
      'PD-SG-018',
      'PD-SG-019',
      'PD-SG-020',
      'PD-SG-021'
      """;

  @Test
  void removesUnpricedCatalogItemsForEveryTenantWithoutDeletingHistoricalCheckLines() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(("""
        jdbc:h2:mem:store-inventory-catalog-%s;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """).formatted(UUID.randomUUID()).replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("106")
        .load()
        .migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String storeId = "V107-HISTORY-STORE";
    jdbc.update("""
        insert into store_branch(id, tenant_id, code, name, status, created_at)
        values (?, 1, 'V107-HISTORY', '历史盘存门店', '营业中', current_timestamp)
        """, storeId);
    jdbc.update("""
        insert into store_inventory_check(
          tenant_id, check_no, store_id, store_name, check_date, status,
          total_amount, created_at
        ) values (1, 'PDC-V107-HISTORY', ?, '历史盘存门店', current_date, 'REVIEWED', 0,
          current_timestamp)
        """, storeId);
    Long historicalCheckId = jdbc.queryForObject("""
        select id
        from store_inventory_check
        where tenant_id = 1 and check_no = 'PDC-V107-HISTORY'
        """, Long.class);
    jdbc.update("""
        insert into store_inventory_check_line(
          tenant_id, check_id, item_name, item_code, category, spec, unit,
          package_quantity, unit_price, unit_price_each, counted_quantity,
          amount, note, created_at
        ) values (
          1, ?, '安心贴', 'PD-HC-032', '耗材', '卷', '卷',
          1, 0, null, 2, 0, 'V107 历史快照保留验证', current_timestamp
        )
        """, historicalCheckId);

    jdbc.update("insert into tenant(id, name) values (2, '第二租户')");
    jdbc.update("""
        insert into store_inventory_item(
          tenant_id, item_code, category, item_name, spec, unit,
          package_quantity, package_price, unit_price, sort_order, enabled,
          source_file, source_sha256, created_at
        )
        select 2, item_code, category, item_name, spec, unit,
               package_quantity, package_price, unit_price, sort_order, enabled,
               source_file, source_sha256, current_timestamp
        from store_inventory_item
        where tenant_id = 1
          and item_code in (%s)
        """.formatted(RETIRED_ITEM_CODES));

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("107")
        .load()
        .migrate();

    assertThat(jdbc.queryForObject(
        "select count(*) from store_inventory_item where tenant_id = 1 and enabled = 1",
        Integer.class)).isEqualTo(97);
    assertThat(jdbc.queryForObject(
        "select count(*) from store_inventory_item where tenant_id = 1 and unit_price is null",
        Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*)
        from store_inventory_item
        where item_code in (%s)
        """.formatted(RETIRED_ITEM_CODES), Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*)
        from store_inventory_check_line
        where tenant_id = 1
          and check_id = ?
          and item_code = 'PD-HC-032'
          and item_name = '安心贴'
        """, Integer.class, historicalCheckId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select unit_price
        from store_inventory_item
        where tenant_id = 1 and item_code = 'PD-YL-031'
        """, BigDecimal.class)).isEqualByComparingTo("0.004000");
    assertThat(jdbc.queryForObject("""
        select source_sha256
        from store_inventory_item
        where tenant_id = 1 and item_code = 'PD-HC-001'
        """, String.class))
        .isEqualTo("a6eb9ae9185e03af3801fea0848831b7d7c703b4bca4563a4952b5d9de5d99b1");
  }
}
