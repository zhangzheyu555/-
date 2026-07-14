package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WarehouseServiceTest {
  private WarehouseService service;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("create alias if not exists date_format for \"" + WarehouseServiceTest.class.getName() + ".dateFormat\"");
    createSchema();
    seedData();
    service = new WarehouseService(new WarehouseRepository(jdbcTemplate));
  }

  @Test
  void storeManagerCanOrderAndReceiveButCannotManageWarehouse() {
    assertThatThrownBy(() -> service.saveItem(storeManager(), itemRequest("NEW", "新品")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    BigDecimal stockBeforeRequisition = itemStock(1L);
    WarehouseRequisitionResponse created = service.createRequisition(
        storeManager(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("5"), "门店补货")),
            "店长叫货"
        )
    );

    assertThat(created.storeId()).isEqualTo("rg1");
    assertThat(created.statusLabel()).isEqualTo("待仓库处理");
    assertThat(itemStock(1L)).isEqualByComparingTo(stockBeforeRequisition);
    WarehouseOverviewResponse storeOverview = service.overview(storeManager());
    assertThat(storeOverview.summary().stockValue()).isEqualByComparingTo("0.00");
    assertThat(storeOverview.items()).allSatisfy(item -> {
      assertThat(item.unitPrice()).isEqualByComparingTo("0.00");
      assertThat(item.stockValue()).isEqualByComparingTo("0.00");
    });
    WarehouseItemResponse storeItemBeforeReceive = storeOverview.items().stream()
        .filter(item -> item.id().equals(1L))
        .findFirst()
        .orElseThrow();
    assertThat(storeItemBeforeReceive.stockQuantity()).isEqualByComparingTo("0.00");
    assertThat(storeItemBeforeReceive.storeStockQuantity()).isEqualByComparingTo("0.00");
    assertThat(storeItemBeforeReceive.warehouseAvailableQuantity()).isEqualByComparingTo("30.00");
    assertThat(storeOverview.suppliers()).isEmpty();
    assertThat(storeOverview.purchaseOrders()).isEmpty();

    BigDecimal stockBefore = itemStock(1L);
    service.review(
        warehouseManager(),
        created.id(),
        new WarehouseRequisitionReviewRequest(true, List.of(), "可以发货")
    );
    service.ship(warehouseManager(), created.id());

    assertThat(itemStock(1L)).isEqualByComparingTo(stockBefore.subtract(new BigDecimal("5.00")));
    assertThat(storeStock("rg1", 1L)).isEqualByComparingTo("0.00");
    assertThat(todoActionCount("warehouse-" + created.id(), "WAREHOUSE_SHIP")).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from warehouse_stock_movement where movement_type = 'OUT' and source_id = ?",
        Integer.class,
        created.id()
    )).isEqualTo(1);

    WarehouseRequisitionResponse shipped = service.requisitions(storeManager()).stream()
        .filter(row -> created.id().equals(row.id()))
        .findFirst()
        .orElseThrow();
    assertThat(shipped.statusLabel()).isEqualTo("待门店收货");

    service.receiveByStore(storeManager(), created.id(), new WarehouseReceiptRequest("实收无误"));

    assertThat(storeStock("rg1", 1L)).isEqualByComparingTo("5.00");
    assertThat(storeInventoryMovementCount("rg1", 1L, "IN", "STORE_RECEIPT")).isEqualTo(1);
    WarehouseItemResponse storeItemAfterReceive = service.overview(storeManager()).items().stream()
        .filter(item -> item.id().equals(1L))
        .findFirst()
        .orElseThrow();
    assertThat(storeItemAfterReceive.stockQuantity()).isEqualByComparingTo("5.00");
    assertThat(storeItemAfterReceive.storeStockQuantity()).isEqualByComparingTo("5.00");
    assertThat(storeItemAfterReceive.warehouseAvailableQuantity()).isEqualByComparingTo("25.00");
    assertThat(todoActionCount("store-receipt-" + created.id(), "STORE_RECEIVE")).isEqualTo(1);
    WarehouseRequisitionResponse received = service.requisitions(storeManager()).stream()
        .filter(row -> created.id().equals(row.id()))
        .findFirst()
        .orElseThrow();
    assertThat(received.statusLabel()).isEqualTo("门店已收货");
  }

  @Test
  void storeManagerCannotUseCentralWarehouseActionsEvenWhenPermissionsAreExplicitlyAllowed() {
    AccessControlService permissiveAccessControl = mock(AccessControlService.class);
    AuthUser manager = storeManager();
    doNothing().when(permissiveAccessControl).requireWarehouseCentralManage(manager);
    doNothing().when(permissiveAccessControl).requireWarehouseRequisitionReview(manager);
    WarehouseService permissionOverrideService = new WarehouseService(
        new WarehouseRepository(jdbcTemplate), permissiveAccessControl);

    assertForbidden(() -> permissionOverrideService.saveItem(manager, itemRequest("FORBIDDEN", "越权物料")));
    assertForbidden(() -> permissionOverrideService.receiveStock(manager, new WarehouseStockBatchRequest(
        1L, "FORBIDDEN-BATCH", "2026-07-13", null,
        BigDecimal.ONE, BigDecimal.ONE, "越权采购入库")));
    assertForbidden(() -> permissionOverrideService.createPurchaseOrder(manager, new WarehousePurchaseOrderRequest(
        1L,
        "越权采购单",
        List.of(new WarehousePurchaseOrderLineRequest(
            1L, BigDecimal.ONE, BigDecimal.ONE, "越权采购")))));
    assertForbidden(() -> permissionOverrideService.review(
        manager, "REQ-NOT-NEEDED",
        new WarehouseRequisitionReviewRequest(true, List.of(), "越权审核")));
    assertForbidden(() -> permissionOverrideService.ship(manager, "REQ-NOT-NEEDED"));
    assertForbidden(() -> permissionOverrideService.reviewReturn(
        manager, "RETURN-NOT-NEEDED", new WarehouseReturnReviewRequest(true, "越权退货审核")));
    assertForbidden(() -> permissionOverrideService.receiveReturn(
        manager, "RETURN-NOT-NEEDED", new WarehouseReturnReceiveRequest("越权退货入库")));
  }

  @Test
  void storeManagerCanReceiveLegacyShippedRequisitionWithoutDeliveryOrder() {
    WarehouseRequisitionResponse created = service.createRequisition(
        storeManager(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("4"), "旧数据补建配送单")),
            "旧数据确认收货"
        )
    );
    service.review(warehouseManager(), created.id(), new WarehouseRequisitionReviewRequest(true, List.of(), "可以发货"));
    service.ship(warehouseManager(), created.id());

    jdbcTemplate.update("""
        delete from warehouse_delivery_order_line
        where tenant_id = 1
          and delivery_id in (
            select id from warehouse_delivery_order where tenant_id = 1 and requisition_id = ?
          )
        """, created.id());
    jdbcTemplate.update("delete from warehouse_delivery_order where tenant_id = 1 and requisition_id = ?", created.id());

    service.receiveByStore(storeManager(), created.id(), new WarehouseReceiptRequest("旧数据确认收货"));

    WarehouseRequisitionResponse received = service.requisitions(storeManager()).stream()
        .filter(row -> created.id().equals(row.id()))
        .findFirst()
        .orElseThrow();
    assertThat(received.status()).isEqualTo("RECEIVED");
    assertThat(storeStock("rg1", 1L)).isEqualByComparingTo("4.00");
    assertThat(storeInventoryMovementCount("rg1", 1L, "IN", "STORE_RECEIPT")).isEqualTo(1);
    assertThat(todoActionCount("store-receipt-" + created.id(), "STORE_RECEIVE")).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from warehouse_delivery_order where tenant_id = 1 and requisition_id = ?",
        Integer.class,
        created.id()
    )).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select shipped_quantity from warehouse_delivery_order_line where tenant_id = 1 and item_id = 1",
        BigDecimal.class
    )).isEqualByComparingTo("4.00");
    assertThat(jdbcTemplate.queryForObject(
        "select received_quantity from warehouse_delivery_order_line where tenant_id = 1 and item_id = 1",
        BigDecimal.class
    )).isEqualByComparingTo("4.00");
  }

  @Test
  void storeManagerCannotReceiveBeforeWarehouseShips() {
    WarehouseRequisitionResponse created = service.createRequisition(
        storeManager(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("3"), "未发货确认收货")),
            "未发货不能收货"
        )
    );

    assertThatThrownBy(() -> service.receiveByStore(storeManager(), created.id(), new WarehouseReceiptRequest("误点收货")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException exception = (BusinessException) error;
          assertThat(exception.getCode()).isEqualTo("BAD_STATUS");
          assertThat(exception.getMessage()).contains("这张叫货单还没有发货，不能确认收货");
        });
  }

  @Test
  void warehouseManagerCanSetMinimumStockAlert() {
    service.updateAlertSettings(
        warehouseManager(),
        1L,
        new WarehouseAlertSettingsRequest(new BigDecimal("40"), true, 5)
    );

    WarehouseItemResponse item = service.overview(warehouseManager()).items().stream()
        .filter(row -> row.id().equals(1L))
        .findFirst()
        .orElseThrow();

    assertThat(item.minStockQuantity()).isEqualByComparingTo("40.00");
    assertThat(item.alertEnabled()).isTrue();
    assertThat(item.expiryAlertDays()).isEqualTo(5);
    assertThat(item.stockStatus()).isEqualTo("低库存");
    assertThat(item.alertLevel()).isEqualTo("LOW");
    assertThat(item.alertText()).contains("当前库存 30", "最低安全库存 40");
  }

  @Test
  void bossCanManageWarehouseAndStoreRequestsAreIdempotent() {
    service.saveItem(boss(), itemRequest("BOSS-WRITE", "老板维护物料"));
    WarehouseItemResponse savedItem = service.overview(boss()).items().stream()
        .filter(item -> "BOSS-WRITE".equals(item.code()))
        .findFirst()
        .orElseThrow();
    WarehouseRequisitionResponse bossRequest = service.createRequisition(
        boss(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, BigDecimal.ONE, "老板代门店叫货")),
            "老板处理叫货",
            "boss-request"
        )
    );

    assertThat(savedItem.code()).isEqualTo("BOSS-WRITE");
    assertThat(bossRequest.storeId()).isEqualTo("rg1");
    int requisitionCountAfterBoss = jdbcTemplate.queryForObject(
        "select count(*) from store_requisition where tenant_id = 1",
        Integer.class
    );

    WarehouseRequisitionRequest request = new WarehouseRequisitionRequest(
        "rg1",
        List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("2"), "幂等叫货")),
        "同一次叫货请求",
        "req-idempotent-001"
    );
    WarehouseRequisitionResponse first = service.createRequisition(storeManager(), request);
    WarehouseRequisitionResponse repeated = service.createRequisition(storeManager(), request);

    assertThat(repeated.id()).isEqualTo(first.id());
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from store_requisition where tenant_id = 1",
        Integer.class
    )).isEqualTo(requisitionCountAfterBoss + 1);
    assertThat(operationLogCount("提交叫货", first.id())).isEqualTo(1);
  }

  @Test
  void stockReceiveRequestIsIdempotentAndCategoryDeleteProtectsReferencedData() {
    WarehouseStockBatchRequest receive = new WarehouseStockBatchRequest(
        1L,
        "IDEMPOTENT-BATCH",
        "2026-07-10",
        "2026-08-10",
        new BigDecimal("3"),
        new BigDecimal("8"),
        "重复点击测试",
        "stock-idempotent-001"
    );
    service.receiveStock(warehouseManager(), receive);
    service.receiveStock(warehouseManager(), receive);

    assertThat(batchQuantity("IDEMPOTENT-BATCH")).isEqualByComparingTo("3.00");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from warehouse_stock_movement where source_id = 'IDEMPOTENT-BATCH'",
        Integer.class
    )).isEqualTo(1);
    assertThat(operationLogCount("仓库入库", "IDEMPOTENT-BATCH")).isEqualTo(1);

    WarehouseItemCategoryResponse category = service.saveItemCategory(
        warehouseManager(),
        new WarehouseItemCategoryRequest(null, "可删除分类", null, 90, true)
    );
    service.deleteItemCategory(warehouseManager(), category.id());
    assertThat(service.itemCategories(warehouseManager()))
        .extracting(WarehouseItemCategoryResponse::name)
        .doesNotContain("可删除分类");

    assertThatThrownBy(() -> service.deleteItemCategory(warehouseManager(), 1L))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("CATEGORY_IN_USE"));
  }

  @Test
  void categoryCannotBeMovedBelowItsOwnChild() {
    WarehouseItemCategoryResponse parent = service.saveItemCategory(
        warehouseManager(),
        new WarehouseItemCategoryRequest(null, "原料", null, 100, true)
    );
    WarehouseItemCategoryResponse child = service.saveItemCategory(
        warehouseManager(),
        new WarehouseItemCategoryRequest(null, "冷藏原料", parent.id(), 110, true)
    );

    assertThatThrownBy(() -> service.saveItemCategory(
        warehouseManager(),
        new WarehouseItemCategoryRequest(parent.id(), "原料", child.id(), 100, true)
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_CATEGORY_PARENT"));
  }

  @Test
  void warehouseManagerShipsByReceivedDateFifoAcrossBatches() {
    jdbcTemplate.update("delete from warehouse_stock_batch where item_id = 1");
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(id, tenant_id, item_id, batch_no, received_date, expiry_date, quantity, unit_cost, note, created_at)
        values
          (10, 1, 1, 'A-OLD', '2026-07-01', '2026-08-01', 10.00, 80.00, '老批次', '2026-07-01 08:00:00'),
          (11, 1, 1, 'B-MID', '2026-07-05', '2026-08-05', 20.00, 81.00, '中批次', '2026-07-05 08:00:00'),
          (12, 1, 1, 'C-NEW', '2026-07-08', '2026-08-08', 30.00, 82.00, '新批次', '2026-07-08 08:00:00')
        """);

    WarehouseRequisitionResponse created = service.createRequisition(
        storeManager(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("25"), "FIFO测试叫货")),
            "店长叫货25件"
        )
    );
    service.review(warehouseManager(), created.id(), new WarehouseRequisitionReviewRequest(true, List.of(), "按先进先出发货"));
    service.ship(warehouseManager(), created.id());

    assertThat(batchQuantity("A-OLD")).isEqualByComparingTo("0.00");
    assertThat(batchQuantity("B-MID")).isEqualByComparingTo("5.00");
    assertThat(batchQuantity("C-NEW")).isEqualByComparingTo("30.00");

    List<Long> batchIds = jdbcTemplate.queryForList(
        "select batch_id from warehouse_stock_movement where source_id = ? and movement_type = 'OUT' order by id",
        Long.class,
        created.id()
    );
    List<BigDecimal> deltas = jdbcTemplate.queryForList(
        "select quantity_delta from warehouse_stock_movement where source_id = ? and movement_type = 'OUT' order by id",
        BigDecimal.class,
        created.id()
    );
    assertThat(batchIds).containsExactly(10L, 11L);
    assertThat(deltas).containsExactly(new BigDecimal("-10.00"), new BigDecimal("-15.00"));

    WarehouseRepository repository = new WarehouseRepository(jdbcTemplate);
    List<WarehouseRepository.WarehouseDeliveryPrintLine> printLines = repository.deliveryPrintLines(1L, created.id());
    assertThat(printLines).extracting(WarehouseRepository.WarehouseDeliveryPrintLine::batchNos)
        .containsExactly("A-OLD", "B-MID");
    assertThat(printLines).extracting(WarehouseRepository.WarehouseDeliveryPrintLine::shippedQuantity)
        .containsExactly(new BigDecimal("10.00"), new BigDecimal("15.00"));

    assertThat(service.overview(warehouseManager()).stockBatches())
        .extracting(WarehouseStockBatchResponse::batchNo)
        .contains("A-OLD", "B-MID", "C-NEW");
    assertThat(service.movements(warehouseManager()).stream()
        .filter(row -> created.id().equals(row.sourceId()))
        .map(WarehouseStockMovementResponse::batchNo)
        .toList()).contains("A-OLD", "B-MID");

    WarehouseRequisitionResponse tooMuch = service.createRequisition(
        storeManager(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("1000"), "超过库存")),
            "库存不足测试"
        )
    );
    service.review(warehouseManager(), tooMuch.id(), new WarehouseRequisitionReviewRequest(true, List.of(), "尝试发货"));
    assertThatThrownBy(() -> service.ship(warehouseManager(), tooMuch.id()))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("INSUFFICIENT_STOCK"));
  }

  @Test
  void warehouseManagerCanManageItemCategoryAndItemFile() {
    WarehouseItemCategoryResponse category = service.saveItemCategory(
        warehouseManager(),
        new WarehouseItemCategoryRequest(null, "冷冻品", null, 80, true)
    );

    assertThat(service.itemCategories(warehouseManager()))
        .extracting(WarehouseItemCategoryResponse::name)
        .contains("奶制品", "冷冻品");

    service.saveItem(
        warehouseManager(),
        new WarehouseItemRequest(
            null,
            "PEACH",
            "桃子",
            category.id(),
            "冷冻品",
            "https://example.test/peach.png",
            "件",
            "箱",
            "件",
            "克",
            "",
            "15斤/件",
            "A-01",
            new BigDecimal("12"),
            7,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0,
            0,
            new BigDecimal("20"),
            true,
            3,
            "鲜果",
            593,
            "水果",
            true,
            List.of(new WarehouseItemDepartmentRequest("采购部", "CG", "总部", "自购", "果蔬供应商"))
        )
    );

    WarehouseItemResponse item = service.items(warehouseManager()).stream()
        .filter(row -> "PEACH".equals(row.code()))
        .findFirst()
        .orElseThrow();
    assertThat(item.categoryId()).isEqualTo(category.id());
    assertThat(item.categoryName()).isEqualTo("冷冻品");
    assertThat(item.stockUnit()).isEqualTo("件");
    assertThat(item.departments()).hasSize(1);
    assertThat(item.departments().get(0).supplierName()).isEqualTo("果蔬供应商");

    service.setItemEnabled(warehouseManager(), item.id(), new WarehouseItemEnabledRequest(false));

    assertThat(service.items(storeManager()))
        .extracting(WarehouseItemResponse::code)
        .doesNotContain("PEACH");
    assertThatThrownBy(() -> service.receiveStock(
        warehouseManager(),
        new WarehouseStockBatchRequest(item.id(), "B002", "2026-07-08", null, BigDecimal.ONE, BigDecimal.ONE, "")
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("ITEM_NOT_FOUND"));
  }

  @Test
  void storeManagerCanCreateReturnOrderAndWarehouseManagerOnlyProcessesIt() {
    WarehouseRequisitionResponse ownSource = service.createRequisition(
        storeManager(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("5"), "退货来源叫货")),
            "退货来源叫货"
        )
    );
    service.review(warehouseManager(), ownSource.id(), new WarehouseRequisitionReviewRequest(true, List.of(), "通过"));
    service.ship(warehouseManager(), ownSource.id());
    service.receiveByStore(storeManager(), ownSource.id(), new WarehouseReceiptRequest("实收无误"));
    assertThat(storeStock("rg1", 1L)).isEqualByComparingTo("5.00");

    WarehouseRequisitionResponse otherSource = service.createRequisition(
        otherStoreManager(),
        new WarehouseRequisitionRequest(
            "other-store",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("3"), "其他门店叫货")),
            "其他门店叫货"
        )
    );
    service.review(warehouseManager(), otherSource.id(), new WarehouseRequisitionReviewRequest(true, List.of(), "通过"));
    service.ship(warehouseManager(), otherSource.id());
    service.receiveByStore(otherStoreManager(), otherSource.id(), new WarehouseReceiptRequest("其他门店实收无误"));

    BigDecimal stockAfterShip = itemStock(1L);
    WarehouseReturnResponse ownStoreReturn = service.createReturn(
        storeManager(),
        new WarehouseReturnRequest(
            "rg1",
            ownSource.id(),
            "仓库",
            "包装破损",
            "门店退回仓库",
            "2026-07-08",
            List.of(new WarehouseReturnLineRequest(1L, new BigDecimal("2"), null, null, "包装破损")),
            List.of(new WarehouseReturnAttachmentRequest("return.txt", "text/plain", "5Yqg5Lu25YaF5a65"))
        )
    );
    WarehouseReturnResponse otherStoreReturn = service.createReturn(
        otherStoreManager(),
        new WarehouseReturnRequest(
            "other-store",
            otherSource.id(),
            "仓库",
            "其他门店退货",
            "其他门店退货",
            "2026-07-08",
            List.of(new WarehouseReturnLineRequest(1L, new BigDecimal("1"), null, null, "其他门店")),
            List.of()
        )
    );

    assertThatThrownBy(() -> service.createReturn(
        warehouseManager(),
        new WarehouseReturnRequest(
            "rg1",
            ownSource.id(),
            "仓库",
            "仓管代建退货",
            "",
            "2026-07-08",
            List.of(new WarehouseReturnLineRequest(1L, new BigDecimal("1"), null, null, "")),
            List.of()
        )
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN");
          assertThat(((BusinessException) error).getMessage()).contains("当前角色不能新建配送退货单");
        });

    assertThat(ownStoreReturn.returnNo()).startsWith("PSTH");
    assertThat(ownStoreReturn.sourceRequisitionId()).isEqualTo(ownSource.id());
    assertThat(ownStoreReturn.statusLabel()).isEqualTo("已提交");
    assertThat(ownStoreReturn.totalAmount()).isEqualByComparingTo("0.00");
    assertThat(jdbcTemplate.queryForObject(
        "select total_amount from warehouse_return_order where id = ?",
        BigDecimal.class,
        ownStoreReturn.id()
    )).isEqualByComparingTo("176.00");
    assertThat(ownStoreReturn.lines()).hasSize(1);
    assertThat(ownStoreReturn.lines().get(0).batchNo()).isEqualTo("B001");
    assertThat(ownStoreReturn.attachmentCount()).isEqualTo(1);
    assertThat(operationLogCount("提交配送退货单", ownStoreReturn.returnNo())).isEqualTo(1);

    WarehouseReturnResponse approved = service.reviewReturn(
        warehouseManager(),
        ownStoreReturn.id(),
        new WarehouseReturnReviewRequest(true, "同意退货")
    );
    assertThat(approved.statusLabel()).isEqualTo("仓库已通过");
    assertThat(approved.totalAmount()).isEqualByComparingTo("176.00");

    WarehouseReturnResponse received = service.receiveReturn(
        warehouseManager(),
        ownStoreReturn.id(),
        new WarehouseReturnReceiveRequest("退货已入库")
    );
    assertThat(received.statusLabel()).isEqualTo("仓库已收货");
    assertThat(itemStock(1L)).isEqualByComparingTo(stockAfterShip.add(new BigDecimal("2.00")));
    assertThat(storeStock("rg1", 1L)).isEqualByComparingTo("3.00");
    assertThat(storeInventoryMovementCount("rg1", 1L, "OUT", "STORE_RETURN")).isEqualTo(1);
    assertThat(todoActionCount("warehouse-return-" + ownStoreReturn.id(), "WAREHOUSE_RETURN_RECEIVE")).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from warehouse_stock_movement where movement_type = 'IN' and source_type = 'RETURN' and source_id = ?",
        Integer.class,
        ownStoreReturn.id()
    )).isEqualTo(1);

    assertThat(service.returns(warehouseManager()))
        .extracting(WarehouseReturnResponse::returnNo)
        .contains(ownStoreReturn.returnNo(), otherStoreReturn.returnNo());
    assertThat(service.returns(storeManager()))
        .extracting(WarehouseReturnResponse::returnNo)
        .containsExactly(ownStoreReturn.returnNo());

    assertThatThrownBy(() -> service.createReturn(
        storeManager(),
        new WarehouseReturnRequest(
            "rg1",
            otherSource.id(),
            "仓库",
            "跨门店退货",
            "",
            "2026-07-08",
            List.of(new WarehouseReturnLineRequest(1L, new BigDecimal("1"), null, null, "")),
            List.of()
        )
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void warehouseManagerCanSaveItemWithEmptyOptionalCatalogFields() {
    service.saveItem(
        warehouseManager(),
        new WarehouseItemRequest(
            null,
            "EMPTY-OPTIONAL",
            "空字段商品",
            1L,
            "奶制品",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            null,
            null,
            null,
            null,
            true,
            List.of()
        )
    );

    WarehouseItemResponse saved = service.items(warehouseManager()).stream()
        .filter(item -> "EMPTY-OPTIONAL".equals(item.code()))
        .findFirst()
        .orElseThrow();
    assertThat(saved.unit()).isEqualTo("件");
    assertThat(saved.unitPrice()).isEqualByComparingTo("0.00");
    assertThat(saved.minStockQuantity()).isEqualByComparingTo("0.00");
  }

  @Test
  void returnQuantityCanEqualAvailableReceivedQuantity() {
    WarehouseRequisitionResponse source = service.createRequisition(
        storeManager(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("2"), "退货边界测试")),
            "收货2件"
        )
    );
    service.review(warehouseManager(), source.id(), new WarehouseRequisitionReviewRequest(true, List.of(), "通过"));
    service.ship(warehouseManager(), source.id());
    service.receiveByStore(storeManager(), source.id(), new WarehouseReceiptRequest("实收2件"));

    WarehouseReturnResponse fullReturn = service.createReturn(
        storeManager(),
        new WarehouseReturnRequest(
            "rg1",
            source.id(),
            "仓库",
            "整单退回",
            "",
            "2026-07-08",
            List.of(new WarehouseReturnLineRequest(1L, new BigDecimal("2"), null, null, "整单退回")),
            List.of()
        )
    );

    assertThat(fullReturn.lines()).hasSize(1);
    assertThat(fullReturn.lines().get(0).quantity()).isEqualByComparingTo("2.00");

    WarehouseRequisitionResponse sourceForReject = service.createRequisition(
        storeManager(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("2"), "退货超量测试")),
            "收货2件"
        )
    );
    service.review(warehouseManager(), sourceForReject.id(), new WarehouseRequisitionReviewRequest(true, List.of(), "通过"));
    service.ship(warehouseManager(), sourceForReject.id());
    service.receiveByStore(storeManager(), sourceForReject.id(), new WarehouseReceiptRequest("实收2件"));

    assertThatThrownBy(() -> service.createReturn(
        storeManager(),
        new WarehouseReturnRequest(
            "rg1",
            sourceForReject.id(),
            "仓库",
            "超量退货",
            "",
            "2026-07-08",
            List.of(new WarehouseReturnLineRequest(1L, new BigDecimal("3"), null, null, "超量退货")),
            List.of()
        )
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException exception = (BusinessException) error;
          assertThat(exception.getCode()).isEqualTo("RETURN_QUANTITY_TOO_LARGE");
          assertThat(exception.getMessage()).contains("退货数量不能大于原单可退数量");
        });
  }

  @Test
  void returnAvailableQuantityIsLimitedByCurrentStoreInventory() {
    WarehouseRequisitionResponse source = service.createRequisition(
        storeManager(),
        new WarehouseRequisitionRequest(
            "rg1",
            List.of(new WarehouseRequisitionLineRequest(1L, new BigDecimal("2"), "本店库存限制测试")),
            "收货2件，本店只剩1件"
        )
    );
    service.review(warehouseManager(), source.id(), new WarehouseRequisitionReviewRequest(true, List.of(), "通过"));
    service.ship(warehouseManager(), source.id());
    service.receiveByStore(storeManager(), source.id(), new WarehouseReceiptRequest("实收2件"));
    jdbcTemplate.update(
        "update store_inventory set quantity = ? where tenant_id = ? and store_id = ? and item_id = ?",
        new BigDecimal("1.00"),
        1L,
        "rg1",
        1L
    );

    WarehouseRequisitionLineResponse visibleLine = service.requisitions(storeManager()).stream()
        .filter(row -> source.id().equals(row.id()))
        .findFirst()
        .orElseThrow()
        .lines()
        .get(0);
    assertThat(visibleLine.receivedQuantity()).isEqualByComparingTo("2.00");
    assertThat(visibleLine.returnedQuantity()).isEqualByComparingTo("0.00");
    assertThat(visibleLine.sourceAvailableReturnQuantity()).isEqualByComparingTo("2.00");
    assertThat(visibleLine.storeInventoryQuantity()).isEqualByComparingTo("1.00");
    assertThat(visibleLine.availableReturnQuantity()).isEqualByComparingTo("1.00");

    assertThatThrownBy(() -> service.createReturn(
        storeManager(),
        new WarehouseReturnRequest(
            "rg1",
            source.id(),
            "仓库",
            "库存不足退货",
            "",
            "2026-07-08",
            List.of(new WarehouseReturnLineRequest(1L, new BigDecimal("2"), null, null, "超过本店库存")),
            List.of()
        )
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException exception = (BusinessException) error;
          assertThat(exception.getCode()).isEqualTo("RETURN_STORE_STOCK_TOO_LOW");
          assertThat(exception.getMessage()).contains("退货数量不能大于本店当前库存");
        });

    WarehouseReturnResponse allowed = service.createReturn(
        storeManager(),
        new WarehouseReturnRequest(
            "rg1",
            source.id(),
            "仓库",
            "退1件",
            "",
            "2026-07-08",
            List.of(new WarehouseReturnLineRequest(1L, new BigDecimal("1"), null, null, "按本店库存退货")),
            List.of()
        )
    );

    assertThat(allowed.lines()).hasSize(1);
    assertThat(allowed.lines().get(0).quantity()).isEqualByComparingTo("1.00");
  }

  public static String dateFormat(Date date, String pattern) {
    if (date == null) {
      return null;
    }
    return new SimpleDateFormat(pattern.replace("%Y", "yyyy").replace("%m", "MM").replace("%d", "dd")).format(date);
  }

  private BigDecimal itemStock(long itemId) {
    return jdbcTemplate.queryForObject(
        "select coalesce(sum(quantity), 0) from warehouse_stock_batch where item_id = ?",
        BigDecimal.class,
        itemId
    );
  }

  private BigDecimal storeStock(String storeId, long itemId) {
    return jdbcTemplate.queryForObject(
        "select coalesce(sum(quantity), 0) from store_inventory where tenant_id = 1 and store_id = ? and item_id = ?",
        BigDecimal.class,
        storeId,
        itemId
    );
  }

  private int storeInventoryMovementCount(String storeId, long itemId, String movementType, String sourceType) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from store_inventory_movement where tenant_id = 1 and store_id = ? and item_id = ? and movement_type = ? and source_type = ?",
        Integer.class,
        storeId,
        itemId,
        movementType,
        sourceType
    );
    return count == null ? 0 : count;
  }

  private BigDecimal batchQuantity(String batchNo) {
    return jdbcTemplate.queryForObject(
        "select quantity from warehouse_stock_batch where tenant_id = 1 and item_id = 1 and batch_no = ?",
        BigDecimal.class,
        batchNo
    );
  }

  private int todoActionCount(String todoId, String actionType) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from todo_action where todo_id = ? and action_type = ? and status = 'DONE'",
        Integer.class,
        todoId,
        actionType
    );
    return count == null ? 0 : count;
  }

  private int operationLogCount(String action, String targetId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = ? and target_id = ?",
        Integer.class,
        action,
        targetId
    );
    return count == null ? 0 : count;
  }

  private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("FORBIDDEN");
          assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        });
  }

  private WarehouseItemRequest itemRequest(String code, String name) {
    return new WarehouseItemRequest(
        null,
        code,
        name,
        1L,
        "奶制品",
        null,
        "件",
        "件",
        "件",
        "件",
        null,
        "1箱/件",
        null,
        new BigDecimal("10"),
        30,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0,
        0,
        BigDecimal.ZERO,
        true,
        3,
        null,
        593,
        null,
        true,
        List.of()
    );
  }

  private AuthUser warehouseManager() {
    return new AuthUser(2L, 1L, "default", "warehouse", "", "仓库管理员", "WAREHOUSE", null, true);
  }

  private AuthUser boss() {
    return new AuthUser(5L, 1L, "default", "boss", "", "老板", "BOSS", null, true);
  }

  private AuthUser storeManager() {
    return new AuthUser(3L, 1L, "default", "rg1", "", "店长", "STORE_MANAGER", "rg1", true);
  }

  private AuthUser otherStoreManager() {
    return new AuthUser(4L, 1L, "default", "other-store", "", "其他门店店长", "STORE_MANAGER", "other-store", true);
  }

  private void createSchema() {
    jdbcTemplate.execute("""
        create table auth_user (
          id bigint primary key,
          tenant_id bigint not null,
          display_name varchar(120)
        )
        """);
    jdbcTemplate.execute("""
        create table store_branch (
          id varchar(64) primary key,
          tenant_id bigint not null,
          name varchar(160) not null
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_item_category (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          name varchar(120) not null,
          parent_id bigint null,
          sort_order int not null default 0,
          enabled tinyint(1) not null default 1,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp null
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_request_dedup (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          request_type varchar(60) not null,
          request_key varchar(80) not null,
          business_id varchar(120),
          created_at timestamp default current_timestamp,
          unique(tenant_id, request_type, request_key)
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_item (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          code varchar(80) not null,
          name varchar(160) not null,
          category_id bigint,
          category varchar(80),
          image_url varchar(500),
          unit varchar(40) not null default '件',
          purchase_unit varchar(40),
          stock_unit varchar(40),
          ingredient_unit varchar(40),
          unit_conversion_text varchar(160),
          spec varchar(160),
          warehouse_location varchar(120),
          unit_price decimal(14,2) not null default 0,
          shelf_life_days int,
          cups_per_unit decimal(14,2) not null default 0,
          daily_usage_estimate decimal(14,2) not null default 0,
          min_stock_days int not null default 7,
          max_stock_days int not null default 60,
          min_stock_quantity decimal(14,2) not null default 0,
          alert_enabled tinyint(1) not null default 1,
          expiry_alert_days int null default 3,
          item_description text,
          sort_order int not null default 593,
          item_attributes varchar(255),
          active tinyint(1) not null default 1,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp null,
          unique key uk_item_code (tenant_id, code)
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_item_department (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          item_id bigint not null,
          department_name varchar(120) not null,
          department_code varchar(80),
          department_group varchar(120),
          purchase_method varchar(120),
          supplier_name varchar(160),
          created_at timestamp not null default current_timestamp,
          updated_at timestamp null
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_stock_batch (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          item_id bigint not null,
          batch_no varchar(120) not null,
          received_date date not null,
          expiry_date date null,
          quantity decimal(14,2) not null default 0,
          unit_cost decimal(14,2) not null default 0,
          note text null,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp null,
          unique key uk_batch (tenant_id, item_id, batch_no)
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_stock_movement (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          item_id bigint not null,
          batch_id bigint null,
          movement_type varchar(40) not null,
          quantity_delta decimal(14,2) not null,
          source_type varchar(60),
          source_id varchar(120),
          store_id varchar(64),
          note text,
          operator_id bigint,
          created_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table store_requisition (
          id varchar(120) primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          status varchar(40) not null default 'SUBMITTED',
          total_amount decimal(14,2) not null default 0,
          note text,
          received_note text,
          submitted_by bigint,
          reviewed_by bigint,
          shipped_by bigint,
          received_by bigint,
          submitted_at timestamp not null default current_timestamp,
          reviewed_at timestamp null,
          shipped_at timestamp null,
          received_at timestamp null,
          updated_at timestamp null
        )
        """);
    jdbcTemplate.execute("""
        create table store_requisition_line (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          requisition_id varchar(120) not null,
          item_id bigint not null,
          requested_quantity decimal(14,2) not null default 0,
          approved_quantity decimal(14,2) null,
          shipped_quantity decimal(14,2) not null default 0,
          unit_price decimal(14,2) not null default 0,
          amount decimal(14,2) not null default 0,
          warning_text varchar(255),
          note text
        )
        """);
    jdbcTemplate.execute("create table warehouse_supplier(id bigint auto_increment primary key, tenant_id bigint, name varchar(160), contact_name varchar(80), phone varchar(80), settlement_cycle varchar(80), active tinyint(1))");
    jdbcTemplate.execute("create table warehouse_purchase_order(id varchar(120) primary key, tenant_id bigint, supplier_id bigint, status varchar(40), total_amount decimal(14,2), note text, created_by bigint, received_by bigint, created_at timestamp default current_timestamp, received_at timestamp null)");
    jdbcTemplate.execute("create table warehouse_purchase_order_line(id bigint auto_increment primary key, tenant_id bigint, purchase_order_id varchar(120), item_id bigint, ordered_quantity decimal(14,2), received_quantity decimal(14,2), unit_cost decimal(14,2), amount decimal(14,2), note text)");
    jdbcTemplate.execute("create table warehouse_delivery_order(id varchar(120) primary key, tenant_id bigint, requisition_id varchar(120), store_id varchar(64), status varchar(40), shipped_by bigint, received_by bigint, shipped_at timestamp default current_timestamp, received_at timestamp null, note text)");
    jdbcTemplate.execute("create table warehouse_delivery_order_line(id bigint auto_increment primary key, tenant_id bigint, delivery_id varchar(120), requisition_line_id bigint, item_id bigint, shipped_quantity decimal(14,2), received_quantity decimal(14,2), unit_price decimal(14,2), amount decimal(14,2), note text)");
    jdbcTemplate.execute("create table store_receipt(id varchar(120) primary key, tenant_id bigint, delivery_id varchar(120), requisition_id varchar(120), store_id varchar(64), status varchar(40), received_by bigint, received_at timestamp default current_timestamp, note text)");
    jdbcTemplate.execute("create table store_receipt_line(id bigint auto_increment primary key, tenant_id bigint, receipt_id varchar(120), item_id bigint, received_quantity decimal(14,2), note text)");
    jdbcTemplate.execute("create table store_inventory(id bigint auto_increment primary key, tenant_id bigint not null, store_id varchar(64) not null, item_id bigint not null, quantity decimal(14,2) not null default 0, unit varchar(40), updated_at timestamp default current_timestamp, unique key uk_store_inventory_item(tenant_id, store_id, item_id))");
    jdbcTemplate.execute("create table store_inventory_movement(id bigint auto_increment primary key, tenant_id bigint not null, store_id varchar(64) not null, item_id bigint not null, quantity_delta decimal(14,2) not null, movement_type varchar(40) not null, source_type varchar(60), source_id varchar(120), note text, created_by bigint, created_at timestamp default current_timestamp)");
    jdbcTemplate.execute("""
        create table warehouse_return_order (
          id varchar(120) primary key,
          tenant_id bigint not null,
          return_no varchar(120) not null,
          source_requisition_id varchar(120),
          source_delivery_id varchar(120),
          return_store_id varchar(64) not null,
          return_store_name varchar(160) not null,
          receive_department varchar(120) not null,
          status varchar(40) not null,
          total_amount decimal(14,2) not null default 0,
          handled_by varchar(500),
          created_by varchar(120),
          updated_by varchar(120),
          reviewed_by varchar(120),
          checked_by varchar(120),
          reason text,
          note text,
          review_note text,
          received_note text,
          return_date date not null,
          reviewed_at timestamp null,
          received_at timestamp null,
          created_at timestamp default current_timestamp,
          updated_at timestamp null
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_return_order_line (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          return_order_id varchar(120) not null,
          source_requisition_line_id bigint,
          item_id bigint not null,
          item_name varchar(160) not null,
          spec varchar(160),
          batch_id bigint,
          batch_no varchar(120),
          quantity decimal(14,2) not null default 0,
          unit varchar(40) not null,
          unit_price decimal(14,2) not null default 0,
          return_price decimal(14,2) not null default 0,
          amount decimal(14,2) not null default 0,
          reason text,
          note text
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_attachment (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          business_type varchar(80) not null,
          business_id varchar(120) not null,
          file_name varchar(255) not null,
          content_type varchar(120),
          file_size bigint not null default 0,
          storage_path varchar(500),
          content blob,
          uploaded_by bigint,
          uploaded_at timestamp default current_timestamp
        )
        """);
    jdbcTemplate.execute("create table operation_log(id bigint auto_increment primary key, tenant_id bigint, operator_id bigint, operator_name varchar(120), action varchar(80), target_type varchar(80), target_id varchar(120), store_id varchar(64), reason varchar(255), created_at timestamp default current_timestamp)");
    jdbcTemplate.execute("create table todo_action(id varchar(120) primary key, tenant_id bigint not null, todo_id varchar(160) not null, action_type varchar(40) not null, status varchar(40) not null, note text not null, actor_user_id bigint null, actor_name varchar(120) null, actor_role varchar(40) not null, created_at timestamp default current_timestamp)");
  }

  private void seedData() {
    jdbcTemplate.update("insert into auth_user(id, tenant_id, display_name) values (2, 1, '仓库管理员'), (3, 1, '店长'), (4, 1, '其他门店店长')");
    jdbcTemplate.update("insert into store_branch(id, tenant_id, name) values ('rg1', 1, '日广店'), ('other-store', 1, '其他门店')");
    jdbcTemplate.update("insert into warehouse_item_category(id, tenant_id, name, parent_id, sort_order, enabled) values (1, 1, '奶制品', null, 10, 1)");
    jdbcTemplate.update("""
        insert into warehouse_item(
          id, tenant_id, code, name, category_id, category, unit, purchase_unit, stock_unit, ingredient_unit, spec, unit_price, shelf_life_days,
          cups_per_unit, daily_usage_estimate, min_stock_days, max_stock_days,
          min_stock_quantity, alert_enabled, expiry_alert_days, active
        ) values (1, 1, 'MILK', '鲜奶', 1, '奶制品', '件', '件', '件', '件', '12盒/件', 88.00, 15, 0, 0, 0, 0, 20.00, 1, 3, 1)
        """);
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(tenant_id, item_id, batch_no, received_date, expiry_date, quantity, unit_cost, note)
        values (1, 1, 'B001', '2026-07-08', '2026-08-01', 30.00, 80.00, '初始库存')
        """);
  }
}
