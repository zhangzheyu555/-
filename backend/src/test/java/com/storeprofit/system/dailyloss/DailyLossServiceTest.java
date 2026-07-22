package com.storeprofit.system.dailyloss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.storage.StorageService;
import com.storeprofit.system.warehouse.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DailyLossServiceTest {
  @Test
  void approvalWritesExactlyOneLossOutMovementAndDoesNotTouchProfitLedger() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    WarehouseRepository warehouse = mock(WarehouseRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuditRepository audit = mock(AuditRepository.class);
    AuthUser reviewer = user("SUPERVISOR", null);
    DailyLossRepository.LockedLossRow locked = new DailyLossRepository.LockedLossRow(
        "loss-1", "s1", 11L, new BigDecimal("2.50"), "临期变质", "SUBMITTED");
    DailyLossRepository.DailyLossRow approved = row("APPROVED");
    when(access.dataScope(reviewer, DataScopeDomains.STORE)).thenReturn(DataScope.all());
    when(repository.findForUpdate(1L, "loss-1")).thenReturn(Optional.of(locked));
    when(repository.insertInventoryApplication(1L, locked, reviewer.id())).thenReturn(true);
    when(warehouse.subtractStoreInventoryIfEnough(
        eq(1L), eq("s1"), eq(11L), eq(new BigDecimal("2.50")), eq("LOSS_OUT"),
        eq("DAILY_LOSS"), eq("loss-1"), eq("临期变质"), eq(reviewer.id())
    )).thenReturn(true);
    when(repository.find(1L, "loss-1")).thenReturn(Optional.of(approved));
    when(repository.inventoryApplicationExists(1L, "loss-1")).thenReturn(true);
    when(repository.attachments(1L, "loss-1")).thenReturn(List.of());

    DailyLossResponse response = service(repository, warehouse, access, audit).approve(
        reviewer, "loss-1", new DailyLossReviewRequest("库存已核对"));

    assertThat(response.status()).isEqualTo("APPROVED");
    assertThat(response.inventoryDeducted()).isTrue();
    verify(repository).markApproved(1L, "loss-1", reviewer.id(), "库存已核对");
    verify(warehouse).subtractStoreInventoryIfEnough(
        1L, "s1", 11L, new BigDecimal("2.50"), "LOSS_OUT", "DAILY_LOSS", "loss-1", "临期变质", reviewer.id());
  }

  @Test
  void repeatedApprovalReturnsExistingResultWithoutAnotherInventoryDeduction() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    WarehouseRepository warehouse = mock(WarehouseRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuthUser reviewer = user("SUPERVISOR", null);
    DailyLossRepository.LockedLossRow locked = new DailyLossRepository.LockedLossRow(
        "loss-1", "s1", 11L, new BigDecimal("2.50"), "临期变质", "APPROVED");
    when(access.dataScope(reviewer, DataScopeDomains.STORE)).thenReturn(DataScope.all());
    when(repository.findForUpdate(1L, "loss-1")).thenReturn(Optional.of(locked));
    when(repository.inventoryApplicationExists(1L, "loss-1")).thenReturn(true);
    when(repository.find(1L, "loss-1")).thenReturn(Optional.of(row("APPROVED")));
    when(repository.attachments(1L, "loss-1")).thenReturn(List.of());

    DailyLossResponse response = service(repository, warehouse, access, mock(AuditRepository.class)).approve(
        reviewer, "loss-1", new DailyLossReviewRequest(null));

    assertThat(response.inventoryDeducted()).isTrue();
    verify(warehouse, never()).subtractStoreInventoryIfEnough(
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void forgedCrossStoreReadIsDeniedBeforeAttachmentsAreLoaded() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuthUser finance = user("FINANCE", null);
    when(access.dataScope(finance, DataScopeDomains.FINANCE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("s1")));
    when(repository.find(1L, "loss-2")).thenReturn(Optional.of(rowForStore("s2", "SUBMITTED")));
    doThrow(new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN))
        .when(access).requireStoreAccess(finance, DataScopeDomains.FINANCE, "s2", "查看每日报损");

    BusinessException error = catchThrowableOfType(
        () -> service(repository, mock(WarehouseRepository.class), access, mock(AuditRepository.class)).get(finance, "loss-2"),
        BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    verify(repository, never()).attachments(1L, "loss-2");
  }

  @Test
  void bossCanReadAllDailyLossReportsWithoutStoreBinding() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuditRepository audit = mock(AuditRepository.class);
    AuthUser boss = user("BOSS", null);
    DataScope scope = DataScope.all();
    when(access.dataScope(boss, DataScopeDomains.FINANCE)).thenReturn(scope);
    when(repository.reportStores(1L, null, scope)).thenReturn(List.of());
    when(repository.reports(1L, java.time.YearMonth.of(2026, 7), null, scope)).thenReturn(List.of());

    List<DailyLossReportResponse> reports = service(
        repository, mock(WarehouseRepository.class), access, audit).reports(boss, null, "2026-07");

    assertThat(reports).isEmpty();
    verify(audit, never()).writePermissionDenied(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void reportWorkflowExportsMonthlyExcelWithMissingDatesNumericCellsAndNoAttachments() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    createReportSchema(jdbcTemplate);
    DailyLossRepository repository = new DailyLossRepository(
        jdbcTemplate,
        new NamedParameterJdbcTemplate(dataSource)
    );
    AccessControlService access = mock(AccessControlService.class);
    AuthUser manager = user("STORE_MANAGER", "s1");
    AuthUser supervisor = user("SUPERVISOR", null);
    AuthUser finance = user("FINANCE", null);
    when(access.dataScope(manager, DataScopeDomains.STORE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("s1")));
    when(access.dataScope(supervisor, DataScopeDomains.STORE)).thenReturn(DataScope.all());
    when(access.dataScope(finance, DataScopeDomains.FINANCE)).thenReturn(DataScope.all());
    DailyLossService dailyLossService = new DailyLossService(
        repository,
        mock(WarehouseRepository.class),
        mock(StorageService.class),
        access,
        new AuditRepository(jdbcTemplate)
    );

    DailyLossReportResponse draft = dailyLossService.saveReport(manager, new DailyLossReportSaveRequest(
        "s1",
        LocalDate.of(2026, 7, 14),
        List.of(
            new DailyLossReportLineRequest(1L, new BigDecimal("10.0000"), "=恶意公式"),
            new DailyLossReportLineRequest(2L, new BigDecimal("2"), "整颗报损")
        )
    ));
    jdbcTemplate.update("""
        insert into warehouse_attachment(
          tenant_id, store_id, business_type, business_id, file_name, content_type, file_size, content, uploaded_at
        ) values
          (1, 's1', 'DAILY_LOSS', ?, 'photo.jpg', 'image/jpeg', 3, ?, current_timestamp),
          (1, 's1', 'DAILY_LOSS', ?, 'detail.csv', 'text/csv', 3, ?, current_timestamp),
          (1, 's1', 'DAILY_LOSS', ?, 'receipt.pdf', 'application/pdf', 3, ?, current_timestamp)
        """, draft.id(), new byte[]{1, 2, 3}, draft.id(), new byte[]{4, 5, 6}, draft.id(), new byte[]{7, 8, 9});

    DailyLossReportResponse submitted = dailyLossService.submitReport(manager, draft.id());
    DailyLossReportResponse reviewed = dailyLossService.reviewReport(
        supervisor, draft.id(), new DailyLossReviewRequest("照片已核对"));
    int reportsBeforeRepeatExport = jdbcTemplate.queryForObject(
        "select count(*) from daily_loss_report", Integer.class);
    int detailsBeforeRepeatExport = jdbcTemplate.queryForObject(
        "select count(*) from daily_loss_record", Integer.class);
    DailyLossMonthlyExcelExport export = dailyLossService.exportMonthlyExcel(finance, "s1", "2026-07");
    DailyLossMonthlyExcelExport repeatedExport = dailyLossService.exportMonthlyExcel(finance, "s1", "2026-07");

    assertThat(draft.totalAmount()).isEqualByComparingTo("50.14");
    assertThat(draft.details()).extracting(DailyLossReportDetailResponse::amountSnapshot)
        .containsExactlyInAnyOrder(new BigDecimal("0.14"), new BigDecimal("50.00"));
    assertThat(submitted.status()).isEqualTo("SUBMITTED");
    assertThat(reviewed.status()).isEqualTo("REVIEWED");
    assertThat(export.fileName()).isEqualTo("测试门店-2026年07月-每日报损.xlsx");
    assertThat(export.summaryRowCount()).isEqualTo(31);
    assertThat(export.detailRowCount()).isEqualTo(2);
    assertThat(repeatedExport.summaryRowCount()).isEqualTo(31);
    assertThat(repeatedExport.detailRowCount()).isEqualTo(2);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(export.content()))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
      assertThat(workbook.getSheetName(0)).isEqualTo("每日汇总");
      assertThat(workbook.getSheetName(1)).isEqualTo("报损明细");
      assertThat(workbook.getAllPictures()).isEmpty();
      assertThat(workbook.getSheetAt(0).getDrawingPatriarch()).isNull();
      assertThat(workbook.getSheetAt(1).getDrawingPatriarch()).isNull();

      var summary = workbook.getSheet("每日汇总");
      var details = workbook.getSheet("报损明细");
      assertThat(summary.getLastRowNum()).isEqualTo(31);
      assertThat(summary.getPaneInformation().isFreezePane()).isTrue();
      assertThat(summary.getCTWorksheet().isSetAutoFilter()).isTrue();
      assertThat(summary.getRow(1).getCell(5).getNumericCellValue()).isZero();
      assertThat(summary.getRow(14).getCell(3).getNumericCellValue()).isEqualTo(2D);
      assertThat(summary.getRow(14).getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(summary.getRow(14).getCell(5).getNumericCellValue()).isEqualTo(50.14D);
      assertThat(summary.getRow(14).getCell(5).getCellStyle().getDataFormatString()).contains("#,##0.00");
      assertThat(details.getLastRowNum()).isEqualTo(2);
      assertThat(details.getRow(1).getCell(6).getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(details.getRow(1).getCell(9).getCellType()).isEqualTo(CellType.NUMERIC);
      var formulaRow = java.util.stream.IntStream.rangeClosed(1, details.getLastRowNum())
          .mapToObj(details::getRow)
          .filter(row -> "苹果汁".equals(row.getCell(4).getStringCellValue()))
          .findFirst()
          .orElseThrow();
      assertThat(formulaRow.getCell(10).getStringCellValue()).isEqualTo("'=恶意公式");
      assertThat(formulaRow.getCell(10).getStringCellValue()).doesNotContain("photo.jpg", "/api/storage", ".zip");
    }
    assertThat(jdbcTemplate.queryForList(
        "select action from operation_log order by id", String.class))
        .contains("daily_loss_submit", "daily_loss_review", "daily_loss_export");
    assertThat(jdbcTemplate.queryForObject("select count(*) from daily_loss_report", Integer.class))
        .isEqualTo(reportsBeforeRepeatExport);
    assertThat(jdbcTemplate.queryForObject("select count(*) from daily_loss_record", Integer.class))
        .isEqualTo(detailsBeforeRepeatExport);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'daily_loss_export'", Integer.class)).isEqualTo(2);
  }

  @Test
  void monthlyExcelExportIncludesEveryUnreportedDayForAnEmptyMonth() throws Exception {
    DriverManagerDataSource dataSource = reportDataSource();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    createReportSchema(jdbcTemplate);
    DailyLossRepository repository = new DailyLossRepository(jdbcTemplate, new NamedParameterJdbcTemplate(dataSource));
    AccessControlService access = mock(AccessControlService.class);
    AuthUser finance = user("FINANCE", null);
    when(access.dataScope(finance, DataScopeDomains.FINANCE)).thenReturn(DataScope.all());
    DailyLossMonthlyExcelExport export = service(repository, mock(WarehouseRepository.class), access,
        new AuditRepository(jdbcTemplate)).exportMonthlyExcel(finance, null, "2026-08");

    try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(export.content()))) {
      assertThat(workbook.getSheet("每日汇总").getLastRowNum()).isEqualTo(31);
      assertThat(workbook.getSheet("报损明细").getLastRowNum()).isZero();
      assertThat(workbook.getSheet("每日汇总").getRow(1).getCell(6).getStringCellValue()).isEqualTo("未报");
      assertThat(workbook.getSheet("每日汇总").getRow(1).getCell(5).getNumericCellValue()).isZero();
    }
  }

  @Test
  void monthlyExcelExportUsesAuthenticatedTenantAndRejectsCrossStoreRequest() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuthUser finance = new AuthUser(8L, 2L, "第二租户", "finance", "hash", "财务", "FINANCE", null, true, 1L);
    when(access.dataScope(finance, DataScopeDomains.FINANCE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("s1")));
    doThrow(new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN))
        .when(access).requireStoreAccess(finance, DataScopeDomains.FINANCE, "s2", "导出每日报损");

    BusinessException error = catchThrowableOfType(
        () -> service(repository, mock(WarehouseRepository.class), access, mock(AuditRepository.class))
            .exportMonthlyExcel(finance, "s2", "2026-07"),
        BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    verify(repository, never()).reportStores(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void monthlyExcelExportQueriesOnlyTheAuthenticatedTenant() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuditRepository audit = mock(AuditRepository.class);
    AuthUser finance = new AuthUser(9L, 2L, "第二租户", "finance", "hash", "财务", "FINANCE", null, true, 1L);
    DataScope scope = DataScope.all();
    when(access.dataScope(finance, DataScopeDomains.FINANCE)).thenReturn(scope);
    when(repository.reportStores(2L, null, scope)).thenReturn(List.of());
    when(repository.reports(2L, java.time.YearMonth.of(2026, 7), null, scope)).thenReturn(List.of());
    when(repository.monthlyExportDetails(2L, java.time.YearMonth.of(2026, 7), null, scope)).thenReturn(List.of());

    service(repository, mock(WarehouseRepository.class), access, audit)
        .exportMonthlyExcel(finance, null, "2026-07");

    verify(repository).reportStores(2L, null, scope);
    verify(repository).reports(2L, java.time.YearMonth.of(2026, 7), null, scope);
    verify(repository).monthlyExportDetails(2L, java.time.YearMonth.of(2026, 7), null, scope);
    verify(repository, never()).reportStores(eq(1L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void monthlyExcelExportRejectsNonFinanceRolesDespiteMisconfiguredPermissionsBeforeRepositoryAccess() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    AuditRepository audit = mock(AuditRepository.class);
    AuthorizationService authorization = mock(AuthorizationService.class);
    AccessControlService access = new AccessControlService(
        mock(AuthService.class), mock(AuthRepository.class), audit, authorization, mock(DataScopeService.class));
    DailyLossService dailyLossService = service(repository, mock(WarehouseRepository.class), access, audit);

    for (AuthUser denied : List.of(
        user("STORE_MANAGER", "s1"), user("SUPERVISOR", null), user("WAREHOUSE", null), user("EMPLOYEE", "s1")
    )) {
      when(authorization.hasPermission(denied, com.storeprofit.system.platform.authorization.PermissionCodes.DAILY_LOSS_EXPORT))
          .thenReturn(true);
      BusinessException error = catchThrowableOfType(
          () -> dailyLossService.exportMonthlyExcel(denied, "s1", "2026-07"), BusinessException.class);

      assertThat(error).isNotNull();
      assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(error.getCode()).isEqualTo("FORBIDDEN");
    }
    verify(repository, never()).reportStores(
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void deniedSupervisorReviewStopsBeforeRepositoryAccess() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuthUser supervisor = user("SUPERVISOR", null);
    doThrow(new BusinessException("FORBIDDEN", "每日报损复核仅限督导或老板", HttpStatus.FORBIDDEN))
        .when(access).requireDailyLossReview(supervisor);

    BusinessException error = catchThrowableOfType(
        () -> service(repository, mock(WarehouseRepository.class), access, mock(AuditRepository.class))
            .reviewReport(supervisor, "report-1", new DailyLossReviewRequest("OK")),
        BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    verify(repository, never()).findReportForUpdate(1L, "report-1");
  }

  @Test
  void activeItemsReturnsEmptyListForAuthorizedManagerAndSupervisorWhenConfigIsEmpty() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuthUser manager = user("STORE_MANAGER", "s1");
    AuthUser supervisor = user("SUPERVISOR", null);
    when(repository.activeItems(1L)).thenReturn(List.of());
    DailyLossService dailyLossService = service(
        repository, mock(WarehouseRepository.class), access, mock(AuditRepository.class));

    assertThat(dailyLossService.activeItems(manager)).isEmpty();
    assertThat(dailyLossService.activeItems(supervisor)).isEmpty();

    verify(access).requireDailyLossRead(manager);
    verify(access).requireDailyLossRead(supervisor);
  }

  @Test
  void activeItemsPreferWarehouseCategoryAndFallbackToLegacyCategoryWhenUnmapped() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    createReportSchema(jdbcTemplate);
    DailyLossRepository repository = new DailyLossRepository(
        jdbcTemplate,
        new NamedParameterJdbcTemplate(dataSource)
    );
    jdbcTemplate.update("""
        insert into warehouse_item_category(tenant_id, name, parent_id, sort_order, enabled, created_at)
        values (1, '水果', null, 30, true, current_timestamp)
        """);
    Long fruitCategoryId = jdbcTemplate.queryForObject(
        "select id from warehouse_item_category where tenant_id = 1 and name = '水果'", Long.class);
    jdbcTemplate.update("update loss_item_config set warehouse_category_id = ? where id = 1", fruitCategoryId);

    List<DailyLossItemResponse> items = repository.activeItems(1L);

    DailyLossItemResponse mapped = items.stream().filter(item -> item.id() == 1L).findFirst().orElseThrow();
    DailyLossItemResponse fallback = items.stream().filter(item -> item.id() == 2L).findFirst().orElseThrow();
    assertThat(mapped.categoryName()).isEqualTo("水果");
    assertThat(mapped.categoryCode()).isEqualTo("WAREHOUSE_CATEGORY_" + fruitCategoryId);
    assertThat(mapped.category()).isNotEqualTo(mapped.categoryName());
    assertThat(fallback.categoryName()).isEqualTo(fallback.category());
    assertThat(fallback.categoryCode()).isEqualTo(fallback.category());
  }

  @Test
  void submittedReportRequiresAtLeastOnePhoto() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    createReportSchema(jdbcTemplate);
    DailyLossRepository repository = new DailyLossRepository(
        jdbcTemplate,
        new NamedParameterJdbcTemplate(dataSource)
    );
    AccessControlService access = mock(AccessControlService.class);
    AuthUser manager = user("STORE_MANAGER", "s1");
    when(access.dataScope(manager, DataScopeDomains.STORE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("s1")));
    DailyLossService dailyLossService = new DailyLossService(
        repository,
        mock(WarehouseRepository.class),
        mock(StorageService.class),
        access,
        new AuditRepository(jdbcTemplate)
    );
    DailyLossReportResponse draft = dailyLossService.saveReport(manager, new DailyLossReportSaveRequest(
        "s1",
        LocalDate.of(2026, 7, 14),
        List.of(new DailyLossReportLineRequest(1L, new BigDecimal("1.0000"), "照片缺失"))
    ));

    BusinessException error = catchThrowableOfType(
        () -> dailyLossService.submitReport(manager, draft.id()),
        BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(error.getCode()).isEqualTo("DAILY_LOSS_ATTACHMENT_REQUIRED");
    assertThat(jdbcTemplate.queryForList("select action from operation_log", String.class)).isEmpty();
  }

  private DailyLossService service(
      DailyLossRepository repository,
      WarehouseRepository warehouse,
      AccessControlService access,
      AuditRepository audit
  ) {
    return new DailyLossService(repository, warehouse, mock(StorageService.class), access, audit);
  }

  private DailyLossRepository.DailyLossRow row(String status) {
    return rowForStore("s1", status);
  }

  private DailyLossRepository.DailyLossRow rowForStore(String storeId, String status) {
    return new DailyLossRepository.DailyLossRow(
        storeId.equals("s1") ? "loss-1" : "loss-2", storeId, "001", "测试门店", LocalDate.of(2026, 7, 14),
        11L, "ITEM-11", "牛奶", "盒", new BigDecimal("2.50"), new BigDecimal("3.2000"),
        new BigDecimal("8.00"), "临期变质", status, 6L, "店长", LocalDateTime.of(2026, 7, 14, 9, 0),
        "APPROVED".equals(status) ? 7L : null, "APPROVED".equals(status) ? "财务" : null,
        "APPROVED".equals(status) ? LocalDateTime.of(2026, 7, 14, 10, 0) : null, null);
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(7L, 1L, "测试租户", "tester", "hash", "测试人员", role, storeId, true, 1L);
  }

  private void createReportSchema(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.execute("""
        create table store_branch (
          id varchar(64) primary key,
          tenant_id bigint not null,
          code varchar(80),
          name varchar(160) not null
        )
        """);
    jdbcTemplate.execute("""
        create table auth_user (
          id bigint not null,
          tenant_id bigint not null,
          display_name varchar(120),
          primary key(id, tenant_id)
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_item_category (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          name varchar(120) not null,
          parent_id bigint,
          sort_order int not null default 0,
          enabled tinyint not null default 1,
          created_at timestamp default current_timestamp,
          updated_at timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table loss_item_config (
          id bigint primary key,
          tenant_id bigint not null,
          item_code varchar(80) not null,
          item_name varchar(160) not null,
          category varchar(120),
          warehouse_category_id bigint,
          unit varchar(20) not null,
          unit_price decimal(14,4) not null,
          source_sheet varchar(80),
          active tinyint not null default 1
        )
        """);
    jdbcTemplate.execute("""
        create table daily_loss_report (
          id varchar(120) primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          loss_date date not null,
          status varchar(40) not null,
          submitted_by bigint,
          submitted_at timestamp,
          reviewed_by bigint,
          reviewed_at timestamp,
          review_note varchar(500),
          created_at timestamp default current_timestamp,
          updated_at timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table daily_loss_record (
          id varchar(120) primary key,
          report_id varchar(120),
          tenant_id bigint not null,
          store_id varchar(64) not null,
          loss_date date not null,
          item_id bigint,
          item_config_id bigint,
          item_code varchar(80),
          item_name varchar(160),
          stock_unit varchar(20),
          loss_quantity decimal(14,4),
          unit_price_snapshot decimal(14,4),
          amount_snapshot decimal(14,2),
          loss_reason varchar(500),
          status varchar(40),
          submitted_by bigint,
          submitted_at timestamp,
          reviewed_by bigint,
          reviewed_at timestamp,
          review_note varchar(500),
          created_at timestamp default current_timestamp,
          updated_at timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_attachment (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          store_id varchar(64),
          business_type varchar(60) not null,
          business_id varchar(120) not null,
          file_name varchar(255) not null,
          content_type varchar(120),
          file_size bigint,
          content blob,
          uploaded_at timestamp default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table operation_log (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          operator_id bigint,
          operator_name varchar(120),
          action varchar(80) not null,
          target_type varchar(80),
          target_id varchar(120),
          store_id varchar(64),
          month char(7),
          before_json text,
          after_json text,
          reason varchar(255),
          created_at timestamp default current_timestamp
        )
        """);
    jdbcTemplate.update("insert into store_branch(id, tenant_id, code, name) values ('s1', 1, '001', '测试门店')");
    jdbcTemplate.update("insert into auth_user(id, tenant_id, display_name) values (7, 1, '测试人员')");
    jdbcTemplate.update("""
        insert into loss_item_config(
          id, tenant_id, item_code, item_name, category, unit, unit_price, source_sheet, active
        ) values
          (1, 1, 'daily-apple-juice', '苹果汁', '每日报损表', '克', 0.0140, '每日报损表', 1),
          (2, 1, 'fruit-pineapple', '凤梨', '水果检查表', '个', 25.0000, '水果检查表', 1)
        """);
  }

  private DriverManagerDataSource reportDataSource() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
  }
}
