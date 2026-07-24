package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintHeader;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintLine;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseMovementPrintRow;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseReceiptPrintRow;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class WarehousePdfRendererTest {
  private final WarehousePdfRenderer renderer = new WarehousePdfRenderer();

  @Test
  void receiptPdfContainsChineseTextAndEmbeddedFont() throws IOException {
    WarehouseReceiptPrintRow row = new WarehouseReceiptPrintRow(
        81L, 1L, "05001", "茉莉绿茶", "250g/包", "包",
        "INITIAL-EXCEL-20260723", "2026-07-23", null,
        new BigDecimal("15"), new BigDecimal("2100"),
        realDatabaseMojibake(), "仓库管理员", "2026-07-23 06:17:00"
    );

    byte[] pdf = renderer.receipt(row);
    writePdfArtifact(pdf, "warehouse.receipt.pdf.output");
    String text = assertChinesePdf(pdf);
    String compactText = text.replaceAll("\\s+", "");

    assertThat(text).contains(
        "入库单", "单据号", "日期", "部门", "供应商名称", "金额", "备注",
        "序号", "物品名称", "内部编号", "规格", "数量", "单位", "单价", "小计",
        "茉莉绿茶", "250g/包", "采购", "自购", "真实库存 Excel 同步", "状态：已核对"
    );
    assertThat(compactText).contains("序号物品名称内部编号规格数量单位单价小计备注");
    assertThat(compactText).contains("单据号：RKD260723000000081");
    assertThat(text).doesNotContain(
        "AI Profit OS 仓库入库单", "商品明细", "说明：", "签字区",
        "仓库经办人", "复核人", "LOGO"
    );
    assertThat(text).doesNotContain(realDatabaseMojibake());
    assertCompactHorizontalSlip(pdf, "receipt");
  }

  @Test
  void deliveryPdfContainsChineseTextAndReferenceColumns() throws IOException {
    WarehouseDeliveryPrintHeader header = new WarehouseDeliveryPrintHeader(
        "REQ-1", "store-1", "荆州之星", "RECEIVED",
        "张三", "13800000000", "荆州市沙市区一号店",
        "PSD260708242206633", "2026-07-08 15:42:00",
        "按叫货单配货", "仓库管理员"
    );
    List<WarehouseDeliveryPrintLine> lines = List.of(
        deliveryLine(1L, "标签纸", "1份/5卷", "份", "1", "35"),
        deliveryLine(2L, "一次性手套", "M码白色", "盒", "1", "35"),
        deliveryLine(3L, "原味晶球", null, "件", "1", "100"),
        deliveryLine(4L, "一次性手套M", "黑色", "包", "1", "35"),
        deliveryLine(5L, "细吸管", "按包叫货", "件", "2", "5.5"),
        deliveryLine(6L, "700注塑杯", null, "件", "1", "230"),
        deliveryLine(7L, "水果叉子", null, "包", "4", "13"),
        deliveryLine(8L, "注塑杯盖", "1000个/件", "件", "1", "115"),
        deliveryLine(9L, "水晶冻粉", "包", "件", "1", "20"),
        deliveryLine(10L, "咖啡奶", "瓶", "瓶", "1", "25"),
        deliveryLine(11L, "安心贴", "1卷/500个", "卷", "1", "20"),
        deliveryLine(12L, "单杯打包袋", "5扎/份", "份", "1", "60"),
        deliveryLine(13L, "新款杯套", "不含刮纸", "件", "1", "50"),
        deliveryLine(14L, "咖色抹布", null, "个", "6", "4.5")
    );

    byte[] pdf = renderer.delivery(header, lines);
    writePdfArtifact(pdf, "warehouse.delivery.pdf.output");
    String text = assertChinesePdf(pdf);

    assertThat(text).contains(
        "配送单", "单据号", "日期", "状态", "配出部门", "配入部门", "总金额",
        "序号", "物品名称", "数量", "单位", "单价", "小计",
        "标签纸", "1份/5卷", "咖色抹布", "815",
        "收货人", "联系电话", "收货地址", "第1页/共1页"
    );
    assertThat(text.replaceAll("\\s+", ""))
        .contains("单据号：PSD260708242206633")
        .doesNotContain("REQ-1", "DEL-1");
    assertThat(text).doesNotContain("批次号");
  }

  private WarehouseDeliveryPrintLine deliveryLine(
      long itemId,
      String name,
      String spec,
      String unit,
      String quantity,
      String unitPrice
  ) {
    BigDecimal quantityValue = new BigDecimal(quantity);
    BigDecimal priceValue = new BigDecimal(unitPrice);
    return new WarehouseDeliveryPrintLine(
        itemId,
        name,
        spec,
        unit,
        quantityValue,
        priceValue,
        quantityValue.multiply(priceValue),
        "BATCH-" + itemId,
        ""
    );
  }

  @Test
  void inboundMovementUsesTheSameCompactReceiptTemplate() throws IOException {
    WarehouseMovementPrintRow row = new WarehouseMovementPrintRow(
        82L, 1L, "PL0066", 81L, "单杯保温袋（500个/件）", "件", "件",
        "IN", new BigDecimal("120"), "PURCHASE_ORDER", "PO-1",
        null, null, "参考图备注", "仓库管理员", "2026-07-07 10:00:00",
        "BATCH-1", null, new BigDecimal("225")
    );

    byte[] pdf = renderer.movement(row);
    writePdfArtifact(pdf, "warehouse.movement.receipt.pdf.output");
    String text = assertChinesePdf(pdf);
    String compactText = text.replaceAll("\\s+", "");

    assertThat(text).contains(
        "入库单", "单据号", "日期", "部门", "供应商名称", "金额", "备注",
        "单杯保温袋（500个/件）", "PL0066", "120", "225", "27000", "状态：已核对"
    );
    assertThat(compactText).contains("序号物品名称内部编号规格数量单位单价小计备注");
    assertThat(compactText).contains("单据号：RKD260707000000082");
    assertThat(text).doesNotContain(
        "AI Profit OS 仓库入库单", "商品明细", "说明：", "签字区",
        "仓库经办人", "复核人", "LOGO"
    );
    assertCompactHorizontalSlip(pdf, "receipt");
  }

  @Test
  void returnPdfUsesReceivingWarehouseSnapshotAndReferenceColumns() throws IOException {
    byte[] pdf = renderer.returnOrder(returnOrder("荆州总仓", "伪造页面收货部门"));
    writePdfArtifact(pdf, "warehouse.return.pdf.output");
    String text = assertChinesePdf(pdf);

    assertThat(text).contains(
        "配送退货单", "退货部门：新天地店", "收货部门：荆州总仓", "经手人：创:荆州仓库",
        "单据号", "日期", "序号", "物品名称", "规格", "数量", "单位",
        "单价", "退货价", "小计", "凤爪（小胡鸭）"
    );
    assertThat(text.replaceAll("\\s+", ""))
        .contains("单据号：PSTH260707882937764")
        .doesNotContain("return-pdf-test", "REQ-1", "DEL-1");
    assertThat(text).doesNotContain("伪造页面收货部门", "收货仓");
    assertCompactHorizontalSlip(pdf, "return slip");
  }

  private WarehouseReturnResponse returnOrder(String receiveWarehouseName, String receiveDepartment) {
    WarehouseReturnLineResponse line = new WarehouseReturnLineResponse(
        1L, 1L, "凤爪（小胡鸭）", "袋装", 1L, "B001", 1L,
        new BigDecimal("10"), "袋", new BigDecimal("2.50"),
        new BigDecimal("2.50"), new BigDecimal("25.00"), "门店退货", ""
    );
    return new WarehouseReturnResponse(
        "return-pdf-test", "PSTH260707882937764", "REQ-1", "DEL-1",
        "store-1", "新天地店", 1L, receiveWarehouseName, receiveDepartment,
        "RECEIVED", "已收货", new BigDecimal("25.00"),
        "创:荆州仓库,改:荆州仓库,审:荆州仓库,核对:荆州仓库", "创建人", "更新人",
        "审核人", "复核人", "门店退货", "", null, null, "2026-07-07", null, null,
        "2026-07-07 10:00:00", "2026-07-07 10:00:00", 1, 0, List.of(line)
    );
  }

  private String assertChinesePdf(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      String text = new PDFTextStripper().getText(document);
      boolean hasEmbeddedFont = false;
      for (PDPage page : document.getPages()) {
        for (COSName fontName : page.getResources().getFontNames()) {
          PDFont font = page.getResources().getFont(fontName);
          if (font != null && font.isEmbedded()) {
            hasEmbeddedFont = true;
          }
        }
      }
      assertThat(hasEmbeddedFont).as("PDF must embed a Chinese-capable font").isTrue();
      assertThat(text).doesNotContain("\uFFFD", "□", "Ã", "Â", "â€", "ï¿½");
      return text;
    }
  }

  private void assertCompactHorizontalSlip(byte[] pdf, String description) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      PDPage page = document.getPage(0);
      assertThat(page.getMediaBox().getWidth()).isGreaterThan(page.getMediaBox().getHeight());
      assertThat(page.getMediaBox().getWidth() / page.getMediaBox().getHeight())
          .as("%s should use the compact horizontal ratio from the reference", description)
          .isGreaterThan(3f);
    }
  }

  private void writePdfArtifact(byte[] pdf, String propertyName) throws IOException {
    String outputPath = System.getProperty(propertyName);
    if (outputPath == null || outputPath.isBlank()) {
      return;
    }
    Path target = Path.of(outputPath);
    if (target.getParent() != null) {
      Files.createDirectories(target.getParent());
    }
    Files.write(target, pdf);
  }

  private String realDatabaseMojibake() {
    return "\u00e7\u0153\u0178\u00e5\u00ae\u017e\u00e5\u00ba\u201c"
        + "\u00e5\u00ad\u02dc Excel \u00e5\u0090\u0152\u00e6\u00ad\u00a5";
  }
}
