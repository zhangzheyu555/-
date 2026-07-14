package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class WarehouseRequestDedupMigrationTest {
  @Test
  void createsTenantScopedDeduplicationGuardAndBusinessLookupIndex() {
    DataSource dataSource = dataSource();
    var migrated = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("29")
        .baselineOnMigrate(true)
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(migrated.success).isTrue();
    assertThat(migrated.targetSchemaVersion).isEqualTo("29");

    jdbc.update("""
        insert into warehouse_request_dedup(
          tenant_id, request_type, request_key, business_id
        ) values (1, 'TRANSFER_SHIP', 'ship-once', 'transfer-1')
        """);

    assertThat(jdbc.queryForObject("""
        select count(*) from information_schema.indexes
        where table_name = 'warehouse_request_dedup'
          and index_name = 'idx_warehouse_request_business'
        """, Integer.class)).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_request_dedup
        where tenant_id = 1 and request_type = 'TRANSFER_SHIP'
          and request_key = 'ship-once' and created_at is not null
        """, Integer.class)).isOne();

    assertThatThrownBy(() -> jdbc.update("""
        insert into warehouse_request_dedup(
          tenant_id, request_type, request_key, business_id
        ) values (1, 'TRANSFER_SHIP', 'ship-once', 'transfer-2')
        """))
        .isInstanceOf(DataAccessException.class);

    jdbc.update("""
        insert into warehouse_request_dedup(
          tenant_id, request_type, request_key, business_id
        ) values (1, 'TRANSFER_RECEIVE', 'ship-once', 'transfer-1')
        """);
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_request_dedup", Integer.class)).isEqualTo(2);

    assertThatThrownBy(() -> jdbc.update("""
        insert into warehouse_request_dedup(
          tenant_id, request_type, request_key, business_id
        ) values (999, 'TRANSFER_SHIP', 'unknown-tenant', 'transfer-3')
        """))
        .isInstanceOf(DataAccessException.class);
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("""
        jdbc:h2:mem:warehouse-request-dedup-migration;
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
