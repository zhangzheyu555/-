package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.UUID;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WarehouseRequisitionSummaryExportTest {
  private final AuthUser boss =
      new AuthUser(1L, 1L, "default", "boss", "", "老板", "BOSS", null, true);
  private final AuthUser storeManager =
      new AuthUser(2L, 1L, "default", "manager", "", "一店店长", "STORE_MANAGER", "s1", true);
  private JdbcTemplate jdbcTemplate;
  private AuthService authService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbcTemplate = new JdbcTemplate(dataSource);
    createSchema();
    seedData();

    authService = mock(AuthService.class);
    WarehouseService warehouseService = new WarehouseService(new WarehouseRepository(jdbcTemplate));
    mockMvc = MockMvcBuilders.standaloneSetup(
            new WarehouseRequisitionReportController(authService, warehouseService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void exportsStoreProductMonthAggregationUsingRequestedQuantityAndOrderUnitPrice() throws Exception {
    when(authService.requireUser("Bearer boss")).thenReturn(boss);

    MvcResult result = mockMvc.perform(post("/api/warehouse/requests/export-summary")
            .header(HttpHeaders.AUTHORIZATION, "Bearer boss")
            .contentType("application/json")
            .content("""
                {
                  "startDate": "2026-06-01",
                  "endDate": "2026-06-30",
                  "periodType": "MONTH",
                  "groupBy": ["store", "product", "period"]
                }
                """))
        .andExpect(status().isOk())
        .andExpect(header().string(
            HttpHeaders.CONTENT_TYPE,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andReturn();

    try (var workbook = WorkbookFactory.create(
        new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
      var sheet = workbook.getSheet("叫货汇总");
      assertThat(sheet).isNotNull();
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("门店ID");
      assertThat(sheet.getRow(0).getCell(7).getStringCellValue()).isEqualTo("计量单位");
      assertThat(sheet.getRow(0).getCell(8).getStringCellValue()).isEqualTo("订货数量");
      assertThat(sheet.getRow(0).getCell(9).getStringCellValue()).isEqualTo("订货金额（元）");

      var firstDataRow = sheet.getRow(1);
      assertThat(firstDataRow.getCell(0).getStringCellValue()).isEqualTo("s1");
      assertThat(firstDataRow.getCell(1).getStringCellValue()).isEqualTo("一店");
      assertThat(firstDataRow.getCell(2).getStringCellValue()).isEqualTo("10");
      assertThat(firstDataRow.getCell(3).getStringCellValue()).isEqualTo("鲜奶");
      assertThat(firstDataRow.getCell(6).getStringCellValue()).isEqualTo("2026年06月");
      assertThat(firstDataRow.getCell(7).getStringCellValue()).isEqualTo("件");
      assertThat(firstDataRow.getCell(8).getNumericCellValue()).isEqualTo(5D);
      assertThat(firstDataRow.getCell(9).getNumericCellValue()).isEqualTo(25D);
    }
  }

  @Test
  void rejectsAnExplicitStoreOutsideTheUsersDataScope() throws Exception {
    when(authService.requireUser("Bearer manager")).thenReturn(storeManager);

    mockMvc.perform(post("/api/warehouse/requests/export-summary")
            .header(HttpHeaders.AUTHORIZATION, "Bearer manager")
            .contentType("application/json")
            .content("""
                {
                  "startDate": "2026-06-01",
                  "endDate": "2026-06-30",
                  "storeIds": ["s2"],
                  "periodType": "MONTH",
                  "groupBy": ["store", "product", "period"]
                }
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("REQUISITION_SUMMARY_STORE_FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("所选门店超出当前账号的数据范围：s2"));
  }

  @Test
  void filtersStoreAndProductIncludesTheEndDateAndExcludesRejectedRequests() throws Exception {
    when(authService.requireUser("Bearer boss")).thenReturn(boss);

    MvcResult result = mockMvc.perform(post("/api/warehouse/requests/export-summary")
            .header(HttpHeaders.AUTHORIZATION, "Bearer boss")
            .contentType("application/json")
            .content("""
                {
                  "startDate": "2026-06-01",
                  "endDate": "2026-06-30",
                  "storeIds": ["s1"],
                  "productIds": [20],
                  "periodType": "MONTH",
                  "groupBy": ["store", "product", "period"]
                }
                """))
        .andExpect(status().isOk())
        .andReturn();

    try (var workbook = WorkbookFactory.create(
        new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
      var sheet = workbook.getSheet("叫货汇总");
      assertThat(sheet.getLastRowNum()).isEqualTo(1);
      var onlyDataRow = sheet.getRow(1);
      assertThat(onlyDataRow.getCell(0).getStringCellValue()).isEqualTo("s1");
      assertThat(onlyDataRow.getCell(2).getStringCellValue()).isEqualTo("20");
      assertThat(onlyDataRow.getCell(8).getNumericCellValue()).isEqualTo(2D);
      assertThat(onlyDataRow.getCell(9).getNumericCellValue()).isEqualTo(6D);
    }
  }

  @Test
  void switchesBetweenDayIsoWeekAndMonthBucketsWithoutChangingTotals() throws Exception {
    when(authService.requireUser("Bearer boss")).thenReturn(boss);
    String[][] expectations = {
        {"DAY", "2026-06-03", "2026-06-03", "2026年06月03日"},
        {"WEEK", "2026-06-01", "2026-06-07", "2026年第23周"},
        {"MONTH", "2026-06-01", "2026-06-30", "2026年06月"}
    };

    for (String[] expectation : expectations) {
      MvcResult result = mockMvc.perform(post("/api/warehouse/requests/export-summary")
              .header(HttpHeaders.AUTHORIZATION, "Bearer boss")
              .contentType("application/json")
              .content("""
                  {
                    "startDate": "2026-06-03",
                    "endDate": "2026-06-03",
                    "storeIds": ["s1"],
                    "productIds": [10],
                    "periodType": "%s",
                    "groupBy": ["store", "product", "period"]
                  }
                  """.formatted(expectation[0])))
          .andExpect(status().isOk())
          .andReturn();

      try (var workbook = WorkbookFactory.create(
          new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
        var row = workbook.getSheet("叫货汇总").getRow(1);
        assertThat(row.getCell(4).getStringCellValue()).isEqualTo(expectation[1]);
        assertThat(row.getCell(5).getStringCellValue()).isEqualTo(expectation[2]);
        assertThat(row.getCell(6).getStringCellValue()).isEqualTo(expectation[3]);
        assertThat(row.getCell(8).getNumericCellValue()).isEqualTo(2D);
        assertThat(row.getCell(9).getNumericCellValue()).isEqualTo(10D);
      }
    }
  }

  @Test
  void exportsAHeaderOnlyWorkbookForNoDataAndWritesAnAuditLog() throws Exception {
    when(authService.requireUser("Bearer boss")).thenReturn(boss);

    MvcResult result = mockMvc.perform(post("/api/warehouse/requests/export-summary")
            .header(HttpHeaders.AUTHORIZATION, "Bearer boss")
            .contentType("application/json")
            .content("""
                {
                  "startDate": "2025-01-01",
                  "endDate": "2025-01-31",
                  "periodType": "MONTH",
                  "groupBy": ["store", "product", "period"]
                }
                """))
        .andExpect(status().isOk())
        .andReturn();

    try (var workbook = WorkbookFactory.create(
        new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
      var sheet = workbook.getSheet("叫货汇总");
      assertThat(sheet.getLastRowNum()).isZero();
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("门店ID");
      assertThat(sheet.getRow(0).getCell(9).getStringCellValue()).isEqualTo("订货金额（元）");
    }
    assertThat(jdbcTemplate.queryForObject("""
        select count(*)
        from operation_log
        where tenant_id = 1
          and action = '导出叫货汇总报表'
          and target_id = '20250101_20250131'
          and reason like '%汇总行数：0%'
        """, Integer.class)).isEqualTo(1);
  }

  @Test
  void includesAZeroQuantityCombinationWhenExplicitlyRequested() throws Exception {
    when(authService.requireUser("Bearer boss")).thenReturn(boss);

    MvcResult result = mockMvc.perform(post("/api/warehouse/requests/export-summary")
            .header(HttpHeaders.AUTHORIZATION, "Bearer boss")
            .contentType("application/json")
            .content("""
                {
                  "startDate": "2026-06-01",
                  "endDate": "2026-06-30",
                  "storeIds": ["s2"],
                  "productIds": [20],
                  "periodType": "MONTH",
                  "includeZeroRows": true,
                  "groupBy": ["store", "product", "period"]
                }
                """))
        .andExpect(status().isOk())
        .andReturn();

    try (var workbook = WorkbookFactory.create(
        new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
      var sheet = workbook.getSheet("叫货汇总");
      assertThat(sheet.getLastRowNum()).isEqualTo(1);
      var zeroRow = sheet.getRow(1);
      assertThat(zeroRow.getCell(0).getStringCellValue()).isEqualTo("s2");
      assertThat(zeroRow.getCell(1).getStringCellValue()).isEqualTo("二店");
      assertThat(zeroRow.getCell(2).getStringCellValue()).isEqualTo("20");
      assertThat(zeroRow.getCell(3).getStringCellValue()).isEqualTo("吸管");
      assertThat(zeroRow.getCell(6).getStringCellValue()).isEqualTo("2026年06月");
      assertThat(zeroRow.getCell(8).getNumericCellValue()).isZero();
      assertThat(zeroRow.getCell(9).getNumericCellValue()).isZero();
    }
  }

  @Test
  void rejectsAnInvertedDateRange() throws Exception {
    when(authService.requireUser("Bearer boss")).thenReturn(boss);

    mockMvc.perform(post("/api/warehouse/requests/export-summary")
            .header(HttpHeaders.AUTHORIZATION, "Bearer boss")
            .contentType("application/json")
            .content("""
                {
                  "startDate": "2026-06-30",
                  "endDate": "2026-06-01",
                  "periodType": "MONTH",
                  "groupBy": ["store", "product", "period"]
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REQUISITION_SUMMARY_DATE_INVALID"))
        .andExpect(jsonPath("$.message").value("开始日期不能晚于结束日期"));
  }

  @Test
  void rejectsAnExcessiveZeroRowCartesianProduct() throws Exception {
    when(authService.requireUser("Bearer boss")).thenReturn(boss);

    mockMvc.perform(post("/api/warehouse/requests/export-summary")
            .header(HttpHeaders.AUTHORIZATION, "Bearer boss")
            .contentType("application/json")
            .content("""
                {
                  "startDate": "2000-01-01",
                  "endDate": "2200-01-01",
                  "storeIds": ["s1"],
                  "productIds": [10],
                  "periodType": "DAY",
                  "includeZeroRows": true,
                  "groupBy": ["store", "product", "period"]
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REQUISITION_SUMMARY_ZERO_ROWS_LIMIT"))
        .andExpect(jsonPath("$.message")
            .value("包含零量行后的组合超过 50000 行，请缩小日期范围或筛选门店、物料"));
  }

  private void createSchema() {
    jdbcTemplate.execute("""
        create table store_branch (
          id varchar(64) primary key,
          tenant_id bigint not null,
          name varchar(160) not null
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_item (
          id bigint primary key,
          tenant_id bigint not null,
          name varchar(160) not null,
          unit varchar(40)
        )
        """);
    jdbcTemplate.execute("""
        create table store_requisition (
          id varchar(120) primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          status varchar(40) not null,
          submitted_at timestamp not null
        )
        """);
    jdbcTemplate.execute("""
        create table store_requisition_line (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          requisition_id varchar(120) not null,
          item_id bigint not null,
          requested_quantity decimal(14, 2) not null,
          unit_price decimal(14, 2) not null,
          amount decimal(14, 2) not null
        )
        """);
    jdbcTemplate.execute("""
        create table operation_log (
          id bigint auto_increment primary key,
          tenant_id bigint,
          operator_id bigint,
          operator_name varchar(120),
          action varchar(80),
          target_type varchar(80),
          target_id varchar(120),
          store_id varchar(64),
          reason varchar(255),
          created_at timestamp default current_timestamp
        )
        """);
  }

  private void seedData() {
    jdbcTemplate.update("""
        insert into store_branch(id, tenant_id, name)
        values ('s1', 1, '一店'), ('s2', 1, '二店')
        """);
    jdbcTemplate.update("""
        insert into warehouse_item(id, tenant_id, name, unit)
        values (10, 1, '鲜奶', '件'), (20, 1, '吸管', '箱')
        """);
    insertRequisition("R1", "s1", "SUBMITTED", "2026-06-03 10:00:00");
    insertLine("R1", 10L, "2.00", "5.00", "10.00");
    insertRequisition("R2", "s1", "RECEIVED", "2026-06-18 15:30:00");
    // amount 会在部分批准时被改写；汇总申请金额必须仍按 3 * 下单单价 5 计算。
    insertLine("R2", 10L, "3.00", "5.00", "5.00");
    insertRequisition("R3", "s2", "SUBMITTED", "2026-06-08 09:00:00");
    insertLine("R3", 10L, "4.00", "5.00", "20.00");
    insertRequisition("R4", "s1", "SUBMITTED", "2026-07-01 09:00:00");
    insertLine("R4", 20L, "7.00", "3.00", "21.00");
    insertRequisition("R5", "s1", "REJECTED", "2026-06-20 09:00:00");
    insertLine("R5", 20L, "100.00", "3.00", "300.00");
    insertRequisition("R6", "s1", "SUBMITTED", "2026-06-30 23:59:59");
    insertLine("R6", 20L, "2.00", "3.00", "6.00");
  }

  private void insertRequisition(String id, String storeId, String status, String submittedAt) {
    jdbcTemplate.update("""
        insert into store_requisition(id, tenant_id, store_id, status, submitted_at)
        values (?, 1, ?, ?, ?)
        """, id, storeId, status, submittedAt);
  }

  private void insertLine(
      String requisitionId,
      long itemId,
      String quantity,
      String unitPrice,
      String amount
  ) {
    jdbcTemplate.update("""
        insert into store_requisition_line(
          tenant_id, requisition_id, item_id, requested_quantity, unit_price, amount
        ) values (1, ?, ?, ?, ?, ?)
        """,
        requisitionId,
        itemId,
        new BigDecimal(quantity),
        new BigDecimal(unitPrice),
        new BigDecimal(amount)
    );
  }
}
