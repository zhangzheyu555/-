package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
  private AuditRepository auditRepository;
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
    auditRepository = mock(AuditRepository.class);
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
  void regionalWarehousePartialFulfillmentReservesAndShipsOnlyAvailableStock() {
    jdbc.update("""
        insert into warehouse_stock_batch(
          tenant_id, warehouse_id, item_id, batch_no, received_date, expiry_date,
          quantity, reserved_quantity, unit_cost, note, version, created_at
        ) values (1, ?, ?, 'SD-PARTIAL-1', '2026-07-20', '2026-08-20',
          3.00, 0.00, 138.00, '分仓部分库存', 0, current_timestamp)
        """, regionalWarehouseId, itemId);
    jdbc.update("""
        update warehouse_inventory
        set on_hand_quantity = 3.00, reserved_quantity = 0.00, unit_cost = 138.00
        where tenant_id = 1 and warehouse_id = ? and item_id = ?
        """, regionalWarehouseId, itemId);

    WarehouseRequisitionResponse created = warehouseService.createRequisition(
        storeManager,
        new WarehouseRequisitionRequest(
            "sd-store-1",
            List.of(new WarehouseRequisitionLineRequest(
                itemId, new BigDecimal("5"), "分仓库存不足时部分发货")),
            "申请5件"
        )
    );
    warehouseService.review(
        regionalManager,
        created.id(),
        new WarehouseRequisitionReviewRequest(
            true,
            List.of(),
            "按可用库存发货",
            WarehouseRequisitionHandlingMode.AVAILABLE_ONLY
        )
    );

    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "on_hand_quantity"))
        .isEqualByComparingTo("3.00");
    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "reserved_quantity"))
        .isEqualByComparingTo("3.00");
    assertThat(batchQuantity(regionalWarehouseId, itemId, "reserved_quantity"))
        .isEqualByComparingTo("3.00");

    warehouseService.ship(regionalManager, created.id());

    WarehouseRequisitionResponse partial = warehouseRepository.requisition(TENANT_ID, created.id())
        .orElseThrow();
    assertThat(partial.status()).isEqualTo("PARTIALLY_SHIPPED");
    assertThat(partial.lines().getFirst().shippedQuantity()).isEqualByComparingTo("3.00");
    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "on_hand_quantity"))
        .isZero();
    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "reserved_quantity"))
        .isZero();
    assertThat(batchQuantity(regionalWarehouseId, itemId, "quantity")).isZero();
    assertThat(batchQuantity(regionalWarehouseId, itemId, "reserved_quantity")).isZero();
  }

  @Test
  void regionalWarehouseReviewCanCompleteRequisitionAndMoveStockToStoreImmediately() {
    jdbc.update("""
        insert into warehouse_stock_batch(
          tenant_id, warehouse_id, item_id, batch_no, received_date, expiry_date,
          quantity, reserved_quantity, unit_cost, note, version, created_at
        ) values (1, ?, ?, 'SD-REVIEW-COMPLETE-1', '2026-07-20', '2026-08-20',
          6.00, 0.00, 138.00, '审核即完成库存', 0, current_timestamp)
        """, regionalWarehouseId, itemId);
    jdbc.update("""
        update warehouse_inventory
        set on_hand_quantity = 6.00, reserved_quantity = 0.00, unit_cost = 138.00
        where tenant_id = 1 and warehouse_id = ? and item_id = ?
        """, regionalWarehouseId, itemId);

    WarehouseRequisitionResponse created = warehouseService.createRequisition(
        storeManager,
        new WarehouseRequisitionRequest(
            "sd-store-1",
            List.of(new WarehouseRequisitionLineRequest(
                itemId, new BigDecimal("2"), "审核后直接完成")),
            "分仓审核改价"
        )
    );
    warehouseService.review(
        regionalManager,
        created.id(),
        new WarehouseRequisitionReviewRequest(
            true,
            List.of(new WarehouseRequisitionReviewLineRequest(
                itemId, new BigDecimal("2"), new BigDecimal("45.50"))),
            "审核即完成",
            WarehouseRequisitionHandlingMode.FULL,
            true
        )
    );

    WarehouseRequisitionResponse completed = warehouseRepository.requisition(
        TENANT_ID, created.id()).orElseThrow();
    assertThat(completed.status()).isEqualTo("RECEIVED");
    assertThat(completed.totalAmount()).isEqualByComparingTo("91.00");
    assertThat(completed.lines().getFirst().unitPrice()).isEqualByComparingTo("45.50");
    assertThat(completed.lines().getFirst().shippedQuantity()).isEqualByComparingTo("2.00");
    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "on_hand_quantity"))
        .isEqualByComparingTo("4.00");
    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "reserved_quantity"))
        .isZero();
    assertThat(batchQuantity(regionalWarehouseId, itemId, "quantity"))
        .isEqualByComparingTo("4.00");
    assertThat(batchQuantity(regionalWarehouseId, itemId, "reserved_quantity"))
        .isZero();
    assertThat(jdbc.queryForObject("""
        select quantity
        from store_inventory
        where tenant_id = 1 and store_id = 'sd-store-1' and item_id = ?
        """, BigDecimal.class, itemId)).isEqualByComparingTo("2.00");
    assertThat(jdbc.queryForObject("""
        select count(*)
        from warehouse_stock_movement
        where tenant_id = 1 and warehouse_id = ? and item_id = ?
          and source_type = 'REQUISITION' and source_id = ? and movement_type = 'OUT'
        """, Integer.class, regionalWarehouseId, itemId, created.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from warehouse_delivery_order
        where tenant_id = 1 and requisition_id = ? and status = 'RECEIVED'
        """, Integer.class, created.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*)
        from store_receipt
        where tenant_id = 1 and requisition_id = ? and status = 'RECEIVED'
        """, Integer.class, created.id())).isEqualTo(1);
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
    jdbc.execute("alter table warehouse_purchase_order alter column version drop default");
    jdbc.execute("alter table warehouse_purchase_order_line alter column received_quantity drop default");
    jdbc.execute("alter table warehouse_stock_batch alter column version drop default");
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
    assertThat(jdbc.queryForObject(
        "select version from warehouse_purchase_order where tenant_id = 1 and id = ?",
        Long.class, draft.id())).isZero();
    assertThat(jdbc.queryForObject(
        "select received_quantity from warehouse_purchase_order_line where tenant_id = 1 and purchase_order_id = ? and item_id = ?",
        BigDecimal.class, draft.id(), itemId)).isEqualByComparingTo("0.00");

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
    for (String column : List.of(
        "approved_quantity", "reserved_quantity", "shipped_quantity", "received_quantity",
        "in_transit_quantity", "unit_cost", "amount", "version"
    )) {
      jdbc.execute("alter table warehouse_transfer_line alter column " + column + " drop default");
    }
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
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_transfer_line
        where tenant_id = 1 and transfer_order_id = ?
          and approved_quantity = 0 and reserved_quantity = 0 and shipped_quantity = 0
          and received_quantity = 0 and in_transit_quantity = 0 and unit_cost = 0
          and amount = 0 and version = 0
        """, Integer.class, created.id())).isOne();

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
  void routeDisableDoesNotBreakAuthorizedIdempotentReplayButBlocksNewActions() {
    allowTransferActions(regionalManager);
    WarehouseTransferCreateRequest createRequest = new WarehouseTransferCreateRequest(
        centralWarehouseId,
        regionalWarehouseId,
        List.of(new WarehouseTransferLineRequest(itemId, BigDecimal.ONE, "幂等路线停用测试")),
        "幂等重试",
        "route-disable-create");
    WarehouseTransferResponse draft = networkService.create(regionalManager, createRequest);
    WarehouseTransferResponse submitted = networkService.submit(
        regionalManager, draft.id(), new WarehouseTransferActionRequest("route-disable-submit", "首次提交"));
    jdbc.update("""
        update warehouse_transfer_route set enabled = 0
        where tenant_id = 1 and source_warehouse_id = ? and target_warehouse_id = ?
        """, centralWarehouseId, regionalWarehouseId);

    WarehouseTransferResponse duplicateCreate = networkService.create(regionalManager, createRequest);
    WarehouseTransferResponse duplicateSubmit = networkService.submit(
        regionalManager, draft.id(), new WarehouseTransferActionRequest("route-disable-submit", "安全重试"));
    assertThat(duplicateCreate.id()).isEqualTo(draft.id());
    assertThat(duplicateCreate.status()).isEqualTo("SUBMITTED");
    assertThat(duplicateSubmit.status()).isEqualTo("SUBMITTED");
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_transfer_action
        where tenant_id = 1 and transfer_order_id = ? and action_type = 'SUBMIT'
        """, Integer.class, draft.id())).isOne();

    assertThatThrownBy(() -> networkService.submit(
        regionalManager, draft.id(), new WarehouseTransferActionRequest("route-disable-new", "新动作")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("WAREHOUSE_ROUTE_FORBIDDEN"));

    AuthUser outOfScopeUser = user(7104L, "other-warehouse", "越权仓管", "WAREHOUSE", null);
    when(accessControl.dataScope(outOfScopeUser, DataScopeDomains.WAREHOUSE))
        .thenReturn(warehouseScope(centralWarehouseId));
    assertThatThrownBy(() -> networkService.submit(
        outOfScopeUser, draft.id(), new WarehouseTransferActionRequest("route-disable-submit", "越权重试")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("FORBIDDEN"));

    assertThat(submitted.status()).isEqualTo("SUBMITTED");
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
  void receiveReturnClaimsApprovedStatusBeforeMutatingInventoryUnderConcurrency() throws Exception {
    String returnId = "RETURN-CONCURRENT-1";
    String batchNo = "SD-RETURN-CONCURRENT-1";
    jdbc.execute("""
        alter table warehouse_return_order
        add column if not exists receive_warehouse_code_snapshot varchar(64)
        """);
    jdbc.execute("""
        alter table warehouse_return_order
        add column if not exists receive_warehouse_name_snapshot varchar(160)
        """);
    jdbc.update("""
        insert into warehouse_stock_batch(
          tenant_id, warehouse_id, item_id, batch_no, received_date, expiry_date,
          quantity, reserved_quantity, unit_cost, note, version, created_at
        ) values (1, ?, ?, ?, '2026-07-20', '2027-07-20',
          10.00, 0.00, 138.00, '并发退货测试批次', 0, current_timestamp)
        """, regionalWarehouseId, itemId, batchNo);
    long batchId = jdbc.queryForObject("""
        select id from warehouse_stock_batch
        where tenant_id = 1 and warehouse_id = ? and item_id = ? and batch_no = ?
        """, Long.class, regionalWarehouseId, itemId, batchNo);
    jdbc.update("""
        update warehouse_inventory
        set on_hand_quantity = 10.00, reserved_quantity = 0.00,
            in_transit_quantity = 0.00, unit_cost = 138.00, version = 0
        where tenant_id = 1 and warehouse_id = ? and item_id = ?
        """, regionalWarehouseId, itemId);
    jdbc.update("""
        insert into store_inventory(
          tenant_id, store_id, item_id, quantity, unit, updated_at
        ) values (1, 'sd-store-1', ?, 10.00, '件', current_timestamp)
        on duplicate key update quantity = values(quantity), updated_at = current_timestamp
        """, itemId);

    WarehouseTopologyRepository.FacilityRow regionalWarehouse = topologyRepository
        .facility(TENANT_ID, regionalWarehouseId)
        .orElseThrow();
    warehouseRepository.insertReturnOrder(
        TENANT_ID,
        new WarehouseRepository.WarehouseFacilitySnapshot(
            regionalWarehouse.id(), regionalWarehouse.code(), regionalWarehouse.name()),
        returnId,
        returnId,
        null,
        null,
        "sd-store-1",
        "山东测试门店",
        "APPROVED",
        new BigDecimal("276.00"),
        "山东仓管理员",
        "山东店长",
        "包装破损",
        "并发确认退货",
        "2026-07-28"
    );
    warehouseRepository.insertReturnOrderLine(
        TENANT_ID,
        returnId,
        null,
        itemId,
        "700ml杯子",
        "1000个/件",
        batchId,
        batchNo,
        new BigDecimal("2.00"),
        "件",
        new BigDecimal("138.00"),
        new BigDecimal("138.00"),
        "包装破损",
        "并发确认退货"
    );

    CountDownLatch bothTransactionsReadApproved = new CountDownLatch(2);
    WarehouseRepository coordinatedRepository = new WarehouseRepository(jdbc) {
      private final ThreadLocal<Boolean> firstRead = ThreadLocal.withInitial(() -> true);

      @Override
      public Optional<WarehouseReturnResponse> returnOrder(long tenantId, String requestedReturnId) {
        Optional<WarehouseReturnResponse> order = super.returnOrder(tenantId, requestedReturnId);
        if (firstRead.get()) {
          firstRead.set(false);
          bothTransactionsReadApproved.countDown();
          try {
            if (!bothTransactionsReadApproved.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("并发退货确认未能同时读取初始状态");
            }
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发退货确认测试被中断", ex);
          }
        }
        return order;
      }
    };
    WarehouseService concurrentService = new WarehouseService(
        coordinatedRepository, accessControl, businessScopeResolver,
        topologyService, topologyRepository);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> first = executor.submit(() ->
          receiveReturnInTransaction(transactionManager, concurrentService, returnId));
      Future<String> second = executor.submit(() ->
          receiveReturnInTransaction(transactionManager, concurrentService, returnId));

      assertThat(List.of(
          first.get(10, TimeUnit.SECONDS),
          second.get(10, TimeUnit.SECONDS)
      )).containsExactlyInAnyOrder("RECEIVED", "BAD_RETURN_STATUS");
    } finally {
      executor.shutdownNow();
    }

    assertThat(batchQuantity(regionalWarehouseId, itemId, "quantity"))
        .isEqualByComparingTo("12.00");
    assertThat(inventoryQuantity(regionalWarehouseId, itemId, "on_hand_quantity"))
        .isEqualByComparingTo("12.00");
    assertThat(jdbc.queryForObject("""
        select quantity from store_inventory
        where tenant_id = 1 and store_id = 'sd-store-1' and item_id = ?
        """, BigDecimal.class, itemId)).isEqualByComparingTo("8.00");
    assertThat(jdbc.queryForObject("""
        select count(*) from warehouse_stock_movement
        where tenant_id = 1 and source_type = 'RETURN' and source_id = ?
          and movement_type = 'RETURN_IN'
        """, Integer.class, returnId)).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from store_inventory_movement
        where tenant_id = 1 and source_type = 'STORE_RETURN' and source_id = ?
        """, Integer.class, returnId)).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from operation_log
        where tenant_id = 1 and action = '确认收到配送退货' and target_id = ?
        """, Integer.class, returnId)).isOne();
    assertThat(jdbc.queryForObject("""
        select count(*) from todo_action
        where tenant_id = 1 and todo_id = ? and action_type = 'WAREHOUSE_RETURN_RECEIVE'
        """, Integer.class, "warehouse-return-" + returnId)).isOne();
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

  @Test
  void foreignTenantWarehouseIdIsForbiddenAndAuditedWhileUnknownIdStaysNotFound() {
    jdbc.update("""
        insert into tenant(id, name, industry, scale, status, created_at)
        values (2, '隔离验收租户', 'chain_store', 'qa', 'ACTIVE', current_timestamp)
        """);
    jdbc.update("""
        insert into warehouse_facility(
          tenant_id, code, name, warehouse_type, region_code, parent_warehouse_id,
          external_purchase_allowed, store_supply_allowed, enabled, created_at
        ) values (2, 'QA-FOREIGN-CENTRAL', '隔离验收总仓', 'CENTRAL', 'JINGZHOU', null, 1, 1, 1, current_timestamp)
        """);
    long foreignWarehouseId = jdbc.queryForObject("""
        select id from warehouse_facility
        where tenant_id = 2 and code = 'QA-FOREIGN-CENTRAL'
        """, Long.class);

    assertThatThrownBy(() -> topologyService.requireVisibleFacility(
        regionalManager, foreignWarehouseId, "查看跨租户仓库"))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("FORBIDDEN");
          assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });
    verify(auditRepository).writePermissionDenied(
        regionalManager,
        "查看跨租户仓库",
        "WAREHOUSE",
        Long.toString(foreignWarehouseId),
        null,
        "仓库不在当前账号的数据范围内");

    assertThatThrownBy(() -> topologyService.requireVisibleFacility(
        regionalManager, 9_999_999L, "查看不存在仓库"))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("WAREHOUSE_NOT_FOUND");
          assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    verifyNoMoreInteractions(auditRepository);
  }

  @Test
  void regionalTransferContextLocksTheParentRouteAndUsesSourceAvailability() {
    allowTransferActions(regionalManager);

    WarehouseTransferResponse draft = networkService.create(
        regionalManager,
        new WarehouseTransferCreateRequest(
            centralWarehouseId,
            regionalWarehouseId,
            List.of(new WarehouseTransferLineRequest(itemId, BigDecimal.ONE, "山东补货")),
            "山东分仓补货草稿",
            "context-regional-draft"));

    WarehouseTransferContextResponse context = networkService.transferContext(
        regionalManager, regionalWarehouseId);

    assertThat(context.mode()).isEqualTo("REQUEST_REPLENISHMENT");
    assertThat(context.currentWarehouse().id()).isEqualTo(regionalWarehouseId);
    assertThat(context.workbenchLabel()).isEqualTo("向上级总仓申请补货");
    assertThat(context.routes()).singleElement().satisfies(route -> {
      assertThat(route.formAction()).isEqualTo("REQUEST_REPLENISHMENT");
      assertThat(route.sourceWarehouse().id()).isEqualTo(centralWarehouseId);
      assertThat(route.targetWarehouse().id()).isEqualTo(regionalWarehouseId);
      assertThat(route.actions().canCreate()).isTrue();
      assertThat(route.actions().canSubmit()).isTrue();
      assertThat(route.actions().canCancel()).isTrue();
      assertThat(route.actions().canApprove()).isFalse();
      assertThat(route.actions().canShip()).isFalse();
      assertThat(route.actions().canReceive()).isTrue();
      assertThat(route.materials()).anySatisfy(material -> {
        assertThat(material.itemId()).isEqualTo(itemId);
        assertThat(material.availableQuantity()).isGreaterThan(BigDecimal.ZERO);
        assertThat(material.shortageMessage()).isNull();
      });
    });
    assertThat(context.todos().draft()).isOne();
    assertThat(context.todos().pendingApproval()).isZero();

    networkService.submit(regionalManager, draft.id(),
        new WarehouseTransferActionRequest("context-regional-submit", "提交补货申请"));
    allowTransferActions(centralManager);
    WarehouseTransferContextResponse centralContext = networkService.transferContext(
        centralManager, centralWarehouseId);
    assertThat(centralContext.todos().pendingApproval()).isOne();
  }

  @Test
  void centralContextRequiresBothScopesToCreateButKeepsSourceSideActions() {
    allowTransferActions(centralManager);

    WarehouseTransferContextResponse sourceOnly = networkService.transferContext(
        centralManager, centralWarehouseId);

    assertThat(sourceOnly.mode()).isEqualTo("PROACTIVE_ALLOCATION");
    assertThat(sourceOnly.routes()).singleElement().satisfies(route -> {
      assertThat(route.sourceWarehouse().id()).isEqualTo(centralWarehouseId);
      assertThat(route.targetWarehouse().id()).isEqualTo(regionalWarehouseId);
      assertThat(route.actions().canCreate()).isFalse();
      assertThat(route.actions().canSubmit()).isFalse();
      assertThat(route.actions().canCancel()).isFalse();
      assertThat(route.actions().canApprove()).isTrue();
      assertThat(route.actions().canShip()).isTrue();
      assertThat(route.actions().canReceive()).isFalse();
      assertThat(route.materials()).isEmpty();
    });
    assertThatThrownBy(() -> networkService.create(
        centralManager,
        new WarehouseTransferCreateRequest(
            centralWarehouseId,
            regionalWarehouseId,
            List.of(new WarehouseTransferLineRequest(itemId, BigDecimal.ONE, "越权主动配货")),
            "仅总仓范围不能选分仓",
            "context-central-source-only")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("FORBIDDEN"));

    when(accessControl.dataScope(centralManager, DataScopeDomains.WAREHOUSE))
        .thenReturn(warehouseScope(centralWarehouseId, regionalWarehouseId));
    WarehouseTransferContextResponse bothEndpoints = networkService.transferContext(
        centralManager, centralWarehouseId);

    assertThat(bothEndpoints.routes()).singleElement().satisfies(route -> {
      assertThat(route.formAction()).isEqualTo("PROACTIVE_ALLOCATION");
      assertThat(route.actions().canCreate()).isTrue();
      assertThat(route.actions().canSubmit()).isTrue();
      assertThat(route.actions().canApprove()).isTrue();
      assertThat(route.actions().canShip()).isTrue();
      assertThat(route.actions().canReceive()).isFalse();
    });
    WarehouseTransferResponse proactivelyCreated = networkService.create(
        centralManager,
        new WarehouseTransferCreateRequest(
            centralWarehouseId,
            regionalWarehouseId,
            List.of(new WarehouseTransferLineRequest(itemId, BigDecimal.ONE, "总仓主动配货")),
            "总仓主动配货",
            "context-central-proactive"));
    assertThat(proactivelyCreated.sourceWarehouseId()).isEqualTo(centralWarehouseId);
    assertThat(proactivelyCreated.targetWarehouseId()).isEqualTo(regionalWarehouseId);
  }

  @Test
  void contextAndMutationsRejectDisabledNonDirectReverseAndPeerRoutes() {
    allowTransferActions(regionalManager);
    jdbc.update("""
        update warehouse_transfer_route set enabled = 0
        where tenant_id = 1 and source_warehouse_id = ? and target_warehouse_id = ?
        """, centralWarehouseId, regionalWarehouseId);

    WarehouseTransferContextResponse disabled = networkService.transferContext(
        regionalManager, regionalWarehouseId);
    assertThat(disabled.mode()).isEqualTo("NONE");
    assertThat(disabled.routes()).isEmpty();
    assertThatThrownBy(() -> networkService.create(
        regionalManager,
        new WarehouseTransferCreateRequest(
            centralWarehouseId,
            regionalWarehouseId,
            List.of(new WarehouseTransferLineRequest(itemId, BigDecimal.ONE, "禁用路线")),
            "路线已禁用",
            "context-disabled-route")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("WAREHOUSE_ROUTE_FORBIDDEN"));

    jdbc.update("""
        update warehouse_transfer_route set enabled = 1
        where tenant_id = 1 and source_warehouse_id = ? and target_warehouse_id = ?
        """, centralWarehouseId, regionalWarehouseId);
    long otherCentralId = insertFacility("OTHER-CENTRAL", "其他总仓", "CENTRAL", null);
    long nonDirectRegionalId = insertFacility(
        "OTHER-REGIONAL", "非直属分仓", "REGIONAL", otherCentralId);
    long siblingRegionalId = insertFacility(
        "SIBLING-REGIONAL", "同级分仓", "REGIONAL", centralWarehouseId);
    jdbc.update("""
        insert into warehouse_transfer_route(
          tenant_id, source_warehouse_id, target_warehouse_id, enabled, created_at
        ) values (1, ?, ?, 1, current_timestamp)
        """, centralWarehouseId, nonDirectRegionalId);
    jdbc.update("""
        insert into warehouse_transfer_route(
          tenant_id, source_warehouse_id, target_warehouse_id, enabled, created_at
        ) values (1, ?, ?, 1, current_timestamp)
        """, regionalWarehouseId, centralWarehouseId);
    jdbc.update("""
        insert into warehouse_transfer_route(
          tenant_id, source_warehouse_id, target_warehouse_id, enabled, created_at
        ) values (1, ?, ?, 1, current_timestamp)
        """, regionalWarehouseId, siblingRegionalId);

    allowTransferActions(centralManager);
    when(accessControl.dataScope(centralManager, DataScopeDomains.WAREHOUSE))
        .thenReturn(warehouseScope(
            centralWarehouseId, regionalWarehouseId, nonDirectRegionalId, siblingRegionalId));
    WarehouseTransferContextResponse central = networkService.transferContext(
        centralManager, centralWarehouseId);
    assertThat(central.routes()).extracting(route -> route.targetWarehouse().id())
        .contains(regionalWarehouseId)
        .doesNotContain(nonDirectRegionalId);
    assertThatThrownBy(() -> networkService.create(
        centralManager,
        new WarehouseTransferCreateRequest(
            centralWarehouseId,
            nonDirectRegionalId,
            List.of(new WarehouseTransferLineRequest(itemId, BigDecimal.ONE, "非直属分仓")),
            "不得跨级调拨",
            "context-non-direct")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("FORBIDDEN"));

    allowTransferActions(regionalManager);
    when(accessControl.dataScope(regionalManager, DataScopeDomains.WAREHOUSE))
        .thenReturn(warehouseScope(
            regionalWarehouseId, centralWarehouseId, siblingRegionalId, nonDirectRegionalId));
    assertThatThrownBy(() -> networkService.create(
        regionalManager,
        new WarehouseTransferCreateRequest(
            regionalWarehouseId,
            centralWarehouseId,
            List.of(new WarehouseTransferLineRequest(itemId, BigDecimal.ONE, "反向调拨")),
            "分仓不得反向补货",
            "context-reverse")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> networkService.create(
        regionalManager,
        new WarehouseTransferCreateRequest(
            regionalWarehouseId,
            siblingRegionalId,
            List.of(new WarehouseTransferLineRequest(itemId, BigDecimal.ONE, "同级互调")),
            "分仓不得互调",
            "context-peer")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("FORBIDDEN"));

    String reverseReview = insertTransferOrder(
        "invalid-reverse-review", regionalWarehouseId, centralWarehouseId, "SUBMITTED");
    String peerShip = insertTransferOrder(
        "invalid-peer-ship", regionalWarehouseId, siblingRegionalId, "APPROVED");
    String peerReceive = insertTransferOrder(
        "invalid-peer-receive", regionalWarehouseId, siblingRegionalId, "SHIPPED");
    assertThatThrownBy(() -> networkService.review(
        regionalManager, reverseReview, new WarehouseTransferReviewRequest(true, "不得审批反向路线")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> networkService.ship(
        regionalManager, peerShip, new WarehouseTransferActionRequest("invalid-peer-ship", "不得发货")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> networkService.receive(
        regionalManager, peerReceive, WarehouseTransferReceiveRequest.empty()))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void contextRejectsCrossScopeWarehouseAndWarnsWhenSourceStockIsEmpty() {
    assertThatThrownBy(() -> networkService.transferContext(regionalManager, centralWarehouseId))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("FORBIDDEN"));

    allowTransferActions(regionalManager);
    allowTransferActions(centralManager);
    WarehouseTransferResponse draft = networkService.create(
        regionalManager,
        new WarehouseTransferCreateRequest(
            centralWarehouseId,
            regionalWarehouseId,
            List.of(new WarehouseTransferLineRequest(itemId, BigDecimal.ONE, "库存不足测试")),
            "库存不足",
            "context-stock-insufficient"));
    networkService.submit(regionalManager, draft.id(),
        new WarehouseTransferActionRequest("context-stock-insufficient-submit", "提交"));
    jdbc.update("""
        update warehouse_inventory
        set on_hand_quantity = 0, reserved_quantity = 0, version = version + 1
        where tenant_id = 1 and warehouse_id = ? and item_id = ?
        """, centralWarehouseId, itemId);

    WarehouseTransferContextResponse context = networkService.transferContext(
        regionalManager, regionalWarehouseId);
    assertThat(context.routes()).singleElement().satisfies(route ->
        assertThat(route.materials()).anySatisfy(material -> {
          assertThat(material.itemId()).isEqualTo(itemId);
          assertThat(material.availableQuantity()).isEqualByComparingTo("0.00");
          assertThat(material.shortageMessage()).isEqualTo("当前可发数量为 0，库存不足");
        }));
    assertThatThrownBy(() -> networkService.review(
        centralManager, draft.id(), new WarehouseTransferReviewRequest(true, "仍由后端校验库存")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getCode()).isEqualTo("WAREHOUSE_STOCK_INSUFFICIENT"));
  }

  @Test
  void movementReportFiltersByStoreDateItemAndBusinessDirection() {
    jdbc.update("""
        insert into warehouse_stock_movement(
          tenant_id, warehouse_id, item_id, movement_type, quantity_delta,
          source_type, source_id, store_id, created_at
        ) values
          (1, ?, ?, 'TRANSFER_RECEIVE', 4, 'TEST', 'movement-in', 'sd-store-1', timestamp '2026-07-20 09:00:00'),
          (1, ?, ?, 'LOSS_OUT', -2, 'TEST', 'movement-out', 'sd-store-1', timestamp '2026-07-21 10:00:00'),
          (1, ?, ?, 'ADJUST_IN', 1, 'TEST', 'movement-adjust', 'sd-store-1', timestamp '2026-07-22 11:00:00'),
          (1, ?, ?, 'IN', 9, 'TEST', 'movement-other-store', null, timestamp '2026-07-20 12:00:00'),
          (1, ?, ?, 'OUT', -7, 'TEST', 'movement-old', 'sd-store-1', timestamp '2026-06-30 12:00:00'),
          (1, ?, ?, 'RESERVE', 0, 'TEST', 'movement-zero', 'sd-store-1', timestamp '2026-07-23 12:00:00')
        """,
        regionalWarehouseId, itemId,
        regionalWarehouseId, itemId,
        regionalWarehouseId, itemId,
        regionalWarehouseId, itemId,
        regionalWarehouseId, itemId,
        regionalWarehouseId, itemId);

    LocalDate start = LocalDate.of(2026, 7, 1);
    LocalDate end = LocalDate.of(2026, 7, 31);

    assertThat(warehouseRepository.movementsFiltered(
        TENANT_ID, regionalWarehouseId, start, end,
        List.of("sd-store-1"), List.of(itemId), List.of("IN"), List.of(), 0, 50))
        .extracting(WarehouseStockMovementResponse::sourceId)
        .containsExactly("movement-in");

    assertThat(warehouseRepository.movementsFiltered(
        TENANT_ID, regionalWarehouseId, start, end,
        List.of("sd-store-1"), List.of(itemId), List.of("OUT"), List.of(), 0, 50))
        .extracting(WarehouseStockMovementResponse::sourceId)
        .containsExactly("movement-out");

    assertThat(warehouseRepository.movementsFiltered(
        TENANT_ID, regionalWarehouseId, start, end,
        List.of("sd-store-1"), List.of(itemId), List.of("ADJUST"), List.of(), 0, 50))
        .extracting(WarehouseStockMovementResponse::sourceId)
        .containsExactly("movement-adjust");

    WarehouseRepository.MovementSummary summary = warehouseRepository.movementsFilteredSummary(
        TENANT_ID, regionalWarehouseId, start, end,
        List.of("sd-store-1"), List.of(itemId), List.of(), List.of());
    assertThat(warehouseRepository.movementsFilteredCount(
        TENANT_ID, regionalWarehouseId, start, end,
        List.of("sd-store-1"), List.of(itemId), List.of(), List.of())).isEqualTo(3);
    assertThat(summary.totalIn()).isEqualByComparingTo("5");
    assertThat(summary.totalOut()).isEqualByComparingTo("2");
    assertThat(summary.netChange()).isEqualByComparingTo("3");

    jdbc.update(
        "update store_branch set supply_warehouse_id = ? where tenant_id = 1 and id = 'sd-store-1'",
        centralWarehouseId);
    assertThat(warehouseRepository.movementFilterStores(TENANT_ID, regionalWarehouseId))
        .extracting(WarehouseMovementFilterOptionsResponse.StoreOption::id)
        .contains("sd-store-1");
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

  private String receiveReturnInTransaction(
      DataSourceTransactionManager transactionManager,
      WarehouseService service,
      String returnId
  ) {
    try {
      WarehouseReturnResponse response = new TransactionTemplate(transactionManager).execute(status ->
          service.receiveReturn(
              regionalManager,
              returnId,
              new WarehouseReturnReceiveRequest("并发确认退货")));
      return response == null ? "NO_RESPONSE" : response.status();
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

  private long insertFacility(String code, String name, String type, Long parentWarehouseId) {
    jdbc.update("""
        insert into warehouse_facility(
          tenant_id, code, name, warehouse_type, region_code, parent_warehouse_id,
          external_purchase_allowed, store_supply_allowed, enabled, created_at
        ) values (1, ?, ?, ?, 'SHANDONG', ?, 0, 1, 1, current_timestamp)
        """, code, name, type, parentWarehouseId);
    return facilityId(code);
  }

  private String insertTransferOrder(
      String id,
      long sourceWarehouseId,
      long targetWarehouseId,
      String status
  ) {
    jdbc.update("""
        insert into warehouse_transfer_order(
          id, tenant_id, transfer_no, source_warehouse_id, target_warehouse_id,
          status, idempotency_key, total_amount, version, created_at
        ) values (?, 1, ?, ?, ?, ?, ?, 0, 0, current_timestamp)
        """, id, "TEST-" + id, sourceWarehouseId, targetWarehouseId, status, id + "-key");
    return id;
  }

  private void allowTransferActions(AuthUser user) {
    when(accessControl.hasPermission(user, PermissionCodes.WAREHOUSE_TRANSFER_REQUEST)).thenReturn(true);
    when(accessControl.hasPermission(user, PermissionCodes.WAREHOUSE_TRANSFER_APPROVE)).thenReturn(true);
    when(accessControl.hasPermission(user, PermissionCodes.WAREHOUSE_TRANSFER_SHIP)).thenReturn(true);
    when(accessControl.hasPermission(user, PermissionCodes.WAREHOUSE_TRANSFER_RECEIVE)).thenReturn(true);
  }

  private DataScope warehouseScope(long... warehouseIds) {
    return new DataScope(
        DataScopeModes.WAREHOUSE_LIST,
        List.of(),
        Arrays.stream(warehouseIds).mapToObj(Long::toString).toList());
  }

  private AuthUser user(long id, String username, String displayName, String role, String storeId) {
    return new AuthUser(id, TENANT_ID, "default", username, "", displayName, role, storeId, true);
  }
}
