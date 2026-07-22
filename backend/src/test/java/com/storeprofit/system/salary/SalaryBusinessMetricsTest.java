package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SalaryBusinessMetricsTest {
  private JdbcTemplate jdbc;
  private SalaryRepository repository;
  private SalaryQueryService service;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table store_branch (
          id varchar(64) not null primary key,
          tenant_id bigint not null,
          brand_id bigint,
          code varchar(80),
          name varchar(160) not null
        )
        """);
    jdbc.execute("""
        create table profit_entry (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          month char(7) not null,
          sales decimal(14,2) not null,
          income decimal(14,2)
        )
        """);
    jdbc.execute("""
        create table employee (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          role varchar(80),
          position varchar(80),
          employment_type varchar(40)
        )
        """);
    jdbc.execute("""
        create table salary_record (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          month char(7) not null,
          employee_id varchar(120),
          position varchar(80),
          attendance varchar(80),
          normal_hours decimal(10,2),
          ot_hours decimal(10,2) not null default 0,
          work_hours decimal(10,2),
          commission decimal(14,2) not null default 0
        )
        """);
    jdbc.execute("""
        create table employee_month_attendance (
          tenant_id bigint not null,
          store_id varchar(64) not null,
          employee_id varchar(120) not null,
          month char(7) not null,
          attendance_days decimal(10,2) not null,
          normal_hours decimal(10,2) not null,
          overtime_hours decimal(10,2) not null default 0,
          status varchar(40) not null,
          primary key (tenant_id, store_id, employee_id, month)
        )
        """);
    repository = new SalaryRepository(jdbc, new NamedParameterJdbcTemplate(dataSource));
    service = new SalaryQueryService(repository, null);
  }

  @Test
  void usesRawSalesAndSalaryHoursBeforeAttendanceWhileHalvingHourlyEmployees() {
    jdbc.update("insert into store_branch(id, tenant_id, brand_id, code, name) values ('s1', 1, 1, '001', 'One')");
    jdbc.update("""
        insert into profit_entry(tenant_id, store_id, month, sales, income)
        values (1, 's1', '2026-07', 100000.99, 90000.11)
        """);
    jdbc.update("""
        insert into employee(id, tenant_id, store_id, role, position, employment_type) values
          ('formal', 1, 's1', 'EMPLOYEE', '店长', '全职'),
          ('zero-hours', 1, 's1', 'EMPLOYEE', '训练员', '全职'),
          ('part', 1, 's1', 'EMPLOYEE', '营业员', '兼职'),
          ('intern', 1, 's1', 'EMPLOYEE', '实习', '实习')
        """);
    jdbc.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, position, attendance,
          normal_hours, ot_hours, commission
        ) values
          ('salary-formal', 1, 's1', '2026-07', 'formal', '店长', '20天', 160, 20, 2000),
          ('salary-zero', 1, 's1', '2026-07', 'zero-hours', '训练员', '10天', 0, 99, 0),
          ('salary-orphan-part', 1, 's1', '2026-07', null, '长期兼职', '10天', 100, 4, 0)
        """);
    jdbc.update("""
        insert into employee_month_attendance(
          tenant_id, store_id, employee_id, month, attendance_days,
          normal_hours, overtime_hours, status
        ) values
          (1, 's1', 'formal', '2026-07', 25, 200, 50, 'CONFIRMED'),
          (1, 's1', 'zero-hours', '2026-07', 10, 80, 10, 'CONFIRMED'),
          (1, 's1', 'part', '2026-07', 10, 100, 8, 'CONFIRMED'),
          (1, 's1', 'intern', '2026-07', 8, 80, 6, 'CONFIRMED')
        """);

    SalaryBusinessMetricsResponse result = service.businessMetrics(
        finance(), "2026-07", null, "s1");

    // (160+20) + (80+10) + (100+8)/2 + (80+6)/2 + (100+4)/2 = 419。
    // 工资行整组优先于考勤；工资行正常工时为0时，正常与加班工时均回退到考勤。
    assertThat(result.revenue()).isEqualByComparingTo("100000.99");
    assertThat(result.effectiveHours()).isEqualByComparingTo("419.00");
    assertThat(result.hourlyRevenue()).isEqualByComparingTo("238.00");
    assertThat(result.perCapitaOutput()).isEqualByComparingTo("49504.00");
    // 正式出勤天数=salary attendance 20 + 训练员确认考勤10；所有小时制员工都不计正式天数。
    assertThat(result.commissionPool()).isEqualByComparingTo("1713.46");
    assertThat(result.commissionTotal()).isEqualByComparingTo("2000.00");
    assertThat(result.storeFund()).isEqualByComparingTo("-286.54");
  }

  @Test
  void keepsFractionalNormalPlusOvertimeTotalForHourlyRevenueDenominator() {
    jdbc.update("insert into store_branch(id, tenant_id, brand_id, code, name) values ('s1', 1, 1, '001', 'One')");
    jdbc.update("""
        insert into profit_entry(tenant_id, store_id, month, sales)
        values (1, 's1', '2026-07', 139872.82)
        """);
    jdbc.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, position, attendance,
          normal_hours, ot_hours, commission
        ) values ('salary-formal', 1, 's1', '2026-07', null, '营业员', '26天', 1000, 69.75, 0)
        """);

    SalaryBusinessMetricsResponse result = service.businessMetrics(
        finance(), "2026-07", null, "s1");

    // 后端保留1069.75原始精度，展示层可截取为1069；每小时营业额按139872.82/1069.75向下取整。
    assertThat(result.effectiveHours()).isEqualByComparingTo("1069.75");
    assertThat(result.hourlyRevenue()).isEqualByComparingTo("130.00");
    assertThat(result.perCapitaOutput()).isEqualByComparingTo("27040.00");
  }

  @Test
  void fallsBackToHistoricalWorkHoursWhenHourBreakdownAndAttendanceAreMissing() {
    jdbc.update("insert into store_branch(id, tenant_id, brand_id, code, name) values ('s1', 1, 1, '001', 'One')");
    jdbc.update("insert into profit_entry(tenant_id, store_id, month, sales) values (1, 's1', '2026-07', 100000)");
    jdbc.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, position, attendance,
          normal_hours, ot_hours, work_hours, commission
        ) values ('salary-legacy', 1, 's1', '2026-07', null, '营业员', '15天', 0, 0, 123.75, 0)
        """);

    SalaryBusinessMetricsResponse result = service.businessMetrics(
        finance(), "2026-07", null, "s1");

    assertThat(result.effectiveHours()).isEqualByComparingTo("123.75");
    assertThat(result.hourlyRevenue()).isEqualByComparingTo("808.00");
  }

  @Test
  void hourlyEmployeesInStandardPositionsDoNotContributeFormalDays() {
    jdbc.update("insert into store_branch(id, tenant_id, brand_id, code, name) values ('s1', 1, 1, '001', 'One')");
    jdbc.update("""
        insert into profit_entry(tenant_id, store_id, month, sales)
        values (1, 's1', '2026-07', 100000)
        """);
    jdbc.update("""
        insert into employee(id, tenant_id, store_id, role, position, employment_type) values
          ('intern', 1, 's1', 'EMPLOYEE', '店长', 'INTERN'),
          ('part', 1, 's1', 'EMPLOYEE', '营业员', 'PART_TIME')
        """);
    jdbc.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, position, attendance,
          normal_hours, ot_hours, commission
        ) values
          ('salary-intern', 1, 's1', '2026-07', 'intern', '店长', '218.4小时', 218.4, 0, 0),
          ('salary-part', 1, 's1', '2026-07', 'part', '营业员', '218.4小时', 218.4, 0, 0)
        """);

    SalaryBusinessMetricsResponse result = service.businessMetrics(
        finance(), "2026-07", null, "s1");

    assertThat(result.effectiveHours()).isEqualByComparingTo("218.40");
    assertThat(result.hourlyRevenue()).isEqualByComparingTo("457.00");
    assertThat(result.commissionPool()).isEqualByComparingTo("0.00");
    assertThat(result.storeFund()).isEqualByComparingTo("0.00");
  }

  @Test
  void sumsFractionalEffectiveHoursAcrossStoresBeforeRounding() {
    jdbc.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name) values
          ('s1', 1, 1, '001', 'One'), ('s2', 1, 1, '002', 'Two')
        """);
    jdbc.update("""
        insert into profit_entry(tenant_id, store_id, month, sales) values
          (1, 's1', '2026-07', 50000), (1, 's2', '2026-07', 50000)
        """);
    jdbc.update("""
        insert into employee(id, tenant_id, store_id, role, position, employment_type) values
          ('s1-part', 1, 's1', 'EMPLOYEE', '调饮师', 'PART_TIME'),
          ('s2-part', 1, 's2', 'EMPLOYEE', '调饮师', 'PART_TIME')
        """);
    jdbc.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, position, attendance,
          normal_hours, ot_hours, commission
        ) values
          ('s1-pay', 1, 's1', '2026-07', 's1-part', '调饮师', '100.01小时', 100.01, 0, 0),
          ('s2-pay', 1, 's2', '2026-07', 's2-part', '调饮师', '100.01小时', 100.01, 0, 0)
        """);

    SalaryBusinessMetricsResponse result = service.businessMetrics(
        finance(), "2026-07", 1L, null);

    // 每店有效工时都是50.005；只能先相加为100.010，再统一展示为100.01。
    assertThat(result.effectiveHours()).isEqualByComparingTo("100.01");
    assertThat(result.hourlyRevenue()).isEqualByComparingTo("999.00");
  }

  @Test
  void ignoresStoresWithoutAnyCurrentMonthDataWhenAggregatingAllStores() {
    jdbc.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name) values
          ('s1', 1, 1, '001', 'One'), ('empty', 1, 1, '999', 'Empty')
        """);
    jdbc.update("""
        insert into profit_entry(tenant_id, store_id, month, sales)
        values (1, 's1', '2026-07', 100000)
        """);
    jdbc.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, position, attendance,
          normal_hours, ot_hours, commission
        ) values ('salary-formal', 1, 's1', '2026-07', null, '营业员', '26天', 208, 0, 600)
        """);

    SalaryBusinessMetricsResponse result = service.businessMetrics(
        finance(), "2026-07", 1L, null);

    assertThat(result.hourlyRevenue()).isEqualByComparingTo("480.00");
    assertThat(result.perCapitaOutput()).isEqualByComparingTo("99840.00");
    assertThat(result.commissionPool()).isNotNull();
    assertThat(result.storeFund()).isNotNull();
  }

  @Test
  void calculatesEachStorePoolBeforeSummingAndDoesNotClampNegativeFund() {
    jdbc.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name) values
          ('s1', 1, 1, '001', 'One'), ('s2', 1, 1, '002', 'Two')
        """);
    jdbc.update("""
        insert into profit_entry(tenant_id, store_id, month, sales) values
          (1, 's1', '2026-07', 100000), (1, 's2', '2026-07', 50000)
        """);
    jdbc.update("""
        insert into employee(id, tenant_id, store_id, role, position, employment_type) values
          ('s1-a', 1, 's1', 'EMPLOYEE', '店长', '全职'),
          ('s1-b', 1, 's1', 'EMPLOYEE', '营业员', '全职'),
          ('s2-a', 1, 's2', 'EMPLOYEE', '店长', '全职')
        """);
    jdbc.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, position, attendance, normal_hours, commission
        ) values
          ('s1-pay-a', 1, 's1', '2026-07', 's1-a', '店长', '26天', 200, 1800),
          ('s1-pay-b', 1, 's1', '2026-07', 's1-b', '营业员', '26天', 200, 1500),
          ('s2-pay-a', 1, 's2', '2026-07', 's2-a', '店长', '26天', 200, 1500)
        """);

    SalaryBusinessMetricsResponse result = service.businessMetrics(
        finance(), "2026-07", 1L, null);

    // s1池=3120，s2池=1560，必须分店计算后相加。
    assertThat(result.revenue()).isEqualByComparingTo("150000.00");
    assertThat(result.effectiveHours()).isEqualByComparingTo("600.00");
    assertThat(result.hourlyRevenue()).isEqualByComparingTo("250.00");
    assertThat(result.perCapitaOutput()).isEqualByComparingTo("52000.00");
    assertThat(result.commissionPool()).isEqualByComparingTo("4680.00");
    assertThat(result.commissionTotal()).isEqualByComparingTo("4800.00");
    assertThat(result.storeFund()).isEqualByComparingTo("-120.00");
  }

  @Test
  void keepsRawTotalsButReturnsNullDerivedMetricsWhenRevenueIsMissing() {
    jdbc.update("insert into store_branch(id, tenant_id, brand_id, code, name) values ('s1', 1, 1, '001', 'One')");
    jdbc.update("""
        insert into employee(id, tenant_id, store_id, role, position, employment_type)
        values ('formal', 1, 's1', 'EMPLOYEE', '营业员', '全职')
        """);
    jdbc.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, position, attendance, normal_hours, commission
        ) values ('salary-formal', 1, 's1', '2026-07', 'formal', '营业员', '26天', 208, 600)
        """);

    SalaryBusinessMetricsResponse result = service.businessMetrics(
        finance(), "2026-07", null, "s1");

    assertThat(result.revenue()).isEqualByComparingTo("0.00");
    assertThat(result.effectiveHours()).isEqualByComparingTo("208.00");
    assertThat(result.commissionTotal()).isEqualByComparingTo("600.00");
    assertThat(result.hourlyRevenue()).isNull();
    assertThat(result.perCapitaOutput()).isNull();
    assertThat(result.commissionPool()).isNull();
    assertThat(result.storeFund()).isNull();
  }

  @Test
  void salaryStoreListScopeOnlyAggregatesAuthorizedStores() {
    jdbc.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name) values
          ('s1', 1, 1, '001', 'One'), ('s2', 1, 1, '002', 'Two')
        """);
    jdbc.update("""
        insert into profit_entry(tenant_id, store_id, month, sales, income) values
          (1, 's1', '2026-07', 100000, 90000), (1, 's2', '2026-07', 50000, 40000)
        """);
    jdbc.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, position, attendance, normal_hours, commission
        ) values
          ('s1-pay', 1, 's1', '2026-07', null, '营业员', '26天', 208, 1500),
          ('s2-pay', 1, 's2', '2026-07', null, '营业员', '26天', 208, 1000)
        """);

    AuthUser finance = finance();
    AccessControlService accessControl = mock(AccessControlService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    when(dataScopeService.scope(finance, DataScopeDomains.SALARY))
        .thenReturn(new DataScope(DataScopeModes.STORE_LIST, List.of("s1")));
    SalaryQueryService scopedService = new SalaryQueryService(
        repository, accessControl, dataScopeService);

    SalaryBusinessMetricsResponse result = scopedService.businessMetrics(
        finance, "2026-07", null, null);

    assertThat(result.revenue()).isEqualByComparingTo("100000.00");
    assertThat(result.effectiveHours()).isEqualByComparingTo("208.00");
    assertThat(result.commissionTotal()).isEqualByComparingTo("1500.00");
  }

  private AuthUser finance() {
    return new AuthUser(1L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true);
  }
}
