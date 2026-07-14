package com.storeprofit.system.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class OrganizationDataScopeRepositoryTest {
  private OrganizationRepository repository;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create alias if not exists date_format for '"
        + OrganizationDataScopeRepositoryTest.class.getName() + ".dateFormat'");
    jdbc.execute("""
        create table brand (
          id bigint not null primary key, tenant_id bigint not null, code varchar(40),
          name varchar(120), color varchar(40), sort_order int not null default 0
        )
        """);
    jdbc.execute("""
        create table warehouse_facility (
          id bigint not null primary key, tenant_id bigint not null, name varchar(160) not null
        )
        """);
    jdbc.execute("""
        create table store_branch (
          id varchar(64) not null primary key, tenant_id bigint not null, brand_id bigint,
          code varchar(80), name varchar(160), area varchar(160), manager varchar(120),
          open_date date, status varchar(40), note varchar(255), region_code varchar(40),
          supply_warehouse_id bigint
        )
        """);
    jdbc.update("""
        insert into brand(id, tenant_id, code, name, color, sort_order)
        values (1, 1, 'A', 'Alpha', '#111', 1), (2, 1, 'B', 'Beta', '#222', 2),
               (3, 2, 'X', 'Other', '#333', 1)
        """);
    jdbc.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name, area, manager, open_date, status, note)
        values ('s1', 1, 1, '001', 'One', 'A', 'Alice', '2025-01-01', '营业中', null),
               ('s2', 1, 2, '002', 'Two', 'B', 'Bob', '2025-02-01', '营业中', null),
               ('other', 2, 3, '099', 'Other', 'C', 'Mallory', '2025-03-01', '营业中', null)
        """);
    repository = new OrganizationRepository(jdbc);
  }

  @Test
  void storeAndBrandQueriesHonorConfiguredStoreListAndNone() {
    DataScope scope = new DataScope(DataScopeModes.STORE_LIST, List.of("s2"));

    assertThat(repository.stores(1L, scope)).extracting(StoreResponse::id).containsExactly("s2");
    assertThat(repository.brands(1L, scope)).extracting(BrandResponse::id).containsExactly(2L);
    assertThat(repository.stores(1L, DataScope.none())).isEmpty();
    assertThat(repository.brands(1L, DataScope.none())).isEmpty();
  }

  public static String dateFormat(java.sql.Date value, String ignoredPattern) {
    return value == null ? null : value.toString();
  }
}
