package com.storeprofit.system.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FinanceDataScopeRepositoryTest {
  private FinanceRepository repository;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table brand (
          id bigint not null primary key, tenant_id bigint not null, name varchar(120), sort_order int not null default 0
        )
        """);
    jdbc.execute("""
        create table store_branch (
          id varchar(64) not null primary key, tenant_id bigint not null, brand_id bigint,
          code varchar(80), name varchar(160), area varchar(160), manager varchar(120)
        )
        """);
    jdbc.execute("""
        create table profit_entry (
          id bigint auto_increment primary key, tenant_id bigint not null, store_id varchar(64) not null,
          month char(7) not null, sales decimal(14,2) default 0, refund decimal(14,2) default 0,
          discount decimal(14,2) default 0, material decimal(14,2) default 0,
          packaging decimal(14,2) default 0, loss decimal(14,2) default 0,
          cost_other decimal(14,2) default 0, rent decimal(14,2) default 0,
          labor decimal(14,2) default 0, utility decimal(14,2) default 0,
          property decimal(14,2) default 0, commission decimal(14,2) default 0,
          meituan decimal(14,2) default 0, eleme decimal(14,2) default 0,
          douyin decimal(14,2) default 0, amap decimal(14,2) default 0,
          promo decimal(14,2) default 0, repair decimal(14,2) default 0,
          equip decimal(14,2) default 0, exp_other decimal(14,2) default 0, note varchar(255)
        )
        """);
    jdbc.update("insert into brand(id, tenant_id, name, sort_order) values (1, 1, 'Tea', 1), (2, 2, 'Other', 1)");
    jdbc.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name, area, manager)
        values ('s1', 1, 1, '001', 'One', 'A', 'Alice'),
               ('s2', 1, 1, '002', 'Two', 'B', 'Bob'),
               ('other', 2, 2, '099', 'Other', 'C', 'Mallory')
        """);
    jdbc.update("""
        insert into profit_entry(tenant_id, store_id, month, sales)
        values (1, 's1', '2026-05', 100), (1, 's2', '2026-06', 200), (2, 'other', '2026-05', 999)
        """);
    repository = new FinanceRepository(jdbc, new NamedParameterJdbcTemplate(dataSource));
  }

  @Test
  void storeListAndNoneScopesAreAppliedBySql() {
    DataScope storeList = new DataScope(DataScopeModes.STORE_LIST, List.of("s1"));

    assertThat(repository.entries(1L, null, null, null, storeList))
        .extracting(ProfitEntryResponse::storeId)
        .containsExactly("s1");
    assertThat(repository.availableMonths(1L, storeList)).containsExactly("2026-05");
    assertThat(repository.entries(1L, null, null, null, DataScope.none())).isEmpty();
    assertThat(repository.entries(1L, null, null, null, DataScope.all()))
        .extracting(ProfitEntryResponse::storeId)
        .containsExactlyInAnyOrder("s1", "s2");
  }
}
