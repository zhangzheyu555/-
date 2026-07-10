package com.storeprofit.system.staticresources;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class Warehouse2StaticContractTest {
  @Test
  void migrationDefinesWarehouse2BusinessTablesAndKeepsMvpTables() throws IOException {
    String warehouseMvp = Files.readString(
        Path.of("src", "main", "resources", "db", "migration", "V4__warehouse_mvp.sql"),
        StandardCharsets.UTF_8);
    String warehouse2 = Files.readString(
        Path.of("src", "main", "resources", "db", "migration", "V7__warehouse_2_0.sql"),
        StandardCharsets.UTF_8);
    String warehouseReturns = Files.readString(
        Path.of("src", "main", "resources", "db", "migration", "V9__warehouse_returns.sql"),
        StandardCharsets.UTF_8);
    String itemCatalog = Files.readString(
        Path.of("src", "main", "resources", "db", "migration", "V10__warehouse_item_catalog.sql"),
        StandardCharsets.UTF_8);

    assertThat(warehouseMvp)
        .contains("warehouse_item")
        .contains("warehouse_stock_batch")
        .contains("warehouse_stock_movement")
        .contains("store_requisition")
        .contains("store_requisition_line");

    assertThat(warehouse2)
        .contains("warehouse_supplier")
        .contains("warehouse_purchase_order")
        .contains("warehouse_purchase_order_line")
        .contains("warehouse_delivery_order")
        .contains("warehouse_delivery_order_line")
        .contains("store_receipt")
        .contains("store_receipt_line")
        .contains("warehouse_stock_adjustment")
        .contains("warehouse_attachment")
        .contains("warehouse_alert")
        .contains("received_by")
        .contains("received_at");

    assertThat(warehouseReturns)
        .contains("warehouse_return_order")
        .contains("warehouse_return_order_line")
        .contains("return_no")
        .contains("return_store_id")
        .contains("receive_department")
        .contains("return_price");

    assertThat(itemCatalog)
        .contains("warehouse_item_category")
        .contains("warehouse_item_department")
        .contains("category_id")
        .contains("image_url")
        .contains("purchase_unit")
        .contains("stock_unit")
        .contains("ingredient_unit")
        .contains("warehouse_location")
        .contains("item_description")
        .contains("器材1")
        .contains("抹布+工作服")
        .contains("奶制品");
  }

  @Test
  void migrationDefinesMinimumStockAlertFields() throws IOException {
    String warehouseMvp = Files.readString(
        Path.of("src", "main", "resources", "db", "migration", "V4__warehouse_mvp.sql"),
        StandardCharsets.UTF_8);
    String safeStock = Files.readString(
        Path.of("src", "main", "resources", "db", "migration", "V8__warehouse_safe_stock_alerts.sql"),
        StandardCharsets.UTF_8);

    assertThat(warehouseMvp).contains("warehouse_item");
    assertThat(safeStock)
        .contains("min_stock_quantity")
        .contains("alert_enabled")
        .contains("expiry_alert_days")
        .contains("最低安全库存")
        .contains("daily_usage_estimate * min_stock_days");
  }

  @Test
  void backendDefinesWarehouse2ClosureEndpointsAndChineseStatusMapping() throws IOException {
    String controller = Files.readString(
        Path.of("src", "main", "java", "com", "storeprofit", "system", "warehouse", "WarehouseController.java"),
        StandardCharsets.UTF_8);
    String repository = Files.readString(
        Path.of("src", "main", "java", "com", "storeprofit", "system", "warehouse", "WarehouseRepository.java"),
        StandardCharsets.UTF_8);
    String service = Files.readString(
        Path.of("src", "main", "java", "com", "storeprofit", "system", "warehouse", "WarehouseService.java"),
        StandardCharsets.UTF_8);
    String todoRepository = Files.readString(
        Path.of("src", "main", "java", "com", "storeprofit", "system", "todo", "RoleTodoRepository.java"),
        StandardCharsets.UTF_8);
    String todoService = Files.readString(
        Path.of("src", "main", "java", "com", "storeprofit", "system", "todo", "RoleTodoService.java"),
        StandardCharsets.UTF_8);

    assertThat(controller)
        .contains("@PostMapping(\"/requisitions/{id}/receive\")")
        .contains("receiveByStore")
        .contains("@GetMapping(\"/item-categories\")")
        .contains("@PostMapping(\"/item-categories\")")
        .contains("@PostMapping(\"/item-categories/{id}/enabled\")")
        .contains("@GetMapping(\"/items/{id}\")")
        .contains("@PostMapping(\"/items/{id}/enabled\")")
        .contains("@GetMapping(\"/movements\")")
        .contains("@PostMapping(\"/purchase-orders\")")
        .contains("/print/receipts/{batchId}")
        .contains("/print/requisitions/{requisitionId}/delivery")
        .contains("/print/movements/{movementId}")
        .contains("@PostMapping(\"/returns\")")
        .contains("@GetMapping(\"/returns\")")
        .contains("@GetMapping(\"/returns/{returnId}\")")
        .contains("/print/returns/{returnId}");

    String renderer = Files.readString(
        Path.of("src", "main", "java", "com", "storeprofit", "system", "warehouse", "WarehousePdfRenderer.java"),
        StandardCharsets.UTF_8);
    assertThat(renderer)
        .contains("入库单")
        .contains("单据号：")
        .contains("供应商名称")
        .contains("配送单")
        .contains("配送退货单")
        .contains("退货部门")
        .contains("收货部门")
        .contains("经手人")
        .contains("配出部门")
        .contains("配入部门")
        .contains("总金额")
        .contains("物品名称")
        .contains("单价")
        .contains("小计")
        .contains("备注：")
        .contains("第1页/共1页");

    assertThat(service)
        .contains("public void receiveByStore")
        .contains("itemCategories")
        .contains("saveItemCategory")
        .contains("setItemEnabled")
        .contains("replaceItemDepartments")
        .contains("requireWarehouseRead")
        .contains("requireStoreReceiver")
        .contains("createDeliveryForRequisition")
        .contains("createReturn")
        .contains("returnOrder")
        .contains("markReceived")
        .contains("门店确认收货");

    assertThat(repository)
        .contains("itemCategories")
        .contains("upsertItemCategory")
        .contains("setItemCategoryEnabled")
        .contains("setItemEnabled")
        .contains("warehouse_item_department")
        .contains("insertDelivery")
        .contains("insertDeliveryLine")
        .contains("insertReceipt")
        .contains("insertReceiptLine")
        .contains("markRequisitionReceived")
        .contains("case \"RECEIVED\" -> \"门店已收货\"")
        .contains("case \"SHIPPED\" -> \"待门店收货\"")
        .contains("insertReturnOrder")
        .contains("insertReturnOrderLine")
        .contains("warehouse_return_order");

    assertThat(todoRepository)
        .contains("warehouseStockAlerts")
        .contains("pendingWarehousePurchases")
        .contains("stockLossAdjustments")
        .contains("warehouse_purchase_order")
        .contains("warehouse_stock_adjustment")
        .contains("库存不足")
        .contains("临期风险");

    assertThat(todoService)
        .contains("warehouseStockAlertItem")
        .contains("warehousePurchaseItem")
        .contains("warehouseAdjustmentItem")
        .contains("采购未入库")
        .contains("盘亏异常待核对")
        .contains("库存预警");
  }

  @Test
  void frontendDefinesWarehouse2WorkbenchAndDoesNotUseBrowserStorageForWarehouse() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("仓库中心")
        .contains("function renderWarehouseStoreManager")
        .contains("function renderWarehouseManager")
        .contains("本店库存")
        .contains("向公司仓库叫货")
        .contains("公司仓库可配送")
        .contains("我的叫货单")
        .contains("确认收货")
        .contains("仓库总览")
        .contains("门店叫货待处理")
        .contains("物品类别")
        .contains("商品类别")
        .contains("商品档案")
        .contains("新建产品")
        .contains("库存管理")
        .contains("作用部门")
        .contains("上传图片")
        .contains("采购单位")
        .contains("库存单位")
        .contains("配料单位")
        .contains("物品说明")
        .contains("采购入库")
        .contains("预警设置")
        .contains("出库记录")
        .contains("最低安全库存")
        .doesNotContain("当前仓库库存")
        .contains("低于这个数量提醒仓库管理员")
        .contains("alertEnabled")
        .contains("minStockQuantity")
        .contains("expiryAlertDays")
        .contains("function warehouseSaveAlertSettings")
        .contains("/alert-settings")
        .contains("function warehouseReceiveByStore")
        .contains("/api/warehouse/requisitions/")
        .contains("/receive")
        .contains("function whStatusLabel")
        .contains("待门店收货")
        .contains("门店已收货")
        .contains("真实库存流水写入 MySQL")
        .contains("下载入库单")
        .contains("下载出库单")
        .contains("配送退货单")
        .contains("新建配送退货单")
        .contains("下载退货单")
        .contains("warehouseCreateReturn")
        .contains("warehouseSaveCategory")
        .contains("warehouseCategoryTreeHtml")
        .contains("warehouseSetItemEnabled")
        .contains("/api/warehouse/item-categories")
        .contains("/api/warehouse/items/")
        .contains("/enabled")
        .contains("/api/warehouse/returns")
        .contains("/api/warehouse/print/returns/")
        .contains("warehouseDownloadPrint")
        .contains("/api/warehouse/print/receipts/")
        .contains("/api/warehouse/print/requisitions/")
        .contains("/api/warehouse/print/movements/")
        .doesNotContain("日均用量")
        .doesNotContain("低库存预警天数")
        .doesNotContain("积压预警天数")
        .doesNotContain("每件可做杯数")
        .doesNotContain("localStorage.setItem(\"warehouse")
        .doesNotContain("localStorage.getItem(\"warehouse");
  }

  @Test
  void frontendStoreManagerWarehouseHasReadOnlyCategoryFilter() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);
    int storeManagerStart = indexHtml.indexOf("function renderWarehouseStoreManager(data)");
    int warehouseManagerStart = indexHtml.indexOf("function renderWarehouseManager(data)");

    assertThat(storeManagerStart).isGreaterThan(0);
    assertThat(warehouseManagerStart).isGreaterThan(storeManagerStart);

    String storeManager = indexHtml.substring(storeManagerStart, warehouseManagerStart);
    assertThat(indexHtml)
        .contains("STORE_WAREHOUSE_CATEGORY_FILTER")
        .contains("function storeWarehouseCategoryTreeHtml")
        .contains("function storeWarehouseSelectCategory")
        .contains("function storeWarehouseFilteredItems");

    assertThat(storeManager)
        .contains("商品分类")
        .contains("按类别查看本店库存和可叫货商品")
        .contains("storeWarehouseCategoryTreeHtml(data,allItems)")
        .contains("const items=storeWarehouseFilteredItems(allItems)")
        .contains("当前分类")
        .contains("商品选择跟随当前分类")
        .doesNotContain("新建产品")
        .doesNotContain("保存类别")
        .doesNotContain("编辑商品")
        .doesNotContain("停用商品")
        .doesNotContain("采购入库")
        .doesNotContain("预警设置");
  }
}
