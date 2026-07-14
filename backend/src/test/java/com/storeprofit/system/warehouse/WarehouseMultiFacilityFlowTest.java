package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V43 contract tests that exercise the actual warehouse tables and repositories.
 * Authentication is mocked so each test can isolate operation permission, warehouse scope and
 * topology without weakening the inventory assertions.
 */
class WarehouseMultiFacilityFlowTest {
  private static final long TENANT_ID = 1L;

  private DataSource dataSource;
  private JdbcTemplate jdbc;
  private WarehouseTopologyRepository topologyRepository;
  private WarehouseRepository warehouseRepository;
  private AccessControlService accessControl;
  private BusinessScopeResolver businessScopeResolver;
  private WarehouseTopologyService topologyService;
  private WarehouseNetworkService networkService;
  private WarehouseService warehouseService;
  private long centralWarehouseId;
  private long regionalWarehouseId;
  private long itemId;

  private AuthUser storeManager;
  private AuthUser centralManager;
  private AuthUser regionalManager;

  @BeforeEach
  void setUp() {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL(("""
        jdbc:h2:mem:warehouse-multi-facility-%s;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        LOCK_TIMEOUT=10000;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """).formatted(UUID.randomUUID()).replaceAll("\\s+", ""));
    h2.setUser("sa");
    h2.setPassword("");
    dataSource = h2;
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target("43")
        .baselineOnMigrate(true)
        .load()
        .migrate();

    jdbc = new JdbcTemplate(dataSource);
    topologyRepository = new WarehouseTopologyRepository(jdbc);
    warehouseRepository = new WarehouseRepository(jdbc);
    accessControl = mock(AccessControlService.class);
    businessScopeResolver = mock(BusinessScopeResolver.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    topologyService = new WarehouseTopologyService(
        topologyRepository, accessControl, businessScopeResolver, auditRepository);
    networkService = new WarehouseNetworkService(
        topologyRepository, topologyService, warehouseRepository, accessControl);
    warehouseService = new WarehouseService(
        warehouseRepository, accessControl, businessScopeResolver,
        topologyService, topologyRepository);

    centralWarehouseId = facilityId("JZ-CENTRAL");
    regionalWarehouseId = facilityId("SD-REGIONAL");
    itemId = jdbc.queryForObject(
        "select id from warehouse_item where tenant_id = 1 and code = 'CUP-700'",
        Long.class);

    jdbc.update("""
        insert into store_branch(
          id, tenant_id, code, name, area, status, region_code, supply_warehouse_id, created_at
        ) values ('sd-store-1', 1, 'SD001', '山东测试门店', 'SHANDONG', '营业中',
          'SHANDONG', ?, current_timestamp)
        """, regionalWarehouseId);

    storeManager = user(7101L, "sd-store-manager", "山东店长", "STORE_MANAGER", "sd-store-1");
    centralManager = user(7102L, "jz-warehouse", "荆州仓管理员", "WAREHOUSE", null);
    regionalManager = user(7103L, "sd-warehouse", "山东仓管理员", "WAREHOUSE", null);

    DataScope storeScope = new DataScope(DataScopeModes.OWN_STORE, List.of("sd-store-1"));
    BusinessScope resolvedStore = new BusinessScope(
        "sd-store-1", "山东测试门店", null, null, storeScope);
    when(businessScopeResolver.resolve(
        eq(storeManager), eq(DataScopeDomains.WAREHOUSE), any(), isNull(), anyString()))
        .thenReturn(resolvedStore);
    when(accessControl.dataScope(storeManager, DataScopeDomains.WAREHOUSE)).thenReturn(storeScope);
    when(accessControl.dataScope(centralManager, DataScopeDomains.WAREHOUSE))
        .thenReturn(warehouseScope(centralWarehouseId));
    when(accessControl.dataScope(regionalManager, DataScopeDomains.WAREHOUSE))
        .thenReturn(warehouseScope(regionalWarehouseId));
  }

  @Test
  void shandongStoreRequisitionIsServerRoutedAndIgnoresTamperedWarehouseId() throws Exception {
    List<String> requestFields = Arrays.stream(WarehouseRequisitionRequest.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
    assertThat(requestFields).doesNotContain("warehouseId", "supplyWarehouseId");

    ObjectMapper webObjectMapper = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    WarehouseRequisitionRequest tamperedRequest = webObjectMapper.readValue(("""
        {
          "storeId": "sd-store-1",
          "warehouseId": %d,
          "supplyWarehouseId": %d,
          "lines": [{"itemId": %d, "requestedQuantity": 1, "note": "山东门店补货"}],
          "note": "服务端按绑定仓路由"
        }
        """).formatted(centralWarehouseId, centralWarehouseId, itemId),
        WarehouseRequisitionRequest.class);

    WarehouseRequisitionResponse created = warehouseService.createRequisition(
        storeManager, tamperedRequest);

    assertThat(created.storeId()).isEqualTo("sd-store-1");
    assertThat(jdbc.queryForObject(
        "select supply_warehouse_id from store_requisition where tenant_id = 1 and id = ?",
        Long.class,
        created.id())).isEqualTo(regionalWarehouseId);
    assertThat(jdbc.queryForObject(
        "select count(*) from store_requisition where tenant_id = 1 and id = ? and supply_warehouse_id = ?",
        Integer.class,
        created.id(),
        centralWarehouseId)).isZero();
    verify(businessScopeResolver).resolve(
        storeManager, DataScopeDomains.WAREHOUSE, "sd-store-1", null, "为门店提交叫货单");
  }

  @Test
  void regionalWarehouseExternalPurchaseIsForbiddenEvenWithPermissionAndScope() {
    int before = jdbc.queryForObject(
        "select count(*) from warehouse_purchase_order where tenant_id = 1",
        Integer.class);
    WarehousePurchaseOrderRequest request = new WarehousePurchaseOrderRequest(
        null,
        "山东分仓不得外采",
        List.of(new WarehousePurchaseOrderLineRequest(
            itemId, BigDecimal.ONE, new BigDecimal("138.00"), "越权采购")),
        regionalWarehouseId,
        "sd-purchase-forbidden-1");

    assertThatThrownBy(() -> warehouseService.createPurchaseOrder(regionalManager, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(accessControl).requireWarehousePurchase(regionalManager);
    assertThat(topologyService.canAccessFacility(regionalManager, regionalWarehouseId)).isTrue();
    assertThat(jdbc.queryForObject(
        "select external_purchase_allowed from warehouse_facility where id = ?",
        Integer.class,
        regionalWarehouseId)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_purchase_order where tenant_id = 1",
        Integer.class)).isEqualTo(before);
  }

  @Test
  void centralPurchaseRequiresApprovalAndReceivingIsIdempotent() {
    Long supplierId = jdbc.queryForObject(
        "select min(id) from warehouse_supplier where tenant_id = 1 and active = 1",
        Long.class);
    BigDecimal initialOnHand = inventoryQuantity(
        centralWarehouseId, itemId, "on_hand_quantity");
    int initialBatchCount = jdbc.queryForObject(
        "select count(*) from warehouse_stock_batch where tenant_id = 1 and warehouse_id = ?",
        Integer.class, centralWarehouseId);
    int initialMovementCount = jdbc.queryForObject(
        "select count(*) from warehouse_stock_movement where tenant_id = 1 and warehouse_id = ?",
        Integer.class, centralWarehouseId);

    WarehousePurchaseOrderRequest createRequest = new WarehousePurchaseOrderRequest(
        supplierId,
        "荆州总仓外部采购",
        List.of(new WarehousePurchaseOrderLineRequest(
            itemId, new BigDecimal("2.00"), new BigDecimal("138.00"), "测试采购")),
        centralWarehouseId,
        "purchase-create-flow-1");
    WarehousePurchaseOrderResponse draft = warehouseService.createPurchaseOrder(
        centralManager, createRequest);
    WarehousePurchaseOrderResponse duplicateDraft = warehouseService.createPurchaseOrder(
        centralManager, createRequest);

    assertThat(draft.status()).isEqualTo("DRAFT");
    assertThat(duplicateDraft.id()).isEqualTo(draft.id());
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_purchase_order where tenant_id = 1 and idempotency_key = ?",
        Integer.class, "purchase-create-flow-1")).isOne();

    WarehousePurchaseOrderResponse ordered = warehouseService.approvePurchaseOrder(
        centralManager, draft.id());
    assertThat(ordered.status()).isEqualTo("ORDERED");

    WarehousePurchaseReceiveRequest receiveRequest = new WarehousePurchaseReceiveRequest(
        "purchase-receive-flow-1",
        List.of(new WarehousePurchaseReceiveLineRequest(
            itemId, "PO-FLOW-BATCH-1", "2026-07-13", "2027-07-13",
            new BigDecimal("2.00"), "按审批单入库")),
        "采购到货");
    WarehousePurchaseOrderResponse received = warehouseService.receivePurchaseOrder(
        centralManager, draft.id(), receiveRequest);
    WarehousePurchaseOrderResponse duplicateReceive = warehouseService.receivePurchaseOrder(
        centralManager, draft.id(), receiveRequest);

    assertThat(received.status()).isEqualTo("RECEIVED");
    assertThat(duplicateReceive.status()).isEqualTo("RECEIVED");
    assertThat(received.lines()).singleElement().satisfies(line ->
        assertThat(line.receivedQuantity()).isEqualByComparingTo("2.00"));
    assertThat(inventoryQuantity(centralWarehouseId, itemId, "on_hand_quantity"))
        .isEqualByComparingTo(initialOnHand.add(new BigDecimal("2.00")));
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_stock_batch where tenant_id = 1 and warehouse_id = ?",
        Integer.class, centralWarehouseId)).isEqualTo(initialBatchCount + 1);
    assertThat(jdbc.queryForObject(
        "select count(*) from warehouse_stock_movement where tenant_id = 1 and warehouse_id = ?",
        Integer.class, centralWarehouseId)).isEqualTo(initialMovementCount + 1);
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_stock_movement
        where tenant_id = 1 and source_type = 'PURCHASE_ORDER' and source_id = ?
        """, Integer.class, draft.id())).isOne();

    WarehouseStockBatchRequest directReceive = new WarehouseStockBatchRequest(
        itemId, "DIRECT-BYPASS", "2026-07-13", null,
        BigDecimal.ONE, new BigDecimal("138.00"), "禁止绕过审批",
        "direct-bypass-1", centralWarehouseId);
    assertThatThrownBy(() -> warehouseService.receiveStock(centralManager, directReceive))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("DIRECT_STOCK_RECEIVE_DISABLED"));
  }

  @Test
  void transferPreservesQuantityAndCostAndAllActionsAreIdempotent() {
    BigDecimal initialInventoryValue = inventoryValue(itemId);
    BigDecimal initialBatchValue = batchValue(itemId);
    BigDecimal initialCentralQuantity = inventoryQuantity(centralWarehouseId, itemId, "on_hand_quantity");

    WarehouseTransferCreateRequest createRequest = new WarehouseTransferCreateRequest(
        centralWarehouseId,
        regionalWarehouseId,
        List.of(new WarehouseTransferLineRequest(itemId, new BigDecimal("10.00"), "山东补货")),
        "荆州发往山东",
        "transfer-create-1");
    WarehouseTransferResponse created = networkService.create(regionalManager, createRequest);
    WarehouseTransferResponse duplicateCreate = networkService.create(regionalManager, createRequest);
    assertThat(duplicateCreate.id()).isEqualTo(created.id());

    networkService.submit(regionalManager, created.id(),
        new WarehouseTransferActionRequest("transfer-submit-1", "提交审批"));
    networkService.submit(regionalManager, created.id(),
        new WarehouseTransferActionRequest("transfer-submit-1", "重复提交"));
    networkService.review(centralManager, created.id(),
        new WarehouseTransferReviewRequest(true, "同意调拨"));
    WarehouseTransferResponse approvedAgain = networkService.review(centralManager, created.id(),
        new WarehouseTransferReviewRequest(true, "重复审批"));

    assertThat(approvedAgain.status()).isEqualTo("APPROVED");
    assertThat(inventoryQuantity(centralWarehouseId, itemId, "on_hand_quantity"))
        .isEqualByComparingTo(initialCentralQuantity);
    assertThat(inventoryQuantity(centralWarehouseId, itemId, "reserved_quantity"))
        .isEqualByComparingTo("10.00");
    assertThat(batchQuantity(centralWarehouseId, itemId, "reserved_quantity"))
        .isEqualByComparingTo("10.00");
    assertThat(inventoryValue(itemId)).isEqualByComparingTo(initialInventoryValue);

    networkService.ship(centralManager, created.id(),
        new WarehouseTransferActionRequest("transfer-ship-1", "调拨发货"));
    WarehouseTransferResponse shippedAgain = networkService.ship(centralManager, created.id(),
        new WarehouseTransferActionRequest("transfer-ship-1", "重复发货"));

    assertThat(shippedAgain.status()).isEqualTo("SHIPPED");
    assertThat(inventoryQuantity(centralWarehouseId, itemId, "on_hand_quantity"))
        .isEqualByComparingTo(initialCentralQuantity.subtract(new BigDecimal("10.00")));
    assertThat(inventoryQuantity(centralWarehouseId, itemId, "reserved_quantity"))
        .isEqualByComparingTo("0.00");
    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "in_transit_quantity"))
        .isEqualByComparingTo("10.00");
    assertThat(batchQuantity(centralWarehouseId, itemId, "quantity"))
        .isEqualByComparingTo(initialCentralQuantity.subtract(new BigDecimal("10.00")));
    assertThat(inventoryValue(itemId)).isEqualByComparingTo(initialInventoryValue);

    WarehouseTransferReceiveRequest receiveRequest = new WarehouseTransferReceiveRequest(
        "transfer-receive-1",
        "山东确认到货",
        List.of(new WarehouseTransferReceiveLineRequest(itemId, new BigDecimal("10.00"))));
    WarehouseTransferResponse received = networkService.receive(regionalManager, created.id(), receiveRequest);
    WarehouseTransferResponse receivedAgain = networkService.receive(regionalManager, created.id(), receiveRequest);

    assertThat(received.status()).isEqualTo("RECEIVED");
    assertThat(receivedAgain.status()).isEqualTo("RECEIVED");
    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "on_hand_quantity"))
        .isEqualByComparingTo("10.00");
    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "in_transit_quantity"))
        .isEqualByComparingTo("0.00");
    assertThat(batchQuantity(regionalWarehouseId, itemId, "quantity"))
        .isEqualByComparingTo("10.00");
    assertThat(inventoryValue(itemId)).isEqualByComparingTo(initialInventoryValue);
    assertThat(batchValue(itemId)).isEqualByComparingTo(initialBatchValue);
    assertThat(received.totalAmount()).isEqualByComparingTo("1380.00");
    assertThat(received.lines()).singleElement().satisfies(line -> {
      assertThat(line.unitCost()).isEqualByComparingTo("138.0000");
      assertThat(line.receivedQuantity()).isEqualByComparingTo("10.00");
      assertThat(line.inTransitQuantity()).isEqualByComparingTo("0.00");
    });

    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_transfer_action
        where tenant_id = 1 and transfer_order_id = ? and action_type = 'SHIP'
        """, Integer.class, created.id())).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_transfer_action
        where tenant_id = 1 and transfer_order_id = ? and action_type = 'RECEIVE'
        """, Integer.class, created.id())).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_stock_movement
        where tenant_id = 1 and source_type = 'WAREHOUSE_TRANSFER' and source_id = ?
        """, Integer.class, created.id())).isEqualTo(4);
    assertThat(jdbc.queryForObject("""
        select coalesce(sum(quantity_delta), 0) from warehouse_stock_movement
        where tenant_id = 1 and source_type = 'WAREHOUSE_TRANSFER' and source_id = ?
        """, BigDecimal.class, created.id())).isEqualByComparingTo("0.00");
    assertThat(jdbc.queryForObject("""
        select coalesce(sum(reserved_quantity_delta), 0) from warehouse_stock_movement
        where tenant_id = 1 and source_type = 'WAREHOUSE_TRANSFER' and source_id = ?
        """, BigDecimal.class, created.id())).isEqualByComparingTo("0.00");
    assertThat(jdbc.queryForObject("""
        select coalesce(sum(in_transit_quantity_delta), 0) from warehouse_stock_movement
        where tenant_id = 1 and source_type = 'WAREHOUSE_TRANSFER' and source_id = ?
        """, BigDecimal.class, created.id())).isEqualByComparingTo("0.00");
  }

  @Test
  void concurrentApprovalUsesRowLocksAndCannotOverReserveOrGoNegative() throws Exception {
    WarehouseTransferResponse first = createAndSubmitTransfer("concurrent-transfer-1", "80.00");
    WarehouseTransferResponse second = createAndSubmitTransfer("concurrent-transfer-2", "80.00");

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> firstResult = executor.submit(() -> approveInTransaction(
          transactionManager, start, first.id()));
      Future<String> secondResult = executor.submit(() -> approveInTransaction(
          transactionManager, start, second.id()));
      start.countDown();

      assertThat(List.of(firstResult.get(), secondResult.get()))
          .containsExactlyInAnyOrder("APPROVED", "WAREHOUSE_STOCK_INSUFFICIENT");
    } finally {
      executor.shutdownNow();
    }

    assertThat(inventoryQuantity(centralWarehouseId, itemId, "on_hand_quantity"))
        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    assertThat(inventoryQuantity(centralWarehouseId, itemId, "reserved_quantity"))
        .isEqualByComparingTo("80.00");
    assertThat(batchQuantity(centralWarehouseId, itemId, "reserved_quantity"))
        .isEqualByComparingTo("80.00");
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_transfer_order
        where tenant_id = 1 and id in (?, ?) and status = 'APPROVED'
        """, Integer.class, first.id(), second.id())).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_transfer_order
        where tenant_id = 1 and id in (?, ?) and status = 'SUBMITTED'
        """, Integer.class, first.id(), second.id())).isOne();
  }

  @Test
  void warehouseListScopeKeepsStoreAndWarehouseIdentifiersIndependent() {
    DataScope scope = new DataScope(
        DataScopeModes.WAREHOUSE_LIST,
        List.of("store-id-must-not-leak"),
        List.of(Long.toString(regionalWarehouseId)));
    when(accessControl.dataScope(regionalManager, DataScopeDomains.WAREHOUSE)).thenReturn(scope);

    assertThat(scope.storeIds()).isEmpty();
    assertThat(scope.warehouseIds()).containsExactly(Long.toString(regionalWarehouseId));
    assertThat(scope.allowsStore("store-id-must-not-leak")).isFalse();
    assertThat(scope.allowsWarehouse(Long.toString(regionalWarehouseId))).isTrue();
    assertThat(topologyService.visibleFacilities(regionalManager))
        .extracting(WarehouseFacilityResponse::code)
        .containsExactly("SD-REGIONAL");
    assertThatThrownBy(() -> topologyService.requireVisibleFacility(
        regionalManager, centralWarehouseId, "越权查看荆州总仓"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  private WarehouseTransferResponse createAndSubmitTransfer(String key, String quantity) {
    WarehouseTransferResponse created = networkService.create(
        regionalManager,
        new WarehouseTransferCreateRequest(
            centralWarehouseId,
            regionalWarehouseId,
            List.of(new WarehouseTransferLineRequest(itemId, new BigDecimal(quantity), key)),
            key,
            key));
    return networkService.submit(
        regionalManager,
        created.id(),
        new WarehouseTransferActionRequest(key + "-submit", "提交"));
  }

  private String approveInTransaction(
      DataSourceTransactionManager transactionManager,
      CountDownLatch start,
      String transferId
  ) throws InterruptedException {
    start.await();
    try {
      new TransactionTemplate(transactionManager).executeWithoutResult(status ->
          networkService.review(
              centralManager,
              transferId,
              new WarehouseTransferReviewRequest(true, "并发审批")));
      return "APPROVED";
    } catch (BusinessException ex) {
      return ex.getCode();
    }
  }

  private long facilityId(String code) {
    return jdbc.queryForObject(
        "select id from warehouse_facility where tenant_id = 1 and code = ?",
        Long.class,
        code);
  }

  private BigDecimal inventoryQuantity(long warehouseId, long requestedItemId, String column) {
    if (!List.of("on_hand_quantity", "reserved_quantity", "in_transit_quantity").contains(column)) {
      throw new IllegalArgumentException("Unsupported inventory column: " + column);
    }
    return jdbc.queryForObject(("""
        select %s from warehouse_inventory
        where tenant_id = 1 and warehouse_id = ? and item_id = ?
        """).formatted(column), BigDecimal.class, warehouseId, requestedItemId);
  }

  private BigDecimal batchQuantity(long warehouseId, long requestedItemId, String column) {
    if (!List.of("quantity", "reserved_quantity").contains(column)) {
      throw new IllegalArgumentException("Unsupported batch column: " + column);
    }
    return jdbc.queryForObject(("""
        select coalesce(sum(%s), 0) from warehouse_stock_batch
        where tenant_id = 1 and warehouse_id = ? and item_id = ?
        """).formatted(column), BigDecimal.class, warehouseId, requestedItemId);
  }

  private BigDecimal inventoryValue(long requestedItemId) {
    return jdbc.queryForObject("""
        select coalesce(sum((on_hand_quantity + in_transit_quantity) * unit_cost), 0)
        from warehouse_inventory where tenant_id = 1 and item_id = ?
        """, BigDecimal.class, requestedItemId);
  }

  private BigDecimal batchValue(long requestedItemId) {
    return jdbc.queryForObject("""
        select coalesce(sum(quantity * unit_cost), 0)
        from warehouse_stock_batch where tenant_id = 1 and item_id = ?
        """, BigDecimal.class, requestedItemId);
  }

  private DataScope warehouseScope(long warehouseId) {
    return new DataScope(
        DataScopeModes.WAREHOUSE_LIST,
        List.of(),
        List.of(Long.toString(warehouseId)));
  }

  private AuthUser user(long id, String username, String displayName, String role, String storeId) {
    return new AuthUser(id, TENANT_ID, "default", username, "", displayName, role, storeId, true);
  }
}
