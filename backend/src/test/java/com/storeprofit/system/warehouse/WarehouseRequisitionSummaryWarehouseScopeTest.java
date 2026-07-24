package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WarehouseRequisitionSummaryWarehouseScopeTest {
  private static final long TENANT_ID = 1L;

  private JdbcTemplate jdbc;
  private WarehouseRepository warehouseRepository;
  private WarehouseService warehouseService;
  private AuthUser regionalManager;
  private long centralWarehouseId;
  private long regionalWarehouseId;
  private long itemId;

  @BeforeEach
  void setUp() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(("""
        jdbc:h2:mem:requisition-summary-scope-%s;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """).formatted(UUID.randomUUID()).replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");
    migrate(dataSource);

    jdbc = new JdbcTemplate(dataSource);
    warehouseRepository = new WarehouseRepository(jdbc);
    WarehouseTopologyRepository topologyRepository = new WarehouseTopologyRepository(jdbc);
    AccessControlService accessControl = mock(AccessControlService.class);
    BusinessScopeResolver businessScopeResolver = mock(BusinessScopeResolver.class);
    WarehouseTopologyService topologyService = new WarehouseTopologyService(
        topologyRepository,
        accessControl,
        businessScopeResolver,
        mock(AuditRepository.class)
    );
    warehouseService = new WarehouseService(
        warehouseRepository,
        accessControl,
        businessScopeResolver,
        topologyService,
        topologyRepository
    );

    centralWarehouseId = facilityId("JZ-CENTRAL");
    regionalWarehouseId = facilityId("SD-REGIONAL");
    itemId = jdbc.queryForObject(
        "select id from warehouse_item where tenant_id = 1 and code = 'CUP-700'",
        Long.class
    );
    regionalManager = new AuthUser(
        8201L,
        TENANT_ID,
        "default",
        "summary-regional-manager",
        "",
        "山东仓管",
        "WAREHOUSE",
        null,
        true
    );
    when(accessControl.hasPermission(
        regionalManager, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(true);
    when(accessControl.dataScope(
        regionalManager, DataScopeDomains.WAREHOUSE)).thenReturn(new DataScope(
            DataScopeModes.WAREHOUSE_LIST,
            List.of(),
            List.of(Long.toString(regionalWarehouseId))
        ));

    insertStore("summary-central-store", "汇总总仓门店", "JINGZHOU", centralWarehouseId);
    insertStore("summary-regional-store", "汇总山东门店", "SHANDONG", regionalWarehouseId);
    insertRequisition("SUMMARY-CENTRAL", "summary-central-store", centralWarehouseId, "2026-06-08 09:00:00");
    insertRequisition("SUMMARY-REGIONAL", "summary-regional-store", regionalWarehouseId, "2026-06-09 09:00:00");
  }

  @Test
  void filtersSummaryRowsByTheRequestedVisibleWarehouse() throws Exception {
    WarehouseRequisitionSummaryExport export = warehouseService.exportRequisitionSummary(
        regionalManager,
        request(List.of(), regionalWarehouseId)
    );

    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(export.content()))) {
      var sheet = workbook.getSheet("叫货汇总");
      assertThat(sheet.getLastRowNum()).isEqualTo(1);
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
          .isEqualTo("summary-regional-store");
    }
  }

  @Test
  void rejectsAnExplicitStoreBoundToAnotherWarehouse() {
    assertThatThrownBy(() -> warehouseService.exportRequisitionSummary(
        regionalManager,
        request(List.of("summary-central-store"), regionalWarehouseId)
    )).isInstanceOfSatisfying(BusinessException.class, error -> {
      assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
      assertThat(error.getCode()).isEqualTo("REQUISITION_SUMMARY_STORE_FORBIDDEN");
      assertThat(error.getMessage())
          .isEqualTo("所选门店不属于当前仓库数据范围：summary-central-store");
    });
  }

  private WarehouseRequisitionSummaryExportRequest request(
      List<String> storeIds,
      Long warehouseId
  ) {
    return new WarehouseRequisitionSummaryExportRequest(
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 6, 30),
        storeIds,
        List.of(itemId),
        "MONTH",
        false,
        List.of("store", "product", "period"),
        warehouseId
    );
  }

  private void migrate(DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("43")
        .baselineOnMigrate(true)
        .load()
        .migrate();
  }

  private long facilityId(String code) {
    return jdbc.queryForObject(
        "select id from warehouse_facility where tenant_id = 1 and code = ?",
        Long.class,
        code
    );
  }

  private void insertStore(String id, String name, String region, long warehouseId) {
    jdbc.update("""
        insert into store_branch(
          id, tenant_id, code, name, area, status, region_code, supply_warehouse_id, created_at
        ) values (?, 1, ?, ?, ?, '营业中', ?, ?, current_timestamp)
        """,
        id,
        id,
        name,
        region,
        region,
        warehouseId
    );
  }

  private void insertRequisition(
      String id,
      String storeId,
      long warehouseId,
      String submittedAt
  ) {
    warehouseRepository.insertRequisition(
        TENANT_ID,
        id,
        storeId,
        warehouseId,
        new BigDecimal("5.00"),
        "汇总范围测试",
        regionalManager.id(),
        id + "-key"
    );
    warehouseRepository.insertRequisitionLine(
        TENANT_ID,
        id,
        itemId,
        BigDecimal.ONE,
        new BigDecimal("5.00"),
        null,
        null
    );
    jdbc.update(
        "update store_requisition set submitted_at = ? where tenant_id = 1 and id = ?",
        submittedAt,
        id
    );
  }
}
