package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SalaryRepositoryDeleteTest {
  private JdbcTemplate jdbc;
  private SalaryRepository repository;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table salary_record (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          status varchar(40) not null,
          version int not null
        )
        """);
    jdbc.execute("""
        create table salary_record_item (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          salary_record_id varchar(120) not null,
          constraint fk_test_salary_item foreign key (salary_record_id) references salary_record(id)
        )
        """);
    jdbc.execute("""
        create table employee_month_attendance (
          tenant_id bigint not null,
          store_id varchar(64) not null,
          employee_id varchar(120) not null,
          month char(7) not null
        )
        """);
    repository = new SalaryRepository(jdbc, new NamedParameterJdbcTemplate(dataSource));
  }

  @Test
  void deletesAssociatedItemsBeforeEditableSalaryAndLeavesAttendanceUntouched() {
    jdbc.update("insert into salary_record(id, tenant_id, status, version) values (?, ?, ?, ?)",
        "salary-1", 1L, "DRAFT", 3);
    jdbc.update("insert into salary_record_item(id, tenant_id, salary_record_id) values (?, ?, ?)",
        "item-1", 1L, "salary-1");
    jdbc.update("insert into salary_record_item(id, tenant_id, salary_record_id) values (?, ?, ?)",
        "item-2", 1L, "salary-1");
    jdbc.update("insert into employee_month_attendance(tenant_id, store_id, employee_id, month) values (?, ?, ?, ?)",
        1L, "store-1", "employee-1", "2026-05");

    assertThat(repository.deleteItems(1L, "salary-1")).isEqualTo(2);
    assertThat(repository.deleteEditable(1L, "salary-1", 3)).isEqualTo(1);

    assertThat(count("salary_record_item")).isZero();
    assertThat(count("salary_record")).isZero();
    assertThat(count("employee_month_attendance")).isEqualTo(1);
  }

  @Test
  void editableDeleteRequiresBothEditableStatusAndExpectedVersion() {
    jdbc.update("insert into salary_record(id, tenant_id, status, version) values (?, ?, ?, ?)",
        "salary-submitted", 1L, "SUBMITTED", 5);
    jdbc.update("insert into salary_record(id, tenant_id, status, version) values (?, ?, ?, ?)",
        "salary-raced", 1L, "DRAFT", 6);

    assertThat(repository.deleteEditable(1L, "salary-submitted", 5)).isZero();
    assertThat(repository.deleteEditable(1L, "salary-raced", 5)).isZero();
    assertThat(count("salary_record")).isEqualTo(2);
  }

  private int count(String table) {
    Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
    return count == null ? 0 : count;
  }
}
