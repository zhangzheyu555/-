package com.storeprofit.system.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.inspection.InspectionRecordRepository;
import com.storeprofit.system.inspection.InspectionRecordRequest;
import com.storeprofit.system.inspection.InspectionService;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.PasswordService;
import com.storeprofit.system.platform.authorization.AuthorizationRepository;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.BusinessScopeRepository;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeRepository;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.storage.StorageService;
import com.storeprofit.system.warehouse.WarehouseNetworkService;
import com.storeprofit.system.warehouse.WarehouseRepository;
import com.storeprofit.system.warehouse.WarehouseRequisitionLineRequest;
import com.storeprofit.system.warehouse.WarehouseRequisitionRequest;
import com.storeprofit.system.warehouse.WarehouseService;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository;
import com.storeprofit.system.warehouse.WarehouseTopologyService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Database-backed contract guards shared by H5, MP-WEIXIN and APP-PLUS.
 *
 * <p>The controller mapping tests remain intentionally small. These tests exercise the formal
 * authorization and business services against the migrated H2 schema so a mocked 403 or a mocked
 * idempotent response cannot hide a real data-scope or persistence regression.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MobileApiDatabaseContractTest {
  private static final long TENANT_ID = 1L;
  private static final String STORE_ONE = "MOBILE_DB_STORE_1";
  private static final String STORE_TWO = "MOBILE_DB_STORE_2";

  private JdbcTemplate jdbc;
  private AuthRepository authRepository;
  private AuthUser manager;
  private AuthUser inspector;
  private StorageService storageService;
  private InspectionService inspectionService;
  private WarehouseService warehouseService;
  private WarehouseNetworkService warehouseNetworkService;
  private long centralWarehouseId;
  private long regionalWarehouseId;
  private long cupItemId;

  @BeforeAll
  void setUpDatabaseAndRealServices() {
    DataSource dataSource = migratedDataSource();
    jdbc = new JdbcTemplate(dataSource);
    authRepository = new AuthRepository(jdbc);

    AuditRepository auditRepository = new AuditRepository(jdbc);
    AuthorizationService authorizationService = new AuthorizationService(
        new AuthorizationRepository(jdbc));
    DataScopeService dataScopeService = new DataScopeService(
        new DataScopeRepository(jdbc), authRepository, new ObjectMapper());
    AuthService authService = new AuthService(
        authRepository,
        new PasswordService(),
        auditRepository,
        authorizationService,
        dataScopeService,
        12L
    );
    AccessControlService accessControl = new AccessControlService(
        authService, authRepository, auditRepository, authorizationService, dataScopeService);
    BusinessScopeResolver businessScopeResolver = new BusinessScopeResolver(
        authRepository,
        dataScopeService,
        new BusinessScopeRepository(jdbc),
        auditRepository
    );

    seedStoresAndUsers();

    storageService = new StorageService(jdbc, accessControl);
    InspectionRecordRepository inspectionRepository = new InspectionRecordRepository(
        jdbc, new NamedParameterJdbcTemplate(dataSource));
    inspectionService = new InspectionService(
        inspectionRepository,
        accessControl,
        "http://127.0.0.1:8000/detect",
        "http://127.0.0.1:8000/export",
        Duration.ofSeconds(1)
    );

    WarehouseRepository warehouseRepository = new WarehouseRepository(jdbc);
    WarehouseTopologyRepository topologyRepository = new WarehouseTopologyRepository(jdbc);
    WarehouseTopologyService topologyService = new WarehouseTopologyService(
        topologyRepository, accessControl, businessScopeResolver, auditRepository);
    warehouseService = new WarehouseService(
        warehouseRepository,
        accessControl,
        businessScopeResolver,
        topologyService,
        topologyRepository
    );
    warehouseNetworkService = new WarehouseNetworkService(
        topologyRepository, topologyService, warehouseRepository, accessControl);
  }

  @Test
  void crossStoreInspectionIsRejectedByRealDataScopeAndAudited() {
    int auditCountBefore = permissionDeniedCount(inspector.id());

    BusinessException error = catchThrowableOfType(
        () -> inspectionService.records(inspector, null, null, null, STORE_TWO, null),
        BusinessException.class);

    assertForbidden(error);
    assertThat(permissionDeniedCount(inspector.id())).isEqualTo(auditCountBefore + 1);
  }

  @Test
  void crossWarehouseContextIsRejectedByRealTopologyScopeAndAudited() {
    assertThat(regionalWarehouseId).isNotEqualTo(centralWarehouseId);
    int auditCountBefore = permissionDeniedCount(manager.id());

    BusinessException error = catchThrowableOfType(
        () -> warehouseNetworkService.transferContext(manager, centralWarehouseId),
        BusinessException.class);

    assertForbidden(error);
    assertThat(permissionDeniedCount(manager.id())).isEqualTo(auditCountBefore + 1);
  }

  @Test
  void crossStoreAttachmentIsRejectedBeforeContentLeavesMysqlAndIsAudited() {
    jdbc.update("""
        insert into warehouse_attachment(
          tenant_id, store_id, business_type, business_id, file_name, content_type,
          file_size, storage_path, content, uploaded_by, uploaded_at
        ) values (?, ?, 'INSPECTION_RECORD', ?, ?, 'image/jpeg', 4, ?, ?, ?, current_timestamp)
        """,
        TENANT_ID,
        STORE_TWO,
        "inspection-" + STORE_TWO + "-draft",
        "mobile-cross-store-evidence.jpg",
        "mysql://warehouse_attachment/mobile-cross-store-evidence.jpg",
        new byte[]{1, 2, 3, 4},
        inspector.id());
    Long attachmentId = jdbc.queryForObject("""
        select id from warehouse_attachment
        where tenant_id = ? and file_name = 'mobile-cross-store-evidence.jpg'
        """, Long.class, TENANT_ID);
    int auditCountBefore = permissionDeniedCount(manager.id());

    BusinessException error = catchThrowableOfType(
        () -> storageService.attachment(manager, attachmentId),
        BusinessException.class);

    assertForbidden(error);
    assertThat(permissionDeniedCount(manager.id())).isEqualTo(auditCountBefore + 1);
  }

  @Test
  void repeatedInspectionPutWithStableIdUpdatesOneDatabaseRecord() {
    String recordId = "MOBILE-DB-INSPECTION-STABLE-ID";
    InspectionRecordRequest request = new InspectionRecordRequest(
        STORE_ONE,
        "2026-07-15",
        "移动督导",
        "移动契约测试品牌",
        new BigDecimal("200.00"),
        new BigDecimal("184.00"),
        false,
        "[{\"item\":\"mobile\",\"deduct\":8}]",
        "[]",
        "[]",
        "弱网重复提交"
    );

    var first = inspectionService.save(inspector, recordId, request);
    var repeated = inspectionService.save(inspector, recordId, request);

    assertThat(first.id()).isEqualTo(recordId);
    assertThat(repeated.id()).isEqualTo(recordId);
    assertThat(jdbc.queryForObject(
        "select count(*) from inspection_record where tenant_id = ? and id = ?",
        Integer.class, TENANT_ID, recordId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from operation_log where tenant_id = ? and action = 'inspection_save' and target_id = ?",
        Integer.class, TENANT_ID, recordId)).isEqualTo(2);
  }

  @Test
  void repeatedRequisitionWithSameClientRequestIdCreatesOneOrderAndOneLine() {
    String clientRequestId = "mobile-db-requisition-idempotency-1";
    WarehouseRequisitionRequest request = new WarehouseRequisitionRequest(
        STORE_ONE,
        List.of(new WarehouseRequisitionLineRequest(
            cupItemId, new BigDecimal("2.00"), "移动端补货")),
        "弱网重复提交",
        clientRequestId
    );

    var first = warehouseService.createRequisition(manager, request);
    var repeated = warehouseService.createRequisition(manager, request);

    assertThat(repeated.id()).isEqualTo(first.id());
    assertThat(jdbc.queryForObject("""
        select count(*) from store_requisition
        where tenant_id = ? and idempotency_key = ?
        """, Integer.class, TENANT_ID, clientRequestId)).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from store_requisition_line
        where tenant_id = ? and requisition_id = ?
        """, Integer.class, TENANT_ID, first.id())).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_request_dedup
        where tenant_id = ? and request_type = 'STORE_REQUISITION' and request_key = ?
        """, Integer.class, TENANT_ID, clientRequestId)).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from operation_log
        where tenant_id = ? and action = '提交叫货' and target_id = ?
        """, Integer.class, TENANT_ID, first.id())).isOne();
  }

  private void seedStoresAndUsers() {
    centralWarehouseId = requiredLong("""
        select id from warehouse_facility where tenant_id = 1 and code = 'JZ-CENTRAL'
        """);
    regionalWarehouseId = requiredLong("""
        select id from warehouse_facility where tenant_id = 1 and code = 'SD-REGIONAL'
        """);
    jdbc.update("""
        insert into warehouse_item(
          tenant_id, code, name, category, unit, purchase_unit, stock_unit, ingredient_unit,
          spec, unit_price, shelf_life_days, cups_per_unit, daily_usage_estimate,
          min_stock_days, max_stock_days, min_stock_quantity, alert_enabled,
          expiry_alert_days, active, sort_order, created_at
        ) values (
          1, 'MOBILE-DB-ITEM', '移动契约测试物料', '测试', '件', '件', '件', '件',
          '测试规格', 10, 365, 1, 0, 1, 30, 0, 1, 3, 1, 999, current_timestamp
        )
        """);
    cupItemId = requiredLong("""
        select id from warehouse_item where tenant_id = 1 and code = 'MOBILE-DB-ITEM'
        """);

    jdbc.update("""
        insert into brand(tenant_id, code, name, color, sort_order, created_at)
        values (1, 'MOBILE-DB-BRAND', '移动契约测试品牌', '#000000', 999, current_timestamp)
        """);
    Long brandId = jdbc.queryForObject(
        "select id from brand where tenant_id = 1 and code = 'MOBILE-DB-BRAND'", Long.class);
    jdbc.update("""
        insert into store_branch(
          id, tenant_id, brand_id, code, name, area, status,
          region_code, supply_warehouse_id, created_at
        ) values (?, 1, ?, 'MOBILE-DB-S1', '移动契约一店', '山东', '营业中',
                  'SHANDONG', ?, current_timestamp)
        """, STORE_ONE, brandId, regionalWarehouseId);
    jdbc.update("""
        insert into store_branch(
          id, tenant_id, brand_id, code, name, area, status,
          region_code, supply_warehouse_id, created_at
        ) values (?, 1, ?, 'MOBILE-DB-S2', '移动契约二店', '山东', '营业中',
                  'SHANDONG', ?, current_timestamp)
        """, STORE_TWO, brandId, regionalWarehouseId);

    manager = createScopedUser(
        "mobile-db-manager", "移动契约店长", "STORE_MANAGER", STORE_ONE);
    inspector = createScopedUser(
        "mobile-db-inspector", "移动契约督导", "OPERATIONS", STORE_ONE);
  }

  private AuthUser createScopedUser(
      String username,
      String displayName,
      String role,
      String storeId
  ) {
    authRepository.createUser(
        TENANT_ID, username, "TEST_ONLY_PASSWORD_HASH", displayName, role, storeId);
    AuthUser user = authRepository.findByUsername(TENANT_ID, username).orElseThrow();
    authRepository.replaceStoreScope(TENANT_ID, user.id(), List.of(storeId));
    return user;
  }

  private long requiredLong(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    if (value == null) {
      throw new IllegalStateException("Required mobile contract fixture is missing");
    }
    return value;
  }

  private int permissionDeniedCount(long userId) {
    Integer count = jdbc.queryForObject("""
        select count(*) from operation_log
        where tenant_id = ? and operator_id = ? and action = 'permission_denied'
        """, Integer.class, TENANT_ID, userId);
    return count == null ? 0 : count;
  }

  private void assertForbidden(BusinessException error) {
    assertThat(error).isNotNull();
    assertThat(error.getCode()).isEqualTo("FORBIDDEN");
    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private DataSource migratedDataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("""
        jdbc:h2:mem:mobile-api-database-contract;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        LOCK_TIMEOUT=10000;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """.replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .baselineOnMigrate(true)
        .load()
        .migrate();
    return dataSource;
  }
}
