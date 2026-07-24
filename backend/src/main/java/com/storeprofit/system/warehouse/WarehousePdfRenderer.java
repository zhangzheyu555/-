package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintHeader;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintLine;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseMovementPrintRow;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseReceiptPrintRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WarehousePdfRenderer {
  private static final int CANVAS_WIDTH = 1240;
  private static final int CANVAS_HEIGHT = 1754;
  private static final int RECEIPT_CANVAS_WIDTH = 1240;
  private static final int RECEIPT_CANVAS_HEIGHT = 375;
  private static final int RETURN_CANVAS_WIDTH = 1240;
  private static final int RETURN_CANVAS_HEIGHT = 403;
  private static final int DELIVERY_CANVAS_WIDTH = 1109;
  private static final int DELIVERY_CANVAS_HEIGHT = 691;
  private static final int MARGIN = 90;
  private static final float PDF_WIDTH = 595f;
  private static final float PDF_HEIGHT = 842f;
  private static final float RECEIPT_PDF_WIDTH = 877f;
  private static final float RECEIPT_PDF_HEIGHT = 265f;
  private static final float RETURN_PDF_WIDTH = 887f;
  private static final float RETURN_PDF_HEIGHT = 288f;
  private static final float DELIVERY_PDF_WIDTH = 1109f;
  private static final float DELIVERY_PDF_HEIGHT = 691f;
  private static final String FONT_RESOURCE = "/fonts/NotoSansSC-Regular.ttf";

  public byte[] receipt(WarehouseReceiptPrintRow row) {
    return renderReceipt(sheet -> sheet.receiptSlip(
        WarehouseDocumentNumbers.receipt(row.createdAt(), row.batchId()),
        dateOnly(display(row.receivedDate(), row.createdAt())),
        "采购",
        "自购",
        amount(row.receivedQuantity()).multiply(amount(row.unitCost())),
        display(row.note(), ""),
        List.of(List.of(
            display(row.itemName()),
            display(row.itemCode()),
            display(row.spec(), ""),
            qty(row.receivedQuantity()),
            display(row.unit(), "件"),
            money(row.unitCost()),
            money(amount(row.receivedQuantity()).multiply(amount(row.unitCost()))),
            display(row.note(), "")
        ))
    ));
  }

  public byte[] delivery(WarehouseDeliveryPrintHeader header, List<WarehouseDeliveryPrintLine> lines) {
    return renderDelivery(sheet -> sheet.deliverySlip(
        WarehouseDocumentNumbers.delivery(
            header.shippedAt(),
            header.deliveryId(),
            header.requisitionId()
        ),
        dateOnly(header.shippedAt()),
        deliveryStatusLabel(header.status()),
        "采购",
        display(header.storeName(), header.storeId()),
        deliveryTotal(lines),
        display(header.receiverName()),
        display(header.receiverPhone()),
        display(header.receiverAddress()),
        lines.stream()
            .map(line -> List.of(
                displayItemName(line.itemName(), line.spec()),
                qty(line.shippedQuantity()),
                display(line.unit()),
                money(line.unitPrice()),
                money(lineSubtotal(line))
            ))
            .toList()
    ));
  }

  public byte[] returnOrder(WarehouseReturnResponse order) {
    return renderReturn(sheet -> sheet.returnSlip(
        WarehouseDocumentNumbers.returnOrder(
            order.returnDate(),
            order.returnNo(),
            order.id()
        ),
        dateOnly(order.returnDate()),
        display(order.returnStoreName(), order.returnStoreId()),
        display(order.receiveWarehouseName(), "未记录收货部门"),
        display(order.handledBy(), ""),
        order.lines().stream()
            .map(line -> List.of(
                display(line.itemName()),
                display(line.spec(), ""),
                qty(line.quantity()),
                display(line.unit(), "件"),
                money(line.unitPrice()),
                money(line.returnPrice()),
                money(line.amount())
            ))
            .toList()
    ));
  }

  public byte[] movement(WarehouseMovementPrintRow row) {
    boolean in = "IN".equals(row.movementType());
    if (in) {
      return renderReceipt(sheet -> sheet.receiptSlip(
          WarehouseDocumentNumbers.receipt(row.createdAt(), row.movementId()),
          dateOnly(row.createdAt()),
          "采购",
          "自购",
          amount(row.quantityDelta().abs()).multiply(amount(row.unitCost())),
          display(row.note(), ""),
          List.of(List.of(
              display(row.itemName()),
              display(row.itemCode()),
              display(row.spec(), ""),
              qty(row.quantityDelta().abs()),
              display(row.unit(), "件"),
              money(row.unitCost()),
              money(amount(row.quantityDelta().abs()).multiply(amount(row.unitCost()))),
              display(row.note(), "")
          ))
      ));
    }
    return render(sheet -> {
      sheet.title("AI Profit OS 库存流水单");
      sheet.meta(List.of(
          "单据编号：MOVE-" + dateCompact(row.createdAt()) + "-" + padded(row.movementId()),
          "记录时间：" + display(row.createdAt()),
          "业务类型：" + movementType(row.movementType()),
          "门店：" + display(row.storeName(), row.storeId()),
          "来源记录：" + display(row.sourceId()),
          "经办人：" + display(row.operatorName())
      ));
      sheet.section("商品明细");
      sheet.table(
          List.of("商品名称", "规格", "数量", "单位", "批次号", "备注"),
          List.of(List.of(
              display(row.itemName()),
              display(row.spec()),
              qty(row.quantityDelta().abs()),
              display(row.unit()),
              display(row.batchNo(), row.sourceId()),
              display(row.note())
          )),
          new int[] {190, 155, 135, 90, 210, 260}
      );
      sheet.paragraph("说明：" + display(row.note()));
      sheet.signatures("操作人", "复核人");
    });
  }

  private byte[] render(DrawAction drawAction) {
    return render(drawAction, CANVAS_WIDTH, CANVAS_HEIGHT, PDF_WIDTH, PDF_HEIGHT);
  }

  private byte[] renderReceipt(DrawAction drawAction) {
    return render(
        drawAction,
        RECEIPT_CANVAS_WIDTH,
        RECEIPT_CANVAS_HEIGHT,
        RECEIPT_PDF_WIDTH,
        RECEIPT_PDF_HEIGHT
    );
  }

  private byte[] renderDelivery(DrawAction drawAction) {
    return render(
        drawAction,
        DELIVERY_CANVAS_WIDTH,
        DELIVERY_CANVAS_HEIGHT,
        DELIVERY_PDF_WIDTH,
        DELIVERY_PDF_HEIGHT
    );
  }

  private byte[] renderReturn(DrawAction drawAction) {
    return render(
        drawAction,
        RETURN_CANVAS_WIDTH,
        RETURN_CANVAS_HEIGHT,
        RETURN_PDF_WIDTH,
        RETURN_PDF_HEIGHT
    );
  }

  private byte[] render(
      DrawAction drawAction,
      int canvasWidth,
      int canvasHeight,
      float pdfWidth,
      float pdfHeight
  ) {
    try (PDDocument document = new PDDocument();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDType0Font font = loadChineseFont(document);
      PDPage page = new PDPage(new PDRectangle(pdfWidth, pdfHeight));
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.transform(Matrix.getScaleInstance(pdfWidth / canvasWidth, pdfHeight / canvasHeight));
        drawAction.draw(new Sheet(content, font, canvasWidth, canvasHeight));
      }
      document.save(output);
      return output.toByteArray();
    } catch (IOException | RuntimeException ex) {
      throw new BusinessException(
          "PDF_GENERATION_FAILED",
          "生成打印单失败：" + ex.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR
      );
    }
  }

  private PDType0Font loadChineseFont(PDDocument document) throws IOException {
    InputStream input = WarehousePdfRenderer.class.getResourceAsStream(FONT_RESOURCE);
    if (input == null) {
      throw new IOException("缺少内置中文字体 " + FONT_RESOURCE);
    }
    try (input) {
      return PDType0Font.load(document, input, true);
    }
  }

  private String display(String value) {
    return display(value, "-");
  }

  private String display(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return repairMojibake(value);
  }

  private String repairMojibake(String value) {
    if (!looksLikeMojibake(value)) {
      return value;
    }

    StringBuilder repaired = new StringBuilder(value.length());
    StringBuilder encodedRun = new StringBuilder();
    for (int offset = 0; offset < value.length();) {
      int codePoint = value.codePointAt(offset);
      if (mojibakeSourceByte(codePoint) >= 0) {
        encodedRun.appendCodePoint(codePoint);
      } else {
        appendDecodedMojibakeRun(repaired, encodedRun);
        repaired.appendCodePoint(codePoint);
      }
      offset += Character.charCount(codePoint);
    }
    appendDecodedMojibakeRun(repaired, encodedRun);

    String candidate = repaired.toString();
    return cjkCount(candidate) > cjkCount(value) ? candidate : value;
  }

  private void appendDecodedMojibakeRun(StringBuilder output, StringBuilder encodedRun) {
    if (encodedRun.isEmpty()) {
      return;
    }
    ByteArrayOutputStream originalBytes = new ByteArrayOutputStream(encodedRun.length());
    for (int offset = 0; offset < encodedRun.length();) {
      int codePoint = encodedRun.codePointAt(offset);
      originalBytes.write(mojibakeSourceByte(codePoint));
      offset += Character.charCount(codePoint);
    }
    String original = encodedRun.toString();
    String decoded = new String(originalBytes.toByteArray(), StandardCharsets.UTF_8);
    output.append(!decoded.contains("\uFFFD") && cjkCount(decoded) > cjkCount(original)
        ? decoded
        : original);
    encodedRun.setLength(0);
  }

  private int mojibakeSourceByte(int codePoint) {
    if (codePoint >= 0 && codePoint <= 0xff) {
      return codePoint;
    }
    return switch (codePoint) {
      case 0x20ac -> 0x80;
      case 0x201a -> 0x82;
      case 0x0192 -> 0x83;
      case 0x201e -> 0x84;
      case 0x2026 -> 0x85;
      case 0x2020 -> 0x86;
      case 0x2021 -> 0x87;
      case 0x02c6 -> 0x88;
      case 0x2030 -> 0x89;
      case 0x0160 -> 0x8a;
      case 0x2039 -> 0x8b;
      case 0x0152 -> 0x8c;
      case 0x017d -> 0x8e;
      case 0x2018 -> 0x91;
      case 0x2019 -> 0x92;
      case 0x201c -> 0x93;
      case 0x201d -> 0x94;
      case 0x2022 -> 0x95;
      case 0x2013 -> 0x96;
      case 0x2014 -> 0x97;
      case 0x02dc -> 0x98;
      case 0x2122 -> 0x99;
      case 0x0161 -> 0x9a;
      case 0x203a -> 0x9b;
      case 0x0153 -> 0x9c;
      case 0x017e -> 0x9e;
      case 0x0178 -> 0x9f;
      default -> -1;
    };
  }

  private boolean looksLikeMojibake(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if ((current >= '\u0080' && current <= '\u009f')
          || "ÃÂâåæçäèéïðœžŸ".indexOf(current) >= 0) {
        return true;
      }
    }
    return false;
  }

  private long cjkCount(String value) {
    return value.codePoints()
        .filter(codePoint -> codePoint >= 0x3400 && codePoint <= 0x9fff)
        .count();
  }

  private String qty(BigDecimal value) {
    return amount(value).stripTrailingZeros().toPlainString();
  }

  private String money(BigDecimal value) {
    return amount(value).stripTrailingZeros().toPlainString();
  }

  private BigDecimal deliveryTotal(List<WarehouseDeliveryPrintLine> lines) {
    return amount(lines.stream()
        .map(this::lineSubtotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  private BigDecimal lineSubtotal(WarehouseDeliveryPrintLine line) {
    BigDecimal recorded = amount(line.amount());
    if (recorded.compareTo(BigDecimal.ZERO) > 0) {
      return recorded;
    }
    return amount(line.shippedQuantity())
        .multiply(amount(line.unitPrice()))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null
        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
        : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String dateCompact(String value) {
    if (value == null || value.length() < 10) {
      return "00000000";
    }
    return value.substring(0, 10).replace("-", "");
  }

  private String dateOnly(String value) {
    if (value == null || value.length() < 10) {
      return "-";
    }
    return value.substring(0, 10);
  }

  private String padded(long id) {
    return String.format(Locale.ROOT, "%04d", id);
  }

  private String displayItemName(String name, String spec) {
    String itemName = display(name);
    if (spec == null || spec.isBlank() || "-".equals(spec)) {
      return itemName;
    }
    return itemName + "（" + display(spec, "") + "）";
  }

  private String deliveryStatusLabel(String status) {
    return switch (display(status)) {
      case "SHIPPED", "RECEIVED" -> "已核对";
      case "SUBMITTED" -> "待仓库处理";
      case "APPROVED" -> "待发货";
      case "REJECTED" -> "已驳回";
      default -> display(status);
    };
  }

  private String movementType(String type) {
    return switch (type) {
      case "IN" -> "采购到货入库";
      case "OUT" -> "手工出库";
      case "ADJUST" -> "库存调整";
      default -> display(type);
    };
  }

  private interface DrawAction {
    void draw(Sheet sheet) throws IOException;
  }

  private static final class Sheet {
    private final PDPageContentStream content;
    private final PDType0Font font;
    private final int canvasWidth;
    private final int canvasHeight;
    private int y = MARGIN;

    private Sheet(
        PDPageContentStream content,
        PDType0Font font,
        int canvasWidth,
        int canvasHeight
    ) {
      this.content = content;
      this.font = font;
      this.canvasWidth = canvasWidth;
      this.canvasHeight = canvasHeight;
    }

    private void deliverySlip(
        String orderNo,
        String date,
        String status,
        String fromDepartment,
        String toDepartment,
        BigDecimal totalAmount,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        List<List<String>> rows
    ) throws IOException {
      int left = 24;
      int width = canvasWidth - left * 2;
      int titleWidth = 850;
      drawCentered("配送单", left, titleWidth, 40, 22);
      dashedLine(left, left + titleWidth, 52);

      drawText("单据号：" + blank(orderNo), left + 5, 76, 18);
      drawText("日期：" + blank(date), left + 315, 76, 18);
      drawText("状态：" + blank(status), left + 590, 76, 18);
      drawText("配出部门：" + blank(fromDepartment), left + 5, 99, 18);
      drawText("配入部门：" + blank(toDepartment), left + 315, 101, 27);
      drawText("总金额：" + formatAmount(totalAmount), left + 590, 99, 18);

      int tableTop = 104;
      int rowHeight = deliveryRowHeight(rows.size(), tableTop);
      int[] widths = {104, 513, 104, 104, 105, 105};
      numberedTable(
          List.of("序号", "物品名称", "数量", "单位", "单价", "小计"),
          rows,
          widths,
          left,
          tableTop,
          rowHeight,
          rowHeight <= 28 ? 17 : 21
      );

      int tableBottom = tableTop + (rows.size() + 1) * rowHeight;
      int footerY = Math.min(tableBottom + 22, canvasHeight - 42);
      drawText("收货人：" + blank(receiverName), left + 5, footerY, 18);
      drawText("联系电话：" + blank(receiverPhone), left + 230, footerY, 18);
      drawText("收货地址：" + blank(receiverAddress), left + 500, footerY, 18);
      drawCentered("第1页/共1页", left, width, canvasHeight - 12, 16);
    }

    private int deliveryRowHeight(int rowCount, int tableTop) {
      int available = canvasHeight - tableTop - 65;
      int preferred = 34;
      if ((rowCount + 1) * preferred <= available) {
        return preferred;
      }
      return Math.max(22, available / Math.max(1, rowCount + 1));
    }

    private void receiptSlip(
        String receiptNo,
        String date,
        String department,
        String supplierName,
        BigDecimal totalAmount,
        String note,
        List<List<String>> rows
    ) throws IOException {
      int left = 24;
      int width = canvasWidth - left * 2;
      int rightColumn = left + 470;
      drawCentered("入库单", left, width, 38, 28);
      logoPlaceholder(canvasWidth - left - 190, 14, 190, 40);

      drawText("单据号：" + blank(receiptNo), left, 64, 21);
      drawText("日期：" + blank(date), rightColumn, 64, 21);
      drawText("部门：" + blank(department), left, 94, 21);
      drawText("供应商名称：" + blank(supplierName), rightColumn, 94, 21);
      drawText("金额：" + formatAmount(totalAmount), left, 124, 21);
      drawText("备注：" + blank(note), rightColumn, 124, 21);

      numberedTable(
          List.of("序号", "物品名称", "内部编号", "规格", "数量", "单位", "单价", "小计", "备注"),
          rows,
          new int[] {75, 405, 155, 100, 80, 80, 90, 115, 92},
          left,
          134,
          40,
          20
      );
      int statusY = 134 + (rows.size() + 1) * 40 + 26;
      drawText("状态：已核对", left, statusY, 21);
    }

    private void returnSlip(
        String returnNo,
        String date,
        String returnDepartment,
        String receiveDepartment,
        String handledBy,
        List<List<String>> rows
    ) throws IOException {
      int left = 22;
      int width = canvasWidth - left * 2;
      logoPlaceholder(left, 32, 200, 42);
      drawCentered("配送退货单", left, width, 70, 30);

      drawText("退货部门：" + blank(returnDepartment), left, 113, 23);
      drawText("收货部门：" + blank(receiveDepartment), left + 378, 113, 23);
      List<String> handlerLines = wrap(
          "经手人：" + blank(handledBy),
          23,
          canvasWidth - (left + 790) - 30
      );
      drawText(handlerLines.get(0), left + 790, 113, 23);
      if (handlerLines.size() > 1) {
        drawText(handlerLines.get(1), left + 790, 151, 23);
      }

      drawText("单据号：" + blank(returnNo), left + 25, 172, 23);
      drawText("日期：" + blank(date), left + 428, 172, 23);
      int tableTop = 187;
      int rowHeight = returnRowHeight(rows.size(), tableTop);
      numberedTable(
          List.of("序号", "物品名称", "规格", "数量", "单位", "单价", "退货价", "小计"),
          rows,
          new int[] {110, 343, 112, 112, 112, 109, 161, 108},
          left,
          tableTop,
          rowHeight,
          rowHeight <= 28 ? 17 : 23
      );
    }

    private int returnRowHeight(int rowCount, int tableTop) {
      int available = canvasHeight - tableTop - 18;
      int preferred = 40;
      if ((rowCount + 1) * preferred <= available) {
        return preferred;
      }
      return Math.max(18, available / Math.max(1, rowCount + 1));
    }

    private void title(String text) throws IOException {
      drawText(text, MARGIN, y, 34);
      y += 52;
      line(MARGIN, canvasWidth - MARGIN, y);
      y += 30;
    }

    private void meta(List<String> values) throws IOException {
      for (String value : values) {
        drawText(value, MARGIN, y, 24);
        y += 36;
      }
      y += 16;
    }

    private void section(String text) throws IOException {
      y += 14;
      drawText(text, MARGIN, y, 26);
      y += 24;
    }

    private void paragraph(String text) throws IOException {
      y += 28;
      for (String line : wrap(text, 24, canvasWidth - MARGIN * 2)) {
        drawText(line, MARGIN, y, 24);
        y += 34;
      }
    }

    private void signatures(String first, String second) throws IOException {
      y += 70;
      drawText("签字区：", MARGIN, y, 26);
      y += 70;
      drawText(first + "：____________________        " + second + "：____________________", MARGIN, y, 25);
      y += 82;
      drawText("日期：____________________", MARGIN, y, 25);
    }

    private void table(
        List<String> headers,
        List<List<String>> rows,
        int[] widths
    ) throws IOException {
      drawTableRow(headers, widths, MARGIN, y, 56, 21, true);
      y += 56;
      for (List<String> row : rows) {
        drawTableRow(row, widths, MARGIN, y, 56, 21, false);
        y += 56;
      }
    }

    private void numberedTable(
        List<String> headers,
        List<List<String>> rows,
        int[] widths,
        int left,
        int top,
        int rowHeight,
        int fontSize
    ) throws IOException {
      drawTableRow(headers, widths, left, top, rowHeight, fontSize, true);
      int currentTop = top + rowHeight;
      int index = 1;
      for (List<String> row : rows) {
        List<String> cells = new ArrayList<>();
        cells.add(String.valueOf(index++));
        cells.addAll(row);
        drawTableRow(cells, widths, left, currentTop, rowHeight, fontSize, false);
        currentTop += rowHeight;
      }
    }

    private void drawTableRow(
        List<String> cells,
        int[] widths,
        int left,
        int top,
        int rowHeight,
        int fontSize,
        boolean header
    ) throws IOException {
      int totalWidth = 0;
      for (int width : widths) {
        totalWidth += width;
      }
      if (header) {
        fillRect(left, top, totalWidth, rowHeight, 210);
      }
      int x = left;
      for (int width : widths) {
        strokeRect(x, top, width, rowHeight);
        x += width;
      }
      x = left;
      int baseline = top + rowHeight - Math.max(10, (rowHeight - fontSize) / 2);
      for (int index = 0; index < widths.length; index++) {
        String value = index < cells.size() ? cells.get(index) : "";
        String clipped = clip(value, fontSize, widths[index] - 12);
        drawText(clipped, x + 6, baseline, fontSize);
        x += widths[index];
      }
    }

    private void drawText(String value, float x, float baseline, float fontSize) throws IOException {
      String printable = printable(value);
      content.beginText();
      content.setFont(font, fontSize);
      content.newLineAtOffset(x, canvasHeight - baseline);
      content.showText(printable);
      content.endText();
    }

    private void drawCentered(
        String value,
        int left,
        int width,
        int baseline,
        int fontSize
    ) throws IOException {
      String printable = printable(value);
      float x = left + (width - textWidth(printable, fontSize)) / 2f;
      drawText(printable, x, baseline, fontSize);
    }

    private void line(float x1, float x2, float top) throws IOException {
      content.setStrokingColor(0, 0, 0);
      content.setLineWidth(1.2f);
      content.moveTo(x1, canvasHeight - top);
      content.lineTo(x2, canvasHeight - top);
      content.stroke();
    }

    private void dashedLine(float x1, float x2, float top) throws IOException {
      content.setLineDashPattern(new float[] {8, 6}, 0);
      line(x1, x2, top);
      content.setLineDashPattern(new float[0], 0);
    }

    private void strokeRect(float x, float top, float width, float height) throws IOException {
      content.setStrokingColor(0, 0, 0);
      content.setLineWidth(1f);
      content.addRect(x, canvasHeight - top - height, width, height);
      content.stroke();
    }

    private void fillRect(
        float x,
        float top,
        float width,
        float height,
        int gray
    ) throws IOException {
      float normalizedGray = gray / 255f;
      content.setNonStrokingColor(normalizedGray, normalizedGray, normalizedGray);
      content.addRect(x, canvasHeight - top - height, width, height);
      content.fill();
      content.setNonStrokingColor(0, 0, 0);
    }

    private void logoPlaceholder(
        float x,
        float top,
        float width,
        float height
    ) throws IOException {
      strokeRect(x, top, width, height);
    }

    private String clip(String value, float fontSize, float maxWidth) throws IOException {
      String text = printable(blank(value));
      if (textWidth(text, fontSize) <= maxWidth) {
        return text;
      }
      while (!text.isEmpty() && textWidth(text + "...", fontSize) > maxWidth) {
        int end = text.offsetByCodePoints(text.length(), -1);
        text = text.substring(0, end);
      }
      return text + "...";
    }

    private List<String> wrap(String value, float fontSize, float maxWidth) throws IOException {
      String text = printable(blank(value));
      if (textWidth(text, fontSize) <= maxWidth) {
        return List.of(text);
      }
      List<String> lines = new ArrayList<>();
      String remaining = text;
      while (!remaining.isBlank()) {
        int end = remaining.length();
        while (end > 1 && textWidth(remaining.substring(0, end), fontSize) > maxWidth) {
          end = remaining.offsetByCodePoints(end, -1);
        }
        lines.add(remaining.substring(0, end));
        remaining = remaining.substring(end).trim();
      }
      return lines.isEmpty() ? List.of("-") : lines;
    }

    private float textWidth(String value, float fontSize) throws IOException {
      return font.getStringWidth(value) / 1000f * fontSize;
    }

    private String printable(String value) {
      return Normalizer.normalize(
          value == null ? "" : value,
          Normalizer.Form.NFC
      ).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    private String blank(String value) {
      return value == null || value.isBlank() || "-".equals(value) ? "" : value;
    }

    private String formatAmount(BigDecimal value) {
      BigDecimal safe = value == null
          ? BigDecimal.ZERO
          : value.setScale(2, RoundingMode.HALF_UP);
      return safe.stripTrailingZeros().toPlainString();
    }
  }
}
