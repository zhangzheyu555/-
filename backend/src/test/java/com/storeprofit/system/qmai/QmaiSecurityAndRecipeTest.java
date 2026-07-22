package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.BusinessException;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QmaiSecurityAndRecipeTest {
  @Test
  void encryptsPersistedCredentialAndNeverReusesLegacyPlainText() {
    QmaiProperties properties = new QmaiProperties();
    properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
    QmaiCredentialCipher cipher = new QmaiCredentialCipher(properties);

    String encrypted = cipher.encrypt("synthetic-secret");

    assertThat(encrypted).startsWith("enc:v1:").doesNotContain("synthetic-secret");
    assertThat(cipher.decrypt(encrypted)).isEqualTo("synthetic-secret");
    assertThat(cipher.decrypt("legacy-clear-text")).isEmpty();
  }

  @Test
  void blocksExternalOutboundUnlessExplicitlyLiveAndAllowsOnlyLoopbackMock() {
    QmaiProperties properties = new QmaiProperties();
    QmaiOutboundPolicy policy = new QmaiOutboundPolicy(properties);

    assertThatThrownBy(() -> policy.requireAllowed("https://openapi.qmai.cn/test"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode()).isEqualTo("QMAI_OUTBOUND_BLOCKED");
    properties.setOutboundMode("MOCK");
    policy.requireAllowed("http://127.0.0.1:19090/qmai");
    assertThatThrownBy(() -> policy.requireAllowed("https://openapi.qmai.cn/test"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void calculatesFruitGrossWeightWithBigDecimalSnapshot() {
    QmaiRecipeCalculationService service = new QmaiRecipeCalculationService();
    QmaiRecipeCalculationService.CalculationSnapshot result = service.calculate(
        new QmaiRecipeCalculationService.CalculationRequest(List.of(
            new QmaiRecipeCalculationService.ProductInput("合成饮品", new BigDecimal("2.5"), List.of(
                new QmaiRecipeCalculationService.IngredientInput("芒果肉", "芒果", new BigDecimal("100"), "FLESH", new BigDecimal("0.5")),
                new QmaiRecipeCalculationService.IngredientInput("柠檬汁", "柠檬", new BigDecimal("10"), "JUICE", new BigDecimal("1.2")))))));

    assertThat(result.totalCups()).isEqualByComparingTo("2.500");
    assertThat(result.fruits()).extracting(QmaiRecipeCalculationService.FruitUsage::fruit)
        .containsExactly("芒果", "柠檬");
    assertThat(result.fruits().get(0).rawGrams()).isEqualByComparingTo("500.000");
    assertThat(result.fruits().get(1).rawGrams()).isEqualByComparingTo("30.000");
  }

  @Test
  void operatingSnapshotsNeverReadOtherTenantOrUnassignedStore() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:qmai-snapshot;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table qmai_daily_sales (tenant_id bigint, brand_code varchar(40), store_id varchar(64),
          business_date date, source_row_count int, received_amount decimal(18,2),
          refund_amount decimal(18,2), cost_amount decimal(18,2))
        """);
    jdbc.execute("""
        create table qmai_product_sales (tenant_id bigint, brand_code varchar(40), store_id varchar(64),
          business_date date, item_name varchar(300), category_name varchar(160), quantity decimal(18,3),
          refund_quantity decimal(18,3), received_amount decimal(18,2), refund_amount decimal(18,2))
        """);
    jdbc.update("insert into qmai_daily_sales values (1, 'ruguo', 's1', '2026-07-01', 3, 100, 1, 20)");
    jdbc.update("insert into qmai_daily_sales values (1, 'ruguo', 's2', '2026-07-01', 9, 999, 0, 1)");
    jdbc.update("insert into qmai_daily_sales values (2, 'ruguo', 's1', '2026-07-01', 7, 777, 0, 1)");
    jdbc.update("insert into qmai_product_sales values (1, 'ruguo', 's1', '2026-07-01', '合成饮品', '饮品', 2, 0, 100, 1)");
    jdbc.update("insert into qmai_product_sales values (2, 'ruguo', 's1', '2026-07-01', '其他租户', '饮品', 7, 0, 777, 0)");
    QmaiOperatingDataRepository repository = new QmaiOperatingDataRepository(jdbc);

    assertThat(repository.revenue(1L, "ruguo", java.time.LocalDate.parse("2026-07-01"),
        java.time.LocalDate.parse("2026-07-31"), List.of("s1")))
        .singleElement().satisfies(row -> {
          assertThat(row.storeId()).isEqualTo("s1");
          assertThat(row.revenue()).isEqualByComparingTo("100.00");
        });
    assertThat(repository.products(1L, "ruguo", java.time.LocalDate.parse("2026-07-01"),
        java.time.LocalDate.parse("2026-07-31"), List.of("s1")))
        .singleElement().extracting(QmaiOperatingDataRepository.ProductRow::itemName).isEqualTo("合成饮品");
  }

  @Test
  void serverOwnedRecipeCatalogUsesOnlyMatchingTenantAndBrandSnapshots() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:qmai-recipe-scope;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table qmai_product_sales (tenant_id bigint, brand_code varchar(40), store_id varchar(64),
          business_date date, item_name varchar(300), category_name varchar(160), quantity decimal(18,3),
          refund_quantity decimal(18,3), received_amount decimal(18,2), refund_amount decimal(18,2))
        """);
    jdbc.execute("""
        create table qmai_recipe_definition (id bigint primary key, tenant_id bigint, brand_code varchar(40),
          product_name varchar(300), active int)
        """);
    jdbc.execute("""
        create table qmai_recipe_ingredient (id bigint primary key, recipe_id bigint, material_name varchar(300),
          fruit_name varchar(160), grams_per_cup decimal(18,3), conversion_kind varchar(16),
          conversion_factor decimal(18,6), sort_order int)
        """);
    jdbc.update("insert into qmai_product_sales values (1, 'ruguo', 's1', '2026-07-01', '合成芒果饮', '饮品', 2.5, 0, 30, 0)");
    jdbc.update("insert into qmai_product_sales values (1, 'other-brand', 's1', '2026-07-01', '合成芒果饮', '饮品', 99, 0, 0, 0)");
    jdbc.update("insert into qmai_product_sales values (2, 'ruguo', 's1', '2026-07-01', '合成芒果饮', '饮品', 88, 0, 0, 0)");
    jdbc.update("insert into qmai_recipe_definition values (1, 1, 'ruguo', '合成芒果饮', 1)");
    jdbc.update("insert into qmai_recipe_definition values (2, 2, 'ruguo', '合成芒果饮', 1)");
    jdbc.update("insert into qmai_recipe_ingredient values (1, 1, '芒果肉', '芒果', 100, 'FLESH', 0.5, 1)");
    jdbc.update("insert into qmai_recipe_ingredient values (2, 2, '跨租户芒果肉', '芒果', 999, 'FLESH', 0.5, 1)");

    QmaiRecipeSnapshotService service = new QmaiRecipeSnapshotService(
        new QmaiOperatingDataRepository(jdbc), new QmaiRecipeCatalogRepository(jdbc),
        new QmaiRecipeCalculationService());

    QmaiRecipeSnapshotService.Snapshot result = service.monthly(1L, "ruguo", "2026-07", List.of("s1"));

    assertThat(result.matchedProductCount()).isEqualTo(1);
    assertThat(result.calculation().totalCups()).isEqualByComparingTo("2.500");
    assertThat(result.calculation().fruits()).singleElement().satisfies(fruit -> {
      assertThat(fruit.fruit()).isEqualTo("芒果");
      assertThat(fruit.netGrams()).isEqualByComparingTo("250.000");
      assertThat(fruit.rawGrams()).isEqualByComparingTo("500.000");
    });
  }

  @Test
  void h2MigrationCreatesEncryptedCredentialCompatibleConfigurationTable() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:qmai-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2")
        .target("77").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
        + "where table_name = 'qmai_platform_config' and column_name = 'console_token'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
        + "where table_name = 'qmai_recipe_definition'", Integer.class)).isEqualTo(1);
  }
}
