package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InventoryCheckBrandScopeRepositoryTest {
  @Test
  void inventoryQueriesUseBrandRelationAndNeverStoreIdPrefixes() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(("""
        jdbc:h2:mem:inventory-brand-scope-%s;
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
        .load()
        .migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("""
        insert into brand(tenant_id, code, name, color, sort_order, created_at)
        values
          (1, 'INVENTORY_RUGUO', '茹菓', '#000000', 997, current_timestamp),
          (1, 'INVENTORY_OTHER', '霸王茶姬', '#000000', 998, current_timestamp),
          (1, 'INVENTORY_RUGUO_ALIAS', '茹果', '#000000', 999, current_timestamp)
        """);
    Long ruguoBrandId = jdbc.queryForObject(
        "select id from brand where tenant_id = 1 and name = '茹菓'",
        Long.class);
    Long otherBrandId = jdbc.queryForObject(
        "select id from brand where tenant_id = 1 and name = '霸王茶姬'",
        Long.class);
    Long ruguoAliasBrandId = jdbc.queryForObject(
        "select id from brand where tenant_id = 1 and name = '茹果'",
        Long.class);

    insertStore(jdbc, "NO-RG-PREFIX", "标准茹菓门店", ruguoBrandId);
    insertStore(jdbc, "ALIAS-NO-RG-PREFIX", "别名茹果门店", ruguoAliasBrandId);
    insertStore(jdbc, "rg-fake-prefix", "霸王茶姬门店", otherBrandId);
    long standardCheckId = insertCheck(jdbc, "PDC-RG-STANDARD", "NO-RG-PREFIX", "标准茹菓门店");
    long aliasCheckId = insertCheck(jdbc, "PDC-RG-ALIAS", "ALIAS-NO-RG-PREFIX", "别名茹果门店");
    long otherBrandCheckId =
        insertCheck(jdbc, "PDC-OTHER-BRAND", "rg-fake-prefix", "霸王茶姬门店");
    long archivedCancelledCheckId =
        insertCheck(jdbc, "PDC-RG-CANCELLED", "NO-RG-PREFIX", "标准茹菓门店", "CANCELLED");

    OperationsBusinessRepository repository =
        new OperationsBusinessRepository(jdbc, new ObjectMapper());
    assertThat(repository.reviewInventoryCheck(
        1L, standardCheckId, 501L, "财务负责人", "FINANCE")).isTrue();

    assertThat(repository.inventoryChecks(1L, null))
        .extracting(OperationsBusinessModels.InventoryCheckResponse::checkNo)
        .contains("PDC-RG-STANDARD", "PDC-RG-ALIAS")
        .doesNotContain("PDC-OTHER-BRAND", "PDC-RG-CANCELLED");
    assertThat(repository.inventoryChecks(
        1L,
        null,
        Set.of("NO-RG-PREFIX", "ALIAS-NO-RG-PREFIX", "rg-fake-prefix")))
        .extracting(OperationsBusinessModels.InventoryCheckResponse::checkNo)
        .containsExactlyInAnyOrder("PDC-RG-STANDARD", "PDC-RG-ALIAS");
    assertThat(repository.inventoryCheck(1L, standardCheckId))
        .get()
        .satisfies(reviewed -> {
          assertThat(reviewed.status()).isEqualTo("REVIEWED");
          assertThat(reviewed.reviewedBy()).isEqualTo(501L);
          assertThat(reviewed.reviewedByName()).isEqualTo("财务负责人");
          assertThat(reviewed.reviewedByRole()).isEqualTo("FINANCE");
          assertThat(reviewed.reviewedByRoleLabel()).isEqualTo("财务");
          assertThat(reviewed.reviewedAt()).isNotBlank();
        });
    assertThat(repository.inventoryCheck(1L, aliasCheckId)).isPresent();
    assertThat(repository.inventoryCheck(1L, otherBrandCheckId)).isEmpty();
    assertThat(repository.inventoryCheck(1L, archivedCancelledCheckId)).isEmpty();
    assertThat(repository.inventoryStoreName(1L, "NO-RG-PREFIX"))
        .contains("标准茹菓门店");
    assertThat(repository.inventoryStoreName(1L, "ALIAS-NO-RG-PREFIX"))
        .contains("别名茹果门店");
    assertThat(repository.inventoryStoreName(1L, "rg-fake-prefix")).isEmpty();
  }

  private void insertStore(JdbcTemplate jdbc, String id, String name, long brandId) {
    jdbc.update("""
        insert into store_branch(
          id, tenant_id, brand_id, code, name, status, created_at
        ) values (?, 1, ?, ?, ?, '营业中', current_timestamp)
        """, id, brandId, id, name);
  }

  private long insertCheck(
      JdbcTemplate jdbc,
      String checkNo,
      String storeId,
      String storeName
  ) {
    return insertCheck(jdbc, checkNo, storeId, storeName, "SUBMITTED");
  }

  private long insertCheck(
      JdbcTemplate jdbc,
      String checkNo,
      String storeId,
      String storeName,
      String status
  ) {
    jdbc.update("""
        insert into store_inventory_check(
          tenant_id, check_no, store_id, store_name, check_date, status,
          total_amount, created_at, updated_at
        ) values (1, ?, ?, ?, current_date, ?, 0, current_timestamp, current_timestamp)
        """, checkNo, storeId, storeName, status);
    return jdbc.queryForObject(
        "select id from store_inventory_check where tenant_id = 1 and check_no = ?",
        Long.class,
        checkNo);
  }
}
