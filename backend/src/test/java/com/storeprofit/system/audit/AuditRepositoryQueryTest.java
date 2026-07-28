package com.storeprofit.system.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.BusinessException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class AuditRepositoryQueryTest {
  private JdbcTemplate jdbc;
  private AuditRepository repository;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create alias if not exists date_format for '"
        + AuditRepositoryQueryTest.class.getName() + ".dateFormat'");
    jdbc.execute("""
        create table operation_log (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          operator_id bigint,
          operator_name varchar(120),
          action varchar(80) not null,
          target_type varchar(80) not null,
          target_id varchar(120),
          store_id varchar(64),
          month varchar(7),
          reason varchar(255),
          created_at timestamp not null
        )
        """);

    insert(1, 1, "老板", "warehouse_item_delete", "仓库商品", "421",
        null, null, "删除测试物料", "2026-07-27 23:59:59");
    insert(1, 2, "店长·荆州之星店", "warehouse_requisition_submit", "仓库单据", "REQ-001",
        "rg1", "2026-07", "门店日常叫货", "2026-07-26 09:20:00");
    insert(1, 3, "财务", "salary_generate", "工资", "SALARY-001",
        "rg2", "2026-06", "生成工资", "2026-06-30 18:00:00");
    insert(2, 4, "其他租户老板", "warehouse_requisition_submit", "仓库单据", "REQ-OTHER",
        "rg1", "2026-07", "不应泄露", "2026-07-26 10:00:00");

    repository = new AuditRepository(jdbc);
  }

  @Test
  void combinedFiltersAreAndedAndEndDateIncludesTheWholeDay() {
    OperationLogQueryResponse result = repository.search(1L, new OperationLogQuery(
        "日常", "店长·荆州之星店", "warehouse_requisition_submit",
        "STORE", "rg1", LocalDate.parse("2026-07-26"), LocalDate.parse("2026-07-26"), 1, 20));

    assertThat(result.rows()).extracting(OperationLogResponse::targetId).containsExactly("REQ-001");
    assertThat(result.total()).isEqualTo(1);
    assertThat(result.page()).isEqualTo(1);
    assertThat(result.totalPages()).isEqualTo(1);
    assertThat(result.operators()).contains("老板", "店长·荆州之星店", "财务")
        .doesNotContain("其他租户老板");
    assertThat(result.actions()).contains(
        "warehouse_item_delete", "warehouse_requisition_submit", "salary_generate");
  }

  @Test
  void globalScopeMatchesOnlyLogsWithoutAStore() {
    OperationLogQueryResponse result = repository.search(1L, new OperationLogQuery(
        "", "", "", "GLOBAL", "", null, null, 1, 20));

    assertThat(result.rows()).extracting(OperationLogResponse::operatorName).containsExactly("老板");
  }

  @Test
  void paginationIsStableNewestFirstAndNeverLeaksOtherTenants() {
    OperationLogQueryResponse firstPage = repository.search(1L, new OperationLogQuery(
        "", "", "", "ALL", "", null, null, 1, 2));
    OperationLogQueryResponse secondPage = repository.search(1L, new OperationLogQuery(
        "", "", "", "ALL", "", null, null, 2, 2));
    OperationLogQueryResponse pageBeyondLast = repository.search(1L, new OperationLogQuery(
        "", "", "", "ALL", "", null, null, 99, 2));

    assertThat(firstPage.rows()).extracting(OperationLogResponse::operatorName)
        .containsExactly("老板", "店长·荆州之星店");
    assertThat(secondPage.rows()).extracting(OperationLogResponse::operatorName)
        .containsExactly("财务");
    assertThat(firstPage.total()).isEqualTo(3);
    assertThat(secondPage.totalPages()).isEqualTo(2);
    assertThat(pageBeyondLast.page()).isEqualTo(2);
    assertThat(pageBeyondLast.rows()).extracting(OperationLogResponse::operatorName)
        .containsExactly("财务");
  }

  @Test
  void invalidRangeAndIncompleteStoreScopeAreRejectedBeforeSqlRuns() {
    assertThatThrownBy(() -> new OperationLogQuery(
        "", "", "", "STORE", "", null, null, 1, 20))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("门店");

    assertThatThrownBy(() -> new OperationLogQuery(
        "", "", "", "ALL", "", LocalDate.parse("2026-07-27"), LocalDate.parse("2026-07-26"), 1, 20))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("开始日期");
  }

  private void insert(
      long tenantId,
      long operatorId,
      String operatorName,
      String action,
      String targetType,
      String targetId,
      String storeId,
      String month,
      String reason,
      String createdAt
  ) {
    jdbc.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, reason, created_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, tenantId, operatorId, operatorName, action, targetType, targetId,
        storeId, month, reason, Timestamp.valueOf(createdAt));
  }

  public static String dateFormat(Timestamp value, String pattern) {
    if (value == null) return null;
    return value.toLocalDateTime().toString().replace('T', ' ');
  }
}
