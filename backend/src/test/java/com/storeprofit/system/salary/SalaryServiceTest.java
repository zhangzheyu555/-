package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
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

class SalaryServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SalaryService service;
  private SalaryQueryService queryService;
  private SalaryRepository repository;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("""
        create table brand (
          id bigint not null primary key,
          tenant_id bigint not null,
          name varchar(120) not null
        )
        """);
    jdbcTemplate.execute("""
        create table store_branch (
          id varchar(64) not null primary key,
          tenant_id bigint not null,
          brand_id bigint null,
          code varchar(80) null,
          name varchar(160) not null,
          area varchar(160) null,
          manager varchar(120) null
        )
        """);
    jdbcTemplate.execute("""
        create table salary_record (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          month char(7) not null,
          employee_id varchar(120) null,
          employee_name varchar(120) not null,
          position varchar(80) null,
          attendance varchar(80) null,
          gross decimal(14,2) not null default 0,
          net_pay decimal(14,2) null,
          normal_hours decimal(10,2) not null default 0,
          ot_hours decimal(10,2) not null default 0,
          work_hours decimal(10,2) not null default 0,
          vacation_left decimal(10,2) not null default 0,
          vacation_note varchar(255) null,
          base decimal(14,2) not null default 0,
          social decimal(14,2) not null default 0,
          post decimal(14,2) not null default 0,
          meal decimal(14,2) not null default 0,
          full_attendance decimal(14,2) not null default 0,
          commission decimal(14,2) not null default 0,
          overtime decimal(14,2) not null default 0,
          seniority decimal(14,2) not null default 0,
          late_night decimal(14,2) not null default 0,
          subsidy decimal(14,2) not null default 0,
          performance decimal(14,2) not null default 0,
          deduct_uniform decimal(14,2) not null default 0,
          return_uniform decimal(14,2) not null default 0,
          status varchar(40) not null default 'DRAFT',
          submitted_by bigint null,
          reviewed_by bigint null,
          reviewed_at timestamp null,
          review_note varchar(500) null,
          paid_at timestamp null,
          version int not null default 1,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp null default null
        )
        """);
    jdbcTemplate.execute("""
        create table employee (
          id varchar(120) not null primary key, tenant_id bigint not null, store_id varchar(64) not null,
          name varchar(120) not null, role varchar(80), position varchar(80), base_salary decimal(14,2) not null default 0,
          status varchar(40) not null default '在职'
        )
        """);
    jdbcTemplate.execute("""
        create table operation_log (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          operator_id bigint null,
          operator_name varchar(120) null,
          action varchar(80) not null,
          target_type varchar(80) not null,
          target_id varchar(120) null,
          store_id varchar(64) null,
          month char(7) null,
          reason varchar(255) null,
          before_json clob null,
          after_json clob null,
          created_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.update("insert into brand(id, tenant_id, name) values (1, 1, 'Tea'), (2, 2, 'Other')");
    jdbcTemplate.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name, area, manager)
        values
          ('s1', 1, 1, '001', 'One', 'Guangzhou', 'Alice'),
          ('s2', 1, 1, '002', 'Two', 'Foshan', 'Bob'),
          ('other', 2, 2, '099', 'Other', 'Shenzhen', 'Mallory')
        """);
    repository = new SalaryRepository(
        jdbcTemplate,
        new NamedParameterJdbcTemplate(dataSource)
    );
    service = new SalaryService(repository);
    queryService = new SalaryQueryService(repository, null);
  }

  @Test
  void employeePageKeepsEmployeesWithoutSalaryRecords() {
    jdbcTemplate.update("""
        insert into employee(id, tenant_id, store_id, name, role, position, base_salary, status)
        values ('emp-a', 1, 's1', 'Alice', 'BARISTA', '调饮师', 5000, '在职'),
               ('emp-b', 1, 's1', 'Bob', 'CASHIER', '收银员', 4500, '在职')
        """);
    SalaryRecordRequest alice = request("s1", "2026-05", "Alice", "5200", "5000");
    service.save(boss(), "salary-a", new SalaryRecordRequest(
        alice.storeId(), alice.month(), "emp-a", alice.employeeName(), alice.position(), alice.attendance(),
        alice.gross(), alice.normalHours(), alice.otHours(), alice.workHours(), alice.vacationLeft(), alice.vacationNote(),
        alice.base(), alice.social(), alice.post(), alice.meal(), alice.fullAttendance(), alice.commission(), alice.overtime(),
        alice.seniority(), alice.lateNight(), alice.subsidy(), alice.performance(), alice.deductUniform(), alice.returnUniform()
    ));

    SalaryEmployeePageResponse result = queryService.employeePage(boss(), "2026-05", null, null, null, null, 1, 20);

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.rows()).extracting(SalaryRecordResponse::employeeName).containsExactlyInAnyOrder("Alice", "Bob");
    assertThat(result.rows()).anySatisfy(row -> {
      assertThat(row.employeeName()).isEqualTo("Alice");
      assertThat(row.status()).isEqualTo("DRAFT");
    });
    assertThat(result.rows()).anySatisfy(row -> {
      assertThat(row.employeeName()).isEqualTo("Bob");
      assertThat(row.status()).isEqualTo("PENDING_GENERATION");
    });
    assertThat(result.statusCounts()).containsEntry("DRAFT", 1).containsEntry("PENDING_GENERATION", 1);
    assertThat(queryService.employeePage(boss(), "2026-05", null, null, "PENDING_GENERATION", "Bob", 1, 20).total())
        .isEqualTo(1);
  }

  @Test
  void employeePageKeepsHistoricalSalaryForInactiveEmployeeButDoesNotOfferInactiveEmployeeForGeneration() {
    jdbcTemplate.update("""
        insert into employee(id, tenant_id, store_id, name, role, position, base_salary, status)
        values ('emp-left-with-history', 1, 's1', 'Former Alice', 'BARISTA', '调饮师', 5000, '离职'),
               ('emp-left-without-history', 1, 's1', 'Former Bob', 'CASHIER', '收银员', 4500, '离职')
        """);
    jdbcTemplate.update("""
        insert into salary_record(id, tenant_id, store_id, month, employee_id, employee_name, position, gross, base)
        values ('LEGACY-left', 1, 's1', '2026-05', 'emp-left-with-history', 'Former Alice', '调饮师', 5200, 5000)
        """);

    SalaryEmployeePageResponse result = queryService.employeePage(
        boss(), "2026-05", null, null, null, null, 1, 20);

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.rows()).extracting(SalaryRecordResponse::id).containsExactly("LEGACY-left");
    assertThat(result.rows()).extracting(SalaryRecordResponse::employeeName).containsExactly("Former Alice");
    assertThat(result.statusCounts()).isEqualTo(java.util.Map.of("DRAFT", 1));
  }

  @Test
  void bossCanCreateListAndSummarizeSalaryRecordsInsideTenant() {
    SalaryRecordResponse first = service.save(boss(), null, request("s1", "2026-05", "Alice", "1000.00", "700.00"));
    SalaryRecordResponse second = service.save(boss(), null, request("s2", "2026-05", "Bob", "2000.00", "1300.00"));
    jdbcTemplate.update("""
        insert into salary_record(id, tenant_id, store_id, month, employee_name, gross, base)
        values ('other-pay', 2, 'other', '2026-05', 'Mallory', 9999, 9999)
        """);

    List<SalaryRecordResponse> records = service.records(boss(), "2026-05", null, null);
    SalarySummaryResponse summary = service.summary(boss(), "2026-05", null, null);

    assertThat(first.id()).isNotBlank();
    assertThat(second.id()).isNotBlank();
    assertThat(records).extracting(SalaryRecordResponse::employeeName).containsExactly("Alice", "Bob");
    assertThat(records).extracting(SalaryRecordResponse::storeName).containsExactly("One", "Two");
    assertThat(summary.month()).isEqualTo("2026-05");
    assertThat(summary.recordCount()).isEqualTo(2);
    assertThat(summary.storeCount()).isEqualTo(2);
    assertThat(summary.grossTotal()).isEqualByComparingTo("3000.00");
    assertThat(summary.baseTotal()).isEqualByComparingTo("2000.00");
  }

  @Test
  void storeManagerReadsOnlyOwnStoreAndCannotWrite() {
    service.save(boss(), "pay-s1", request("s1", "2026-05", "Alice", "1000", "700"));
    service.save(boss(), "pay-s2", request("s2", "2026-05", "Bob", "2000", "1300"));

    List<SalaryRecordResponse> records = service.records(storeManager(), "2026-05", null, null);

    assertThat(records).extracting(SalaryRecordResponse::id).containsExactly("pay-s1");
    assertThatThrownBy(() -> service.records(storeManager(), "2026-05", null, "s2"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> service.save(storeManager(), null, request("s1", "2026-05", "Alice", "1000", "700")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void configuredSalaryStoreListFiltersRowsAndPaginationTotalInSql() {
    service.save(boss(), "pay-s1", request("s1", "2026-05", "Alice", "1000", "700"));
    service.save(boss(), "pay-s2", request("s2", "2026-05", "Bob", "2000", "1300"));
    jdbcTemplate.update("""
        insert into employee(id, tenant_id, store_id, name, role, position, base_salary, status)
        values ('emp-s1', 1, 's1', 'Alice', 'BARISTA', '调饮师', 5000, '在职'),
               ('emp-s2', 1, 's2', 'Bob', 'BARISTA', '调饮师', 5000, '在职')
        """);
    AccessControlService accessControl = mock(AccessControlService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    DataScope scope = new DataScope(DataScopeModes.STORE_LIST, List.of("s1"));
    when(dataScopeService.scope(finance(), DataScopeDomains.SALARY)).thenReturn(scope);
    SalaryQueryService scopedQuery = new SalaryQueryService(repository, accessControl, dataScopeService);

    SalaryPageResponse records = scopedQuery.recordsPaged(finance(), "2026-05", null, null, 1, 20);
    SalaryEmployeePageResponse employees = scopedQuery.employeePage(
        finance(), "2026-05", null, null, null, null, 1, 20);

    assertThat(records.total()).isEqualTo(1);
    assertThat(records.rows()).extracting(SalaryRecordResponse::storeId).containsExactly("s1");
    assertThat(employees.total()).isEqualTo(1);
    assertThat(employees.rows()).extracting(SalaryRecordResponse::storeId).containsExactly("s1");
    assertThatThrownBy(() -> scopedQuery.records(finance(), "2026-05", null, "s2"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void salaryNoneScopeReturnsNoRowsAndZeroFilteredTotal() {
    service.save(boss(), "pay-s1", request("s1", "2026-05", "Alice", "1000", "700"));
    AccessControlService accessControl = mock(AccessControlService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    when(dataScopeService.scope(finance(), DataScopeDomains.SALARY)).thenReturn(DataScope.none());
    SalaryQueryService scopedQuery = new SalaryQueryService(repository, accessControl, dataScopeService);

    SalaryPageResponse records = scopedQuery.recordsPaged(finance(), "2026-05", null, null, 1, 20);

    assertThat(records.rows()).isEmpty();
    assertThat(records.total()).isZero();
  }

  @Test
  void saveRejectsBadMonthAndMissingStore() {
    assertThatThrownBy(() -> service.save(boss(), null, request("s1", "202605", "Alice", "1000", "700")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_MONTH"));

    assertThatThrownBy(() -> service.save(boss(), null, request("missing", "2026-05", "Alice", "1000", "700")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("STORE_NOT_FOUND"));
  }

  @Test
  void financeCanUpdateAndDeleteTenantScopedSalaryRecordWithAudit() {
    service.save(boss(), "pay-1", request("s1", "2026-05", "Alice", "1000", "700"));

    SalaryRecordResponse updated = service.save(finance(), "pay-1", request("s1", "2026-05", "Alice", "1500", "900"));
    service.delete(finance(), "pay-1");

    assertThat(updated.gross()).isEqualByComparingTo("1500.00");
    assertThat(service.records(boss(), "2026-05", null, null)).isEmpty();
    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where target_type = 'salary_record' and target_id = 'pay-1'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(3);
  }

  @Test
  void salaryUsesStableEmployeeIdAndRequiresReviewBeforeCompletion() {
    SalaryRecordRequest draftRequest = request("s1", "2026-05", "Alice", "1200", "800");
    SalaryRecordRequest identifiedRequest = new SalaryRecordRequest(
        draftRequest.storeId(), draftRequest.month(), "emp-alice", draftRequest.employeeName(), draftRequest.position(),
        draftRequest.attendance(), draftRequest.gross(), draftRequest.normalHours(), draftRequest.otHours(),
        draftRequest.workHours(), draftRequest.vacationLeft(), draftRequest.vacationNote(), draftRequest.base(),
        draftRequest.social(), draftRequest.post(), draftRequest.meal(), draftRequest.fullAttendance(),
        draftRequest.commission(), draftRequest.overtime(), draftRequest.seniority(), draftRequest.lateNight(),
        draftRequest.subsidy(), draftRequest.performance(), draftRequest.deductUniform(), draftRequest.returnUniform()
    );

    SalaryRecordResponse draft = service.save(finance(), "pay-review", identifiedRequest);
    SalaryRecordResponse submitted = service.submit(finance(), "pay-review");
    SalaryRecordResponse approved = service.approve(boss(), "pay-review");

    assertThat(draft.employeeId()).isEqualTo("emp-alice");
    assertThat(draft.status()).isEqualTo("DRAFT");
    assertThat(submitted.status()).isEqualTo("SUBMITTED");
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(approved.reviewedBy()).isEqualTo(boss().id());
  }

  private SalaryRecordRequest request(String storeId, String month, String employeeName, String gross, String base) {
    return new SalaryRecordRequest(
        storeId,
        month,
        null,
        employeeName,
        "Barista",
        "26",
        new BigDecimal(gross),
        new BigDecimal("216"),
        new BigDecimal("2.5"),
        new BigDecimal("218.5"),
        new BigDecimal("1"),
        "one day left",
        new BigDecimal(base),
        new BigDecimal("300"),
        new BigDecimal("100"),
        new BigDecimal("200"),
        new BigDecimal("50"),
        new BigDecimal("80"),
        new BigDecimal("70"),
        new BigDecimal("20"),
        new BigDecimal("10"),
        new BigDecimal("5"),
        new BigDecimal("60"),
        new BigDecimal("15"),
        new BigDecimal("0")
    );
  }

  private AuthUser boss() {
    return user("BOSS", null);
  }

  private AuthUser finance() {
    return user("FINANCE", null);
  }

  private AuthUser storeManager() {
    return user("STORE_MANAGER", "s1");
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(1L, 1L, "default", role.toLowerCase(), "", role, role, storeId, true);
  }
}
