package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

class QmaiSyncServiceTest {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private JdbcTemplate jdbc;
  private QmaiSyncRepository repository;
  private QmaiConfigService configService;
  private QmaiOrderService orderService;
  private QmaiSyncService service;
  private DriverManagerDataSource dataSource;

  @BeforeEach
  void setUp() {
    dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:qmai-sync-" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(dataSource);
    createSchema(jdbc);
    jdbc.update("insert into tenant values (1, 'ACTIVE')");
    jdbc.update("insert into store_branch values (1, 's1', '茹果门店')");
    repository = new QmaiSyncRepository(jdbc);
    configService = mock(QmaiConfigService.class);
    orderService = mock(QmaiOrderService.class);
    when(configService.resolve(1L, "ruguo")).thenReturn(config(List.of("285275:茹果门店:s1")));
    service = new QmaiSyncService(configService, orderService, repository, Runnable::run);
  }

  @Test
  void currentMonthStopsAtShanghaiYesterdayAndHistoricalMonthIncludesEveryDay() {
    LocalDate today = LocalDate.now(ZONE);
    assumeTrue(today.getDayOfMonth() > 1);
    LocalDate yesterday = today.minusDays(1);
    when(orderService.fetchDailyShop(any(), any(), any()))
        .thenAnswer(invocation -> snapshot(invocation.getArgument(2), "10.00", List.of()));

    QmaiSyncService.BatchView current =
        service.startMonth(1L, "ruguo", YearMonth.from(today).toString(),
            null, 7L, "老板");

    assertThat(current.status()).isEqualTo("SUCCEEDED");
    assertThat(current.totalTasks()).isEqualTo(yesterday.getDayOfMonth());
    assertThat(current.completedTasks()).isEqualTo(current.totalTasks());
    assertThat(jdbc.queryForObject(
        "select max(business_date) from qmai_daily_sales", LocalDate.class))
        .isEqualTo(yesterday);

    YearMonth previous = YearMonth.from(today).minusMonths(1);
    QmaiSyncService.BatchView historical =
        service.startMonth(1L, "ruguo", previous.toString(), null, 7L, "老板");
    assertThat(historical.totalTasks()).isEqualTo(previous.lengthOfMonth());
    assertThat(historical.status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void failedDayCountsAsProcessedAndKeepsPreviousDailyAndProducts() {
    LocalDate date = LocalDate.now(ZONE).minusDays(3);
    long oldBatch = repository.createBatch(
        1L, "ruguo", YearMonth.from(date).toString(), null, "旧批次", 1);
    repository.replaceDay(1L, "ruguo", oldBatch,
        snapshot(date, "99.00", List.of(product("old", "旧商品", "99.00"))));
    repository.finish(oldBatch, "SUCCEEDED", null);
    when(orderService.fetchDailyShop(any(), any(), eq(date)))
        .thenThrow(new IllegalStateException("upstream failure"));

    QmaiSyncService.BatchView failed =
        service.startDate(1L, "ruguo", date.toString(), null, 7L, "老板");

    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.totalTasks()).isEqualTo(1);
    assertThat(failed.completedTasks()).isEqualTo(1);
    assertThat(failed.failedTasks()).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select received_amount from qmai_daily_sales where business_date = ?",
        BigDecimal.class, date)).isEqualByComparingTo("99.00");
    assertThat(jdbc.queryForList(
        "select item_name from qmai_product_sales where business_date = ?",
        String.class, date)).containsExactly("旧商品");
  }

  @Test
  void successfulRetryReplacesWholeDayAndDoesNotDuplicateRows() {
    LocalDate date = LocalDate.now(ZONE).minusDays(4);
    when(orderService.fetchDailyShop(any(), any(), eq(date)))
        .thenReturn(snapshot(date, "20.00", List.of(
            product("a", "商品A", "10.00"), product("b", "商品B", "10.00"))))
        .thenReturn(snapshot(date, "30.00", List.of(
            product("a", "商品A", "30.00"))));

    service.startDate(1L, "ruguo", date.toString(), null, 7L, "老板");
    service.startDate(1L, "ruguo", date.toString(), null, 7L, "老板");

    assertThat(jdbc.queryForObject(
        "select count(*) from qmai_daily_sales where business_date = ?",
        Integer.class, date)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select received_amount from qmai_daily_sales where business_date = ?",
        BigDecimal.class, date)).isEqualByComparingTo("30.00");
    assertThat(jdbc.queryForList(
        "select item_name from qmai_product_sales where business_date = ?",
        String.class, date)).containsExactly("商品A");
  }

  @Test
  void transactionRollsBackIfNewProductCannotBeInserted() {
    LocalDate date = LocalDate.now(ZONE).minusDays(5);
    long oldBatch = repository.createBatch(
        1L, "ruguo", YearMonth.from(date).toString(), null, "旧批次", 1);
    repository.replaceDay(1L, "ruguo", oldBatch,
        snapshot(date, "88.00", List.of(product("old", "旧商品", "88.00"))));
    repository.finish(oldBatch, "SUCCEEDED", null);
    long newBatch = repository.createBatch(
        1L, "ruguo", YearMonth.from(date).toString(), null, "新批次", 1);
    QmaiDailySalesSnapshot invalid = snapshot(
        date, "1.00", List.of(product("broken", null, "1.00")));
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    assertThatThrownBy(() ->
        transaction.executeWithoutResult(ignored ->
            repository.replaceDay(1L, "ruguo", newBatch, invalid)))
        .isInstanceOf(RuntimeException.class);

    assertThat(jdbc.queryForObject(
        "select received_amount from qmai_daily_sales where business_date = ?",
        BigDecimal.class, date)).isEqualByComparingTo("88.00");
    assertThat(jdbc.queryForList(
        "select item_name from qmai_product_sales where business_date = ?",
        String.class, date)).containsExactly("旧商品");
  }

  @Test
  void databaseLeaseRejectsSecondOwnerAndWrongOwnerCannotRelease() {
    assertThat(repository.claimLease(1L, "ruguo", "owner-a", 10)).isTrue();
    assertThat(repository.claimLease(1L, "ruguo", "owner-b", 10)).isFalse();
    repository.releaseLease(1L, "ruguo", "wrong-owner");
    assertThat(repository.claimLease(1L, "ruguo", "owner-b", 10)).isFalse();
    repository.releaseLease(1L, "ruguo", "owner-a");
    assertThat(repository.claimLease(1L, "ruguo", "owner-b", 10)).isTrue();
  }

  @Test
  void busyLeaseAlwaysReturnsConflictForExactDayAndSameMonthRequests() {
    LocalDate date = LocalDate.now(ZONE).minusDays(2);
    assertThat(repository.claimLease(1L, "ruguo", "existing-owner", 10)).isTrue();

    assertThatThrownBy(() -> service.startDate(
        1L, "ruguo", date.toString(), null, 7L, "老板"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("QMAI_SYNC_BUSY");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
    assertThatThrownBy(() -> service.startMonth(
        1L, "ruguo", YearMonth.from(date).toString(), null, 7L, "老板"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("QMAI_SYNC_BUSY");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        });

    verifyNoInteractions(orderService);
    repository.releaseLease(1L, "ruguo", "existing-owner");
  }

  @Test
  void staleNormalizedMirrorNeverOverridesCanonicalConfiguredShop() {
    LocalDate date = LocalDate.now(ZONE).minusDays(2);
    jdbc.update("""
        insert into qmai_store_mapping(
          tenant_id, brand_code, qmai_shop_id, qmai_shop_name, store_id)
        values (1, 'ruguo', '999999', '旧镜像门店', 's1')
        """);
    when(orderService.fetchDailyShop(any(), any(), eq(date)))
        .thenAnswer(invocation -> snapshot(date, "10.00", List.of()));

    service.startDate(1L, "ruguo", date.toString(), null, 7L, "老板");

    verify(orderService).fetchDailyShop(
        any(), argThat(shop -> "285275".equals(shop.shopCode())), eq(date));
    verify(orderService, never()).fetchDailyShop(
        any(), argThat(shop -> "999999".equals(shop.shopCode())), eq(date));
  }

  @Test
  void schedulerTargetsOnlyTenantOwnedDatabaseConfigurations() {
    jdbc.update("insert into tenant values (2, 'ACTIVE')");
    jdbc.update("""
        insert into qmai_platform_config
        values (1, 'ruguo', 'enc-open-id', 'enc-grant-code', 'enc-open-key', '285275:店:s1')
        """);
    jdbc.update("""
        insert into qmai_platform_config
        values (2, 'ruguo', '', '', '', '')
        """);

    assertThat(repository.activeTargets())
        .containsExactly(new QmaiSyncRepository.SyncTarget(1L, "ruguo"));
  }

  @Test
  void missingOrDuplicateStoreMappingIsRejectedBeforeAnyExternalCall() {
    when(configService.resolve(1L, "ruguo"))
        .thenReturn(config(List.of("285275:未映射门店")));
    assertThatThrownBy(() -> service.startDate(
        1L, "ruguo", LocalDate.now(ZONE).minusDays(2).toString(), null, 7L, "老板"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("QMAI_SHOP_MAPPING_REQUIRED"));

    when(configService.resolve(1L, "ruguo")).thenReturn(
        config(List.of("285275:一店:s1", "287952:二店:s1")));
    assertThatThrownBy(() -> service.startDate(
        1L, "ruguo", LocalDate.now(ZONE).minusDays(2).toString(), null, 7L, "老板"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("QMAI_SHOP_MAPPING_INVALID"));
  }

  @Test
  void dateMustBelongToSelectedMonth() {
    QmaiOperatingDataService operatingData =
        new QmaiOperatingDataService(mock(QmaiOperatingDataRepository.class));

    assertThatThrownBy(() ->
        operatingData.businessDate("2026-06", "2026-07-01"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("QMAI_DATE_MONTH_MISMATCH"));
  }

  @Test
  void localMonthlyReadsStopAtShanghaiYesterdayAndExactTodayIsRejected() {
    QmaiOperatingDataRepository localRepository = mock(QmaiOperatingDataRepository.class);
    QmaiOperatingDataService operatingData =
        new QmaiOperatingDataService(localRepository);
    assumeTrue(LocalDate.now(ZONE).getDayOfMonth() > 1);
    LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
    YearMonth current = YearMonth.from(yesterday.plusDays(1));

    operatingData.revenue(1L, "ruguo", current.toString(), null);

    verify(localRepository).revenue(
        1L, "ruguo", current.atDay(1), yesterday, null);
    assertThatThrownBy(() ->
        operatingData.businessDate(current.toString(), LocalDate.now(ZONE).toString()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("QMAI_DATE_NOT_FINAL"));
    assertThatThrownBy(() ->
        operatingData.revenueForDate(
            1L, "ruguo", LocalDate.now(ZONE).plusDays(1).toString(), null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("QMAI_DATE_NOT_FINAL"));
  }

  private QmaiConfigService.EffectiveConfig config(List<String> shops) {
    return new QmaiConfigService.EffectiveConfig(
        "open-id", "grant-code", "open-key", "http://127.0.0.1:1", "1.0",
        Duration.ofSeconds(1), shops, "", "", "", "TEST");
  }

  private QmaiDailySalesSnapshot snapshot(LocalDate date, String received,
      List<QmaiDailySalesSnapshot.Product> products) {
    BigDecimal amount = new BigDecimal(received);
    return new QmaiDailySalesSnapshot(
        "285275", "s1", date, products.size(), amount, amount,
        BigDecimal.ZERO, BigDecimal.ZERO, products);
  }

  private QmaiDailySalesSnapshot.Product product(
      String key, String name, String received) {
    BigDecimal amount = new BigDecimal(received);
    return new QmaiDailySalesSnapshot.Product(
        key, null, null, name, "饮品", BigDecimal.ONE, BigDecimal.ZERO,
        amount, amount, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  private void createSchema(JdbcTemplate template) {
    template.execute("create table tenant (id bigint primary key, status varchar(20))");
    template.execute("""
        create table store_branch (
          tenant_id bigint not null, id varchar(64) not null, name varchar(160),
          primary key (tenant_id, id))
        """);
    template.execute("""
        create table qmai_platform_config (
          tenant_id bigint not null, brand varchar(40) not null,
          open_id varchar(300), grant_code varchar(300), open_key varchar(300), shops varchar(1000),
          primary key (tenant_id, brand))
        """);
    template.execute("""
        create table qmai_store_mapping (
          tenant_id bigint not null, brand_code varchar(40) not null,
          qmai_shop_id varchar(80) not null, qmai_shop_name varchar(160),
          store_id varchar(64) not null)
        """);
    template.execute("""
        create table qmai_operating_sync_lease (
          tenant_id bigint not null, brand_code varchar(40) not null,
          owner_token varchar(64), locked_until timestamp, updated_at timestamp,
          primary key (tenant_id, brand_code))
        """);
    template.execute("""
        create table qmai_sync_batch (
          id bigint generated by default as identity primary key,
          tenant_id bigint not null, brand_code varchar(40) not null,
          target_month char(7) not null, status varchar(24) not null,
          requested_by bigint, requested_by_name varchar(120),
          total_tasks int not null default 0, completed_tasks int not null default 0,
          failed_tasks int not null default 0, daily_rows int not null default 0,
          product_rows int not null default 0, error_summary varchar(1000),
          created_at timestamp default current_timestamp, started_at timestamp,
          finished_at timestamp)
        """);
    template.execute("""
        create table qmai_daily_sales (
          id bigint generated by default as identity primary key,
          tenant_id bigint not null, brand_code varchar(40) not null,
          qmai_shop_id varchar(80) not null, store_id varchar(64) not null,
          business_date date not null, source_row_count int not null,
          receivable_amount decimal(18,2) not null,
          received_amount decimal(18,2) not null,
          cost_amount decimal(18,2) not null, refund_amount decimal(18,2) not null,
          sync_batch_id bigint not null, synced_at timestamp,
          unique (tenant_id, brand_code, qmai_shop_id, business_date))
        """);
    template.execute("""
        create table qmai_product_sales (
          id bigint generated by default as identity primary key,
          tenant_id bigint not null, brand_code varchar(40) not null,
          qmai_shop_id varchar(80) not null, store_id varchar(64) not null,
          business_date date not null, product_key varchar(128) not null,
          product_id varchar(120), sku_id varchar(120), item_name varchar(300) not null,
          category_name varchar(160), quantity decimal(18,3) not null,
          refund_quantity decimal(18,3) not null,
          receivable_amount decimal(18,2) not null,
          received_amount decimal(18,2) not null,
          cost_amount decimal(18,2) not null, refund_amount decimal(18,2) not null,
          sync_batch_id bigint not null, synced_at timestamp,
          unique (tenant_id, brand_code, qmai_shop_id, business_date, product_key))
        """);
  }
}
