package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SalaryRepositoryCommissionHoursTest {

  @Test
  void commissionProductivityUsesNormalHoursAndExcludesOvertimeHours() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table employee (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          position varchar(80),
          employment_type varchar(40)
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
          overtime_hours decimal(10,2) not null,
          total_hours decimal(10,2) not null,
          status varchar(40) not null
        )
        """);
    jdbc.update("""
        insert into employee(id, tenant_id, position, employment_type) values
          ('formal', 1, '营业员', '全职'),
          ('intern', 1, '实习', '兼职')
        """);
    jdbc.update("""
        insert into employee_month_attendance(
          tenant_id, store_id, employee_id, month, attendance_days,
          normal_hours, overtime_hours, total_hours, status
        ) values
          (1, 's1', 'formal', '2026-06', 26, 208, 40, 248, 'CONFIRMED'),
          (1, 's1', 'intern', '2026-06', 20, 160, 20, 180, 'CONFIRMED')
        """);

    SalaryRepository repository = new SalaryRepository(jdbc, new NamedParameterJdbcTemplate(dataSource));

    SalaryRepository.StoreAttendanceStats stats = repository.storeAttendanceStats(1, "s1", "2026-06");

    assertThat(stats.effectiveHours()).isEqualByComparingTo("288.00");
    assertThat(stats.formalDays()).isEqualByComparingTo("26.00");
  }
}
