package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WarehousePrintServiceTest {
  private JdbcTemplate jdbcTemplate;
  private WarehousePrintService service;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbcTemplate = new JdbcTemplate(dataSource);
    createSchema();
    seedData();
    service = new WarehousePrintService(new WarehouseRepository(jdbcTemplate), new WarehousePdfRenderer());
  }

  @Test
  void receiptPdfUsesMysqlBatchDataAndWritesDownloadLog() throws Exception {
    WarehousePrintDocument document = service.receiptPdf(warehouseManager(), 1L);

    assertThat(document.filename()).isEqualTo("入库单-RKD260708000000001.pdf");
    assertPdfBytes(document.bytes());
    assertThat(pdfText(document.bytes()).replaceAll("\\s+", ""))
        .contains("单据号：RKD260708000000001");
    assertThat(operationLogCount("下载入库单", "batch-1")).isEqualTo(1);
  }

  @Test
  void inboundMovementPdfUsesCompactReceiptTemplateAndKeepsDownloadContract() throws Exception {
    WarehousePrintDocument document = service.movementPdf(warehouseManager(), 1L);
    String text = pdfText(document.bytes());

    assertThat(document.filename()).isEqualTo("入库单-RKD260708000000001.pdf");
    assertThat(text).contains(
        "入库单", "单据号", "日期", "部门", "供应商名称", "金额", "备注",
        "序号", "物品名称", "内部编号", "规格", "数量", "单位", "单价", "小计",
        "鲜奶", "MILK", "状态：已核对"
    );
    assertThat(text).doesNotContain(
        "AI Profit OS 仓库入库单", "商品明细", "说明：", "签字区",
        "仓库经办人", "复核人"
    );
    assertThat(operationLogCount("下载入库单", "movement-1")).isEqualTo(1);
  }

  @Test
  void deliveryPdfUsesWholeRequisitionAndWritesDownloadLog() throws Exception {
    WarehousePrintDocument document = service.deliveryPdf(warehouseManager(), "REQ1");

    assertThat(document.filename()).isEqualTo("配送单-PSD260708000000001.pdf");
    assertPdfBytes(document.bytes());
    assertThat(pdfText(document.bytes()).replaceAll("\\s+", ""))
        .contains("单据号：PSD260708000000001")
        .doesNotContain("单据号：REQ1", "单据号：DO1");
    assertThat(operationLogCount("下载出库单", "REQ1")).isEqualTo(1);
  }

  @Test
  void returnPdfUsesMysqlReturnOrderAndWritesDownloadLog() throws Exception {
    WarehousePrintDocument document = service.returnPdf(warehouseManager(), "PSTH1");

    assertThat(document.filename()).isEqualTo("配送退货单-PSTH260708000000001.pdf");
    assertPdfBytes(document.bytes());
    assertThat(pdfText(document.bytes()).replaceAll("\\s+", ""))
        .contains("单据号：PSTH260708000000001");
    assertThat(operationLogCount("下载配送退货单", "PSTH1")).isEqualTo(1);
  }

  @Test
  void returnPdfKeepsReceivingWarehouseSnapshotAfterFacilityRename() throws Exception {
    jdbcTemplate.update("update warehouse_facility set name = '荆州运营总仓' where id = 1");

    WarehouseReturnResponse historical = new WarehouseRepository(jdbcTemplate)
        .returnOrder(1L, "PSTH1")
        .orElseThrow();
    WarehousePrintDocument document = service.returnPdf(warehouseManager(), "PSTH1");

    assertThat(historical.receiveWarehouseName()).isEqualTo("荆州总仓");
    assertThat(historical.receiveWarehouseName()).isNotEqualTo("荆州运营总仓");
    assertPdfBytes(document.bytes());
    assertThat(operationLogCount("下载配送退货单", "PSTH1")).isEqualTo(1);
  }

  @Test
  void storeManagerCanOnlyDownloadOwnStoreDelivery() {
    WarehousePrintDocument ownStore = service.deliveryPdf(storeManager(), "REQ1");
    assertThat(ownStore.filename()).contains("配送单");

    assertThatThrownBy(() -> service.deliveryPdf(storeManager(), "REQ-OTHER"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> service.receiptPdf(storeManager(), 1L))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void storeManagerCanOnlyDownloadOwnStoreReturnOrder() {
    WarehousePrintDocument ownStore = service.returnPdf(storeManager(), "PSTH1");
    assertThat(ownStore.filename()).contains("配送退货单");

    assertThatThrownBy(() -> service.returnPdf(storeManager(), "PSTH-OTHER"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  private void assertPdfBytes(byte[] bytes) {
    assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    assertThat(bytes.length).isGreaterThan(10_000);
  }

  private String pdfText(byte[] bytes) throws Exception {
    try (PDDocument document = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(document);
    }
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

  private AuthUser warehouseManager() {
    return new AuthUser(2L, 1L, "default", "warehouse", "", "仓库管理员", "WAREHOUSE", null, true);
  }

  private AuthUser storeManager() {
    return new AuthUser(3L, 1L, "default", "rg1", "", "店长", "STORE_MANAGER", "rg1", true);
  }

  private void createSchema() {
    jdbcTemplate.execute("create table auth_user(id bigint primary key, tenant_id bigint not null, display_name varchar(120))");
    jdbcTemplate.execute("create table store_branch(id varchar(64) primary key, tenant_id bigint not null, name varchar(160) not null, area varchar(160), manager varchar(120))");
    jdbcTemplate.execute("""
        create table warehouse_facility (
          id bigint primary key,
          tenant_id bigint not null,
          code varchar(64) not null,
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
          updated_at timestamp null
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
          updated_at timestamp null
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
    jdbcTemplate.execute("""
        create table warehouse_return_order (
          id varchar(120) primary key,
          tenant_id bigint not null,
          warehouse_id bigint not null,
          receive_warehouse_code_snapshot varchar(64) not null,
          receive_warehouse_name_snapshot varchar(160) not null,
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
  }

  private void seedData() {
    jdbcTemplate.update("insert into auth_user(id, tenant_id, display_name) values (2, 1, '仓库管理员'), (3, 1, '店长')");
    jdbcTemplate.update("insert into store_branch(id, tenant_id, name, area, manager) values ('rg1', 1, '日广店', '日广路1号', '张店长'), ('other-store', 1, '其他门店', '其他地址', '其他店长')");
    jdbcTemplate.update("""
        insert into warehouse_facility(id, tenant_id, code, name)
        values (1, 1, 'JZ-CENTRAL', '荆州总仓')
        """);
    jdbcTemplate.update("insert into warehouse_item_category(id, tenant_id, name, parent_id, sort_order, enabled) values (1, 1, '奶制品', null, 10, 1), (2, 1, '包装', null, 20, 1)");
    jdbcTemplate.update("""
        insert into warehouse_item(id, tenant_id, code, name, category_id, category, unit, purchase_unit, stock_unit, ingredient_unit, spec, unit_price, shelf_life_days, active)
        values
          (1, 1, 'MILK', '鲜奶', 1, '奶制品', '件', '件', '件', '件', '12盒/件', 88.00, 15, 1),
          (2, 1, 'CUP-700', '700ml杯子', 2, '包装', '件', '件', '件', '件', '1000个/件', 20.00, 365, 1)
        """);
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(id, tenant_id, item_id, batch_no, received_date, expiry_date, quantity, unit_cost, note)
        values
          (1, 1, 1, 'B001', '2026-07-08', '2026-08-01', 30.00, 80.00, '供应商A'),
          (2, 1, 2, 'C001', '2026-07-08', null, 100.00, 15.00, '杯子采购')
        """);
    jdbcTemplate.update("""
        insert into warehouse_stock_movement(id, tenant_id, item_id, batch_id, movement_type, quantity_delta, source_type, source_id, store_id, note, operator_id, created_at)
        values
          (1, 1, 1, 1, 'IN', 30.00, 'MANUAL_RECEIVE', 'B001', null, '供应商A', 2, '2026-07-08 15:50:00')
        """);
    jdbcTemplate.update("""
        insert into store_requisition(id, tenant_id, store_id, status, total_amount, submitted_by, reviewed_by, shipped_by, submitted_at, reviewed_at, shipped_at)
        values
          ('REQ1', 1, 'rg1', 'SHIPPED', 128.00, 3, 2, 2, '2026-07-08 15:30:00', '2026-07-08 15:35:00', '2026-07-08 15:42:00'),
          ('REQ-OTHER', 1, 'other-store', 'SHIPPED', 88.00, 3, 2, 2, '2026-07-08 15:30:00', '2026-07-08 15:35:00', '2026-07-08 15:42:00')
        """);
    jdbcTemplate.update("""
        insert into store_requisition_line(id, tenant_id, requisition_id, item_id, requested_quantity, approved_quantity, shipped_quantity, unit_price, amount, note)
        values
          (1, 1, 'REQ1', 1, 1.00, 1.00, 1.00, 88.00, 88.00, '门店叫货配货'),
          (2, 1, 'REQ1', 2, 2.00, 2.00, 2.00, 20.00, 40.00, '门店叫货配货'),
          (3, 1, 'REQ-OTHER', 1, 1.00, 1.00, 1.00, 88.00, 88.00, '其他门店')
        """);
    jdbcTemplate.update("insert into warehouse_delivery_order(id, tenant_id, requisition_id, store_id, status, shipped_by, shipped_at, note) values ('DO1', 1, 'REQ1', 'rg1', 'SHIPPED', 2, '2026-07-08 15:42:00', '按叫货单配货出库')");
    jdbcTemplate.update("""
        insert into warehouse_delivery_order_line(id, tenant_id, delivery_id, requisition_line_id, item_id, shipped_quantity, unit_price, amount)
        values
          (1, 1, 'DO1', 1, 1, 1.00, 88.00, 88.00),
          (2, 1, 'DO1', 2, 2, 2.00, 20.00, 40.00)
        """);
    jdbcTemplate.update("""
        insert into warehouse_stock_movement(id, tenant_id, item_id, batch_id, movement_type, quantity_delta, source_type, source_id, store_id, note, operator_id, created_at)
        values
          (2, 1, 1, 1, 'OUT', -1.00, 'REQUISITION', 'REQ1', 'rg1', '门店叫货配货', 2, '2026-07-08 15:42:00'),
          (3, 1, 2, 2, 'OUT', -2.00, 'REQUISITION', 'REQ1', 'rg1', '门店叫货配货', 2, '2026-07-08 15:42:00')
        """);
    jdbcTemplate.update("""
        insert into warehouse_return_order(
          id, tenant_id, warehouse_id, receive_warehouse_code_snapshot,
          receive_warehouse_name_snapshot, return_no, return_store_id, return_store_name,
          receive_department,
          status, total_amount, handled_by, created_by, updated_by, reviewed_by, checked_by, note, return_date, created_at
        )
        values
          ('PSTH1', 1, 1, 'JZ-CENTRAL', '荆州总仓', 'PSTH1', 'rg1', '日广店', '荆州总仓', 'CHECKED', 25.00,
           '创:仓库管理员,改:仓库管理员,审:仓库管理员,核对:仓库管理员',
           '仓库管理员', '仓库管理员', '仓库管理员', '仓库管理员', '门店退回采购', '2026-07-08', '2026-07-08 16:10:00'),
          ('PSTH-OTHER', 1, 1, 'JZ-CENTRAL', '荆州总仓', 'PSTH-OTHER', 'other-store', '其他门店', '荆州总仓', 'CHECKED', 10.00,
           '创:仓库管理员,改:仓库管理员,审:仓库管理员,核对:仓库管理员',
           '仓库管理员', '仓库管理员', '仓库管理员', '仓库管理员', '其他门店退货', '2026-07-08', '2026-07-08 16:10:00')
        """);
    jdbcTemplate.update("""
        insert into warehouse_return_order_line(
          tenant_id, return_order_id, item_id, item_name, spec, quantity, unit, unit_price, return_price, amount, note
        )
        values
          (1, 'PSTH1', 1, '鲜奶', '12盒/件', 1.00, '件', 88.00, 25.00, 25.00, '门店退货'),
          (1, 'PSTH-OTHER', 2, '700ml杯子', '1000个/件', 1.00, '件', 20.00, 10.00, 10.00, '其他门店')
        """);
  }
}
