package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  void liveOutboundUsesSeparateExactOfficialHostsAndRejectsRedirectPrimitives() {
    QmaiProperties properties = new QmaiProperties();
    properties.setOutboundMode("LIVE");
    QmaiOutboundPolicy policy = new QmaiOutboundPolicy(properties);

    policy.requireAllowed("https://openapi.qmai.cn/v3/org/shop/getShopList");
    policy.requireConsoleAllowed(
        "https://inapi.qmai.cn/gw/data-center/trd/pc/list-business-income");
    policy.requireValidBaseUrl("https://openapi.qmai.cn");

    for (String blocked : List.of(
        "http://openapi.qmai.cn/v3/org/shop/getShopList",
        "https://openapi.qmai.cn.evil.example/v3/org/shop/getShopList",
        "https://user@openapi.qmai.cn/v3/org/shop/getShopList",
        "https://openapi.qmai.cn:8443/v3/org/shop/getShopList",
        "https://inapi.qmai.cn/gw/data-center/trd/pc/list-business-income")) {
      assertThatThrownBy(() -> policy.requireAllowed(blocked))
          .isInstanceOf(BusinessException.class);
    }
    assertThatThrownBy(() -> policy.requireConsoleAllowed(
        "https://openapi.qmai.cn/v3/org/shop/getShopList"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> policy.requireValidBaseUrl("https://inapi.qmai.cn"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("QMAI_BASE_URL_INVALID"));
  }

  @Test
  void generalOpenApiAndConsoleProbesAllowOnlyRegisteredReadOnlyPaths() {
    QmaiOrderService orders = new QmaiOrderService(null, null);
    QmaiConsoleService console = new QmaiConsoleService(null, null);

    assertThatThrownBy(() -> orders.probe(
        1L, "ruguo", "v3/crm/coupon/writeOffCoupon", Map.of("orderNo", "x")))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("QMAI_PROBE_PATH_BLOCKED"));
    assertThatThrownBy(() -> orders.probe(
        1L, "ruguo", "v3/newPattern/scmApiserver/post/warehouse-product/list", Map.of()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("QMAI_PROBE_PATH_BLOCKED"));
    assertThatThrownBy(() -> console.probe(
        1L, "ruguo", "seller/account/update", Map.of("role", "BOSS")))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("QMAI_CONSOLE_PROBE_PATH_BLOCKED"));

    QmaiConfigService config = mock(QmaiConfigService.class);
    when(config.resolve(1L, "ruguo")).thenReturn(new QmaiConfigService.EffectiveConfig(
        "", "", "", "https://openapi.qmai.cn", "1.0", java.time.Duration.ofSeconds(1),
        List.of(), "", "", "", "TEST"));
    QmaiOrderService allowedOrders = new QmaiOrderService(config, null);
    QmaiConsoleService allowedConsole = new QmaiConsoleService(config, null);
    assertThat(allowedOrders.probe(
        1L, "ruguo", "/v3/org/shop/getShopList", Map.of()).get("ok")).isEqualTo(false);
    assertThat(allowedOrders.probe(
        1L, "ruguo", "v3/dataone/item/store/turnover", Map.of()).get("ok")).isEqualTo(false);
    assertThat(allowedConsole.probe(
        1L, "ruguo", "/data-center/trd/pc/list-business-income", Map.of()).get("ok"))
        .isEqualTo(false);
  }

  @Test
  void turnoverParserAcceptsLegalEmptyListAndRejectsMalformedMoneyContract() {
    QmaiOrderService service = new QmaiOrderService(null, null);
    assertThat(service.turnoverRows(Map.of(
        "data", Map.of("resultList", List.of())))).isEmpty();

    Map<String, Object> valid = new LinkedHashMap<>();
    valid.put("receivableAmount", "12.30");
    valid.put("receivedAmount", 12.3);
    valid.put("costAmount", BigDecimal.ZERO);
    valid.put("refundAmount", "0");
    assertThat(service.turnoverRows(Map.of(
        "data", Map.of("resultList", List.of(valid))))).singleElement();

    for (Map<String, Object> malformed : List.<Map<String, Object>>of(
        Map.of(),
        Map.of("data", (Object) Map.of()),
        Map.of("data", (Object) Map.of("resultList", "not-a-list")),
        Map.of("data", (Object) Map.of("resultList", List.of("not-a-row"))))) {
      assertThatThrownBy(() -> service.turnoverRows(malformed))
          .isInstanceOf(IllegalStateException.class);
    }
    Map<String, Object> missingAmount = new LinkedHashMap<>(valid);
    missingAmount.remove("refundAmount");
    assertThatThrownBy(() -> service.turnoverRows(Map.of(
        "data", Map.of("resultList", List.of(missingAmount)))))
        .isInstanceOf(IllegalStateException.class);
    Map<String, Object> invalidAmount = new LinkedHashMap<>(valid);
    invalidAmount.put("receivedAmount", "not-money");
    assertThatThrownBy(() -> service.turnoverRows(Map.of(
        "data", Map.of("resultList", List.of(invalidAmount)))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void calculatesFruitGrossWeightWithBigDecimalSnapshot() {
    QmaiRecipeCalculationService service = new QmaiRecipeCalculationService();
    QmaiRecipeCalculationService.CalculationSnapshot result = service.calculate(
        new QmaiRecipeCalculationService.CalculationRequest(List.of(
            new QmaiRecipeCalculationService.ProductInput("合成饮品", new BigDecimal("2.5"), List.of(
                new QmaiRecipeCalculationService.IngredientInput("芒果肉", "芒果", new BigDecimal("100"), "FLESH", new BigDecimal("0.5")),
                new QmaiRecipeCalculationService.IngredientInput("柠檬汁", "柠檬", new BigDecimal("10"), "JUICE", new BigDecimal("1.2")),
                new QmaiRecipeCalculationService.IngredientInput("椰奶", null, new BigDecimal("10"), "NONE", null))))));

    assertThat(result.totalCups()).isEqualByComparingTo("2.500");
    assertThat(result.fruits()).extracting(QmaiRecipeCalculationService.FruitUsage::fruit)
        .containsExactly("芒果", "柠檬");
    assertThat(result.fruits().get(0).rawGrams()).isEqualByComparingTo("500.000");
    assertThat(result.fruits().get(1).rawGrams()).isEqualByComparingTo("30.000");
    assertThat(result.otherMaterials()).singleElement().satisfies(material -> {
      assertThat(material.materialName()).isEqualTo("椰奶");
      assertThat(material.grams()).isEqualByComparingTo("25.000");
    });
  }

  @Test
  void recipeSnapshotUsesAveragedSingleCupRecipesAliasesAndDrinkScope() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:qmai-recipe-original-rules;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create table store_branch (tenant_id bigint, id varchar(64), name varchar(160))");
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
    jdbc.update("insert into store_branch values (1, 's1', '一店'), (1, 's2', '二店')");
    jdbc.update("""
        insert into qmai_product_sales values
          (1, 'ruguo', 's1', '2026-07-01', '牛油果甘露', '饮品', 2, 0, 0, 0),
          (1, 'ruguo', 's2', '2026-07-02', '牛油果甘露', '饮品', 3, 0, 0, 0),
          (1, 'ruguo', 's1', '2026-07-02', '牛油果甘露 - 大杯', '饮品', 1.5, 0, 0, 0),
          (1, 'ruguo', 's1', '2026-07-02', '大颗粒芒果冰茶', '饮品', 2, 0, 0, 0),
          (1, 'ruguo', 's1', '2026-07-02', '牛油果追芒芒', '饮品', 1, 0, 0, 0),
          (1, 'ruguo', 's1', '2026-07-02', '打包费', '费用', 99, 0, 0, 0),
          (1, 'ruguo', 's1', '2026-07-02', '未收录水果茶', '饮品', 4, 0, 0, 0)
        """);
    jdbc.update("""
        insert into qmai_recipe_definition values
          (1, 1, 'ruguo', '牛油果甘露', 1),
          (2, 1, 'ruguo', '大颗芒果冰茶', 1),
          (3, 1, 'ruguo', '牛油果芒果', 1)
        """);
    jdbc.update("""
        insert into qmai_recipe_ingredient values
          (1, 1, '芒果粒', '芒果', 50, 'FLESH', 0.530303, 1),
          (2, 1, '牛油果', '牛油果', 90, 'FLESH', 0.78, 2),
          (3, 1, '椰奶', null, 190, 'NONE', null, 3),
          (4, 2, '芒果', '芒果', 300, 'FLESH', 0.530303, 1),
          (5, 3, '芒果肉', '芒果', 90, 'FLESH', 0.530303, 1)
        """);

    QmaiRecipeSnapshotService service = new QmaiRecipeSnapshotService(
        new QmaiOperatingDataRepository(jdbc), new QmaiRecipeCatalogRepository(jdbc),
        new QmaiRecipeCalculationService());

    QmaiRecipeSnapshotService.Snapshot result = service.monthly(
        1L, "ruguo", "2026-07", List.of("s1", "s2"));

    assertThat(result.matchedProductCount()).isEqualTo(4);
    assertThat(result.calculation().totalCups()).isEqualByComparingTo("9.500");
    assertThat(result.matchedProducts()).hasSize(3);
    assertThat(result.matchedProducts())
        .filteredOn(product -> "牛油果甘露".equals(product.recipeName()))
        .singleElement()
        .extracting(QmaiRecipeSnapshotService.MatchedProduct::cups)
        .isEqualTo(new BigDecimal("6.500"));
    assertThat(result.matchedProducts())
        .filteredOn(product -> "大颗芒果冰茶".equals(product.recipeName()))
        .singleElement()
        .extracting(QmaiRecipeSnapshotService.MatchedProduct::cups)
        .isEqualTo(new BigDecimal("2.000"));
    assertThat(result.matchedProducts())
        .filteredOn(product -> "牛油果芒果".equals(product.recipeName()))
        .singleElement()
        .extracting(QmaiRecipeSnapshotService.MatchedProduct::cups)
        .isEqualTo(new BigDecimal("1.000"));
    assertThat(result.calculation().fruits())
        .filteredOn(fruit -> "牛油果".equals(fruit.fruit()))
        .singleElement()
        .extracting(QmaiRecipeCalculationService.FruitUsage::netGrams)
        .isEqualTo(new BigDecimal("585.000"));
    assertThat(result.calculation().otherMaterials()).singleElement().satisfies(material -> {
      assertThat(material.materialName()).isEqualTo("椰奶");
      assertThat(material.grams()).isEqualByComparingTo("1235.000");
    });
    assertThat(result.unmatchedProducts()).singleElement().satisfies(product -> {
      assertThat(product.name()).isEqualTo("未收录水果茶");
      assertThat(product.cups()).isEqualByComparingTo("4.000");
    });
  }

  @Test
  void operatingSnapshotsNeverReadOtherTenantOrUnassignedStore() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:qmai-snapshot;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create table store_branch (tenant_id bigint, id varchar(64), name varchar(160))");
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
    jdbc.execute("create table store_branch (tenant_id bigint, id varchar(64), name varchar(160))");
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

  @Test
  void latestH2MigrationAllowsOriginalNonFruitMaterialRules() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:qmai-recipe-latest-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE");
    dataSource.setUser("sa");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2")
        .target("113.20260728104000001").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    jdbc.update("""
        insert into qmai_recipe_definition (tenant_id, brand_code, product_name, active)
        values (1, 'ruguo', '非水果物料迁移验证', 1)
        """);
    jdbc.update("""
        insert into qmai_recipe_ingredient
          (recipe_id, material_name, fruit_name, grams_per_cup, conversion_kind, sort_order)
        select id, '椰奶', null, 100, 'NONE', 1
        from qmai_recipe_definition
        where tenant_id = 1 and brand_code = 'ruguo' and product_name = '非水果物料迁移验证'
        """);

    assertThat(jdbc.queryForObject("""
        select count(*) from qmai_recipe_ingredient
        where material_name = '椰奶' and fruit_name is null and conversion_kind = 'NONE'
        """, Integer.class)).isEqualTo(1);
  }
}
