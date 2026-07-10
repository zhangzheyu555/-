package com.storeprofit.system.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WarehouseTodoAlertRepositoryTest {
  private RoleTodoRepository repository;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("create alias if not exists date_format for \"" + WarehouseTodoAlertRepositoryTest.class.getName() + ".dateFormat\"");
    jdbcTemplate.execute("""
        create table warehouse_item (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          name varchar(160) not null,
          unit varchar(40) not null default '件',
          daily_usage_estimate decimal(14,2) not null default 0,
          min_stock_days int not null default 0,
          min_stock_quantity decimal(14,2) not null default 0,
          alert_enabled tinyint(1) not null default 1,
          expiry_alert_days int null default 3,
          active tinyint(1) not null default 1
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_stock_batch (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          item_id bigint not null,
          expiry_date date null,
          quantity decimal(14,2) not null default 0
        )
        """);
    repository = new RoleTodoRepository(jdbcTemplate, new NamedParameterJdbcTemplate(dataSource));
  }

  @Test
  void warehouseStockAlertUsesMinimumSafeStockQuantity() {
    jdbcTemplate.update("""
        insert into warehouse_item(
          id, tenant_id, name, unit, min_stock_quantity, alert_enabled, expiry_alert_days, active
        ) values (1, 1, '鲜奶', '件', 40.00, 1, 3, 1)
        """);
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(tenant_id, item_id, quantity, expiry_date)
        values (1, 1, 30.00, null)
        """);

    List<RoleTodoRepository.WarehouseStockAlertTodoRow> alerts = repository.warehouseStockAlerts(1L, 20);

    assertThat(alerts).singleElement().satisfies(alert -> {
      assertThat(alert.id()).isEqualTo("warehouse-alert-low-1");
      assertThat(alert.alertType()).isEqualTo("LOW");
      assertThat(alert.message()).contains("库存不足：鲜奶 当前 30", "最低安全库存 40");
    });
  }

  @Test
  void warehouseStockAlertDisappearsWhenStockIsSafeOrAlertDisabled() {
    jdbcTemplate.update("""
        insert into warehouse_item(
          id, tenant_id, name, unit, min_stock_quantity, alert_enabled, expiry_alert_days, active
        ) values
          (1, 1, '鲜奶', '件', 40.00, 1, 3, 1),
          (2, 1, '杯子', '件', 160.00, 0, 3, 1)
        """);
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(tenant_id, item_id, quantity, expiry_date)
        values
          (1, 1, 50.00, null),
          (1, 2, 120.00, null)
        """);

    List<RoleTodoRepository.WarehouseStockAlertTodoRow> alerts = repository.warehouseStockAlerts(1L, 20);

    assertThat(alerts).isEmpty();
  }

  public static String dateFormat(Date date, String pattern) {
    if (date == null) {
      return null;
    }
    return new SimpleDateFormat(pattern.replace("%Y", "yyyy").replace("%m", "MM").replace("%d", "dd")).format(date);
  }
}
