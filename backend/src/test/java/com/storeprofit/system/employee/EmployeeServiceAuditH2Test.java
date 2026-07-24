package com.storeprofit.system.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.PasswordService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class EmployeeServiceAuditH2Test {
  private static final long TENANT_ID = 1L;
  private static final String STORE_A = "EMP_A";
  private static final String STORE_B = "EMP_B";

  @Test
  void fourSuccessfulEmployeeWritesProduceRedactedOperationLogs() {
    Fixture fixture = fixture();
    EmployeeResponse created = fixture.service().create(fixture.boss(), request("审计员工", "在职", "店员"));
    EmployeeResponse updated = fixture.service().update(
        fixture.boss(), created.id(), request("审计员工", "在职", "资深店员"));
    EmployeeAccountResponse account = fixture.service().createAccount(fixture.boss(), created.id());
    fixture.service().remove(fixture.boss(), created.id());

    List<Map<String, Object>> logs = fixture.jdbc().queryForList("""
        select operator_id, action, target_type, target_id, store_id, reason, before_json, after_json
        from operation_log
        where tenant_id = ? and target_type = 'employee' and target_id = ?
        order by id
        """, TENANT_ID, created.id());

    assertThat(updated.position()).isEqualTo("资深店员");
    assertThat(account.employeeId()).isEqualTo(created.id());
    assertThat(logs).hasSize(4);
    assertThat(logs).extracting(row -> row.get("ACTION"))
        .containsExactly("新增员工档案", "修改员工档案", "创建员工登录账号", "员工离职");
    assertThat(logs).allSatisfy(row -> {
      assertThat(((Number) row.get("OPERATOR_ID")).longValue()).isEqualTo(fixture.boss().id());
      assertThat(row.get("TARGET_TYPE")).isEqualTo("employee");
      assertThat(row.get("TARGET_ID")).isEqualTo(created.id());
      assertThat(row.get("STORE_ID")).isEqualTo(STORE_A);
      assertThat(row.get("REASON").toString()).contains("成功");
    });
    String auditPayload = logs.stream()
        .map(row -> String.valueOf(row.get("REASON"))
            + String.valueOf(row.get("BEFORE_JSON")) + String.valueOf(row.get("AFTER_JSON")))
        .reduce("", String::concat);
    assertThat(auditPayload)
        .contains("\"status\"")
        .contains("\"accountLinked\"")
        .doesNotContain("13800138000", "11010119900101123X", account.initialPassword(),
            "审计员工", "3500");
    assertThat(fixture.jdbc().queryForObject(
        "select status from employee where tenant_id = ? and id = ?", String.class, TENANT_ID, created.id()))
        .isEqualTo("离职");
    assertThat(fixture.jdbc().queryForObject(
        "select enabled from auth_user where tenant_id = ? and username = ?", Integer.class, TENANT_ID, account.username()))
        .isZero();
  }

  @Test
  void accountPasswordsAreUniqueStrongReturnedOnceAndPersistedOnlyAsHashes() {
    Fixture fixture = fixture();
    EmployeeResponse firstEmployee = fixture.service().create(
        fixture.boss(), request("随机密码员工甲", "在职", "店员"));
    EmployeeResponse secondEmployee = fixture.service().create(
        fixture.boss(), request("随机密码员工乙", "在职", "店员"));

    EmployeeAccountResponse first = fixture.service().createAccount(fixture.boss(), firstEmployee.id());
    EmployeeAccountResponse second = fixture.service().createAccount(fixture.boss(), secondEmployee.id());

    assertThat(first.initialPassword())
        .hasSize(20)
        .containsPattern("[A-Z]")
        .containsPattern("[a-z]")
        .containsPattern("[0-9]")
        .containsPattern("[!@#$%_-]");
    assertThat(second.initialPassword()).hasSize(20).isNotEqualTo(first.initialPassword());

    String firstHash = fixture.jdbc().queryForObject(
        "select password_hash from auth_user where tenant_id = ? and username = ?",
        String.class, TENANT_ID, first.username());
    String secondHash = fixture.jdbc().queryForObject(
        "select password_hash from auth_user where tenant_id = ? and username = ?",
        String.class, TENANT_ID, second.username());
    assertThat(fixture.jdbc().queryForObject(
        "select password_change_required from auth_user where tenant_id = ? and username = ?",
        Boolean.class, TENANT_ID, first.username())).isTrue();
    assertThat(fixture.jdbc().queryForObject(
        "select password_change_required from auth_user where tenant_id = ? and username = ?",
        Boolean.class, TENANT_ID, second.username())).isTrue();
    PasswordService passwords = new PasswordService();
    assertThat(firstHash).startsWith("pbkdf2$").doesNotContain(first.initialPassword());
    assertThat(secondHash).startsWith("pbkdf2$").doesNotContain(second.initialPassword());
    assertThat(passwords.matches(first.initialPassword(), firstHash)).isTrue();
    assertThat(passwords.matches(second.initialPassword(), secondHash)).isTrue();

    String auditPayload = String.join(" ", fixture.jdbc().queryForList("""
        select concat_ws(' ', reason, before_json, after_json)
        from operation_log
        where tenant_id = ? and target_type = 'employee'
          and target_id in (?, ?)
        order by id
        """, String.class, TENANT_ID, firstEmployee.id(), secondEmployee.id()));
    assertThat(auditPayload).doesNotContain(first.initialPassword(), second.initialPassword());
  }

  @Test
  void failedWritesLeaveNoEmployeeOrAuditRowsAndAccountLinkFailureRollsBack() {
    Fixture fixture = fixture();
    int employeesBefore = count(fixture.jdbc(), "employee");
    int logsBefore = count(fixture.jdbc(), "operation_log");

    assertThatThrownBy(() -> fixture.service().create(
        fixture.boss(), request("", "在职", "店员")))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getStatus())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(count(fixture.jdbc(), "employee")).isEqualTo(employeesBefore);
    assertThat(count(fixture.jdbc(), "operation_log")).isEqualTo(logsBefore);

    EmployeeResponse employee = fixture.service().create(fixture.boss(), request("回滚员工", "在职", "店员"));
    int usersBeforeLinkFailure = count(fixture.jdbc(), "auth_user");
    int logsBeforeLinkFailure = count(fixture.jdbc(), "operation_log");
    EmployeeRepository linkFailureRepository = new EmployeeRepository(
        fixture.jdbc(), fixture.namedJdbc()) {
      @Override
      public void linkAccount(long tenantId, String employeeId, long authUserId) {
        throw new BusinessException("LINK_FAILED", "测试关联失败", HttpStatus.CONFLICT);
      }
    };
    EmployeeService linkFailureService = fixture.service(linkFailureRepository);
    TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource()));

    assertThatThrownBy(() -> transaction.executeWithoutResult(
        status -> linkFailureService.createAccount(fixture.boss(), employee.id())))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("LINK_FAILED");

    assertThat(count(fixture.jdbc(), "auth_user")).isEqualTo(usersBeforeLinkFailure);
    assertThat(count(fixture.jdbc(), "operation_log")).isEqualTo(logsBeforeLinkFailure);
    assertThat(fixture.jdbc().queryForObject(
        "select auth_user_id from employee where tenant_id = ? and id = ?", Long.class, TENANT_ID, employee.id()))
        .isNull();
  }

  @Test
  void crossStoreAndCrossTenantAreForbiddenUnknownIdRemainsNotFoundAndDenialsAreAudited() {
    Fixture fixture = fixture();
    fixture.employeeRepository().upsertProfile(TENANT_ID, "emp-store-b", requestFor(STORE_B, "跨店员工"), "MANUAL_ENTRY");
    fixture.jdbc().update("""
        insert into tenant(id, name, industry, scale, status, created_at)
        values (2, 'EMP其他租户', 'test', 'test', 'ACTIVE', current_timestamp)
        """);
    fixture.jdbc().update("""
        insert into store_branch(id, tenant_id, code, name, status, created_at)
        values ('EMP_FOREIGN_STORE', 2, 'EMP-F', '其他租户门店', '营业中', current_timestamp)
        """);
    fixture.jdbc().update("""
        insert into employee(id, tenant_id, store_id, name, status, base_salary, created_at)
        values ('emp-foreign', 2, 'EMP_FOREIGN_STORE', '其他租户员工', '在职', 0, current_timestamp)
        """);
    AuthUser manager = new AuthUser(101L, TENANT_ID, "EMP测试租户", "emp_manager", "hash",
        "测试店长", "STORE_MANAGER", STORE_A, true, 1L);

    assertThatThrownBy(() -> fixture.service().detail(manager, "emp-store-b"))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getStatus())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThatThrownBy(() -> fixture.service().detail(fixture.boss(), "emp-foreign"))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getStatus())
        .isEqualTo(HttpStatus.FORBIDDEN);
    int logsAfterDenials = count(fixture.jdbc(), "operation_log");
    assertThatThrownBy(() -> fixture.service().detail(fixture.boss(), "emp-unknown"))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getStatus())
        .isEqualTo(HttpStatus.NOT_FOUND);

    assertThat(fixture.jdbc().queryForObject("""
        select count(*) from operation_log
        where tenant_id = 1 and action = 'permission_denied' and target_id in ('EMP_B', 'emp-foreign')
        """, Integer.class)).isEqualTo(2);
    assertThat(count(fixture.jdbc(), "operation_log")).isEqualTo(logsAfterDenials);
    assertThat(fixture.jdbc().queryForObject("""
        select reason from operation_log
        where tenant_id = 1 and action = 'permission_denied' and target_id = 'emp-foreign'
        """, String.class)).contains("员工档案不属于当前企业");
  }

  private Fixture fixture() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:employee_audit_" + UUID.randomUUID()
        + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
    jdbc.update("""
        insert into store_branch(id, tenant_id, code, name, status, created_at)
        values (?, 1, ?, ?, '营业中', current_timestamp), (?, 1, ?, ?, '营业中', current_timestamp)
        """, STORE_A, "EMP-A", "员工审计门店A", STORE_B, "EMP-B", "员工审计门店B");
    jdbc.update("""
        insert into auth_user(id, tenant_id, username, password_hash, display_name, role, store_id,
                              enabled, permission_version, created_at)
        values (100, 1, 'emp_boss', 'hash', '审计老板', 'BOSS', null, 1, 1, current_timestamp),
               (101, 1, 'emp_manager', 'hash', '测试店长', 'STORE_MANAGER', ?, 1, 1, current_timestamp)
        """, STORE_A);
    EmployeeRepository employeeRepository = new EmployeeRepository(jdbc, namedJdbc);
    AuthRepository authRepository = new AuthRepository(jdbc);
    AuditRepository auditRepository = new AuditRepository(jdbc);
    AccessControlService accessControl = new AccessControlService(
        org.mockito.Mockito.mock(AuthService.class), authRepository, auditRepository);
    AuthUser boss = new AuthUser(100L, TENANT_ID, "EMP测试租户", "emp_boss", "hash",
        "审计老板", "BOSS", null, true, 1L);
    return new Fixture(dataSource, jdbc, namedJdbc, employeeRepository, authRepository, auditRepository,
        accessControl, boss);
  }

  private EmployeeUpsertRequest request(String name, String status, String position) {
    return requestFor(STORE_A, name, status, position);
  }

  private EmployeeUpsertRequest requestFor(String storeId, String name) {
    return requestFor(storeId, name, "在职", "店员");
  }

  private EmployeeUpsertRequest requestFor(String storeId, String name, String status, String position) {
    return new EmployeeUpsertRequest(
        storeId, name, "13800138000", position, "全职", status, "2026-07-01", "7月21日",
        "11010119900101123X", "2026-01-01", "2027-01-01", "健康证", "2026-08-01",
        null, null, null, "审计测试备注", BigDecimal.valueOf(18));
  }

  private int count(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private record Fixture(
      DataSource dataSource,
      JdbcTemplate jdbc,
      NamedParameterJdbcTemplate namedJdbc,
      EmployeeRepository employeeRepository,
      AuthRepository authRepository,
      AuditRepository auditRepository,
      AccessControlService accessControl,
      AuthUser boss
  ) {
    EmployeeService service() {
      return service(employeeRepository);
    }

    EmployeeService service(EmployeeRepository repository) {
      return new EmployeeService(
          repository, accessControl, null, new PasswordService(), authRepository, auditRepository);
    }
  }
}
