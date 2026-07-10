package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintHeader;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintLine;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseMovementPrintRow;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseReceiptPrintRow;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WarehousePdfRenderer {
  private static final int CANVAS_WIDTH = 1240;
  private static final int CANVAS_HEIGHT = 1754;
  private static final int DELIVERY_CANVAS_WIDTH = 1109;
  private static final int DELIVERY_CANVAS_HEIGHT = 691;
  private static final int MARGIN = 90;
  private static final float PDF_WIDTH = 595f;
  private static final float PDF_HEIGHT = 842f;
  private static final float DELIVERY_PDF_WIDTH = 1109f;
  private static final float DELIVERY_PDF_HEIGHT = 691f;

  public byte[] receipt(WarehouseReceiptPrintRow row) {
    return render(sheet -> {
      sheet.receiptSlip(
          "RKD" + dateShort(row.createdAt()) + padded(row.batchId()),
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
      );
    });
  }

  public byte[] delivery(WarehouseDeliveryPrintHeader header, List<WarehouseDeliveryPrintLine> lines) {
    return renderDelivery(sheet -> {
      sheet.deliverySlip(
          display(header.deliveryId(), header.requisitionId()),
          dateOnly(header.shippedAt()),
          deliveryStatusLabel(header.status()),
          "采购",
          display(header.storeName(), header.storeId()),
          deliveryTotal(lines),
          display(header.receiverName()),
          display(header.receiverPhone()),
          display(header.receiverAddress()),
          display(header.note()),
          lines.stream()
              .map(line -> List.of(
                  displayItemName(line.itemName(), line.spec()),
                  display(line.batchNos(), "-"),
                  qty(line.shippedQuantity()),
                  display(line.unit()),
                  money(line.unitPrice()),
                  money(lineSubtotal(line))
              ))
              .toList()
      );
    });
  }

  public byte[] returnOrder(WarehouseReturnResponse order) {
    return render(sheet -> {
      sheet.returnSlip(
          display(order.returnNo()),
          dateOnly(order.returnDate()),
          display(order.returnStoreName(), order.returnStoreId()),
          display(order.receiveDepartment(), "采购"),
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
      );
    });
  }

  public byte[] movement(WarehouseMovementPrintRow row) {
    boolean in = "IN".equals(row.movementType());
    return render(sheet -> {
      sheet.title(in ? "AI Profit OS 仓库入库单" : "AI Profit OS 库存流水单");
      sheet.meta(List.of(
          "单据编号：" + (in ? "IN" : "MOVE") + "-" + dateCompact(row.createdAt()) + "-" + padded(row.movementId()),
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
      sheet.signatures(in ? "仓库经办人" : "操作人", "复核人");
    });
  }

  private byte[] render(DrawAction drawAction) {
    return render(drawAction, CANVAS_WIDTH, CANVAS_HEIGHT, PDF_WIDTH, PDF_HEIGHT);
  }

  private byte[] renderDelivery(DrawAction drawAction) {
    return render(drawAction, DELIVERY_CANVAS_WIDTH, DELIVERY_CANVAS_HEIGHT, DELIVERY_PDF_WIDTH, DELIVERY_PDF_HEIGHT);
  }

  private byte[] render(DrawAction drawAction, int canvasWidth, int canvasHeight, float pdfWidth, float pdfHeight) {
    BufferedImage image = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, canvasWidth, canvasHeight);
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      drawAction.draw(new Sheet(graphics, chineseFontFamily(), canvasWidth, canvasHeight));
      ByteArrayOutputStream imageOut = new ByteArrayOutputStream();
      ImageIO.write(image, "jpg", imageOut);
      return imagePdf(imageOut.toByteArray(), canvasWidth, canvasHeight, pdfWidth, pdfHeight);
    } catch (IOException ex) {
      throw new BusinessException("PDF_GENERATION_FAILED", "生成打印单失败：" + ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    } finally {
      graphics.dispose();
    }
  }

  private byte[] imagePdf(byte[] imageBytes, int canvasWidth, int canvasHeight, float pdfWidth, float pdfHeight) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<Integer> offsets = new ArrayList<>();
    writeAscii(out, "%PDF-1.4\n");
    offsets.add(out.size());
    writeAscii(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
    offsets.add(out.size());
    writeAscii(out, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
    offsets.add(out.size());
    writeAscii(out, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + pdfWidth + " " + pdfHeight + "] /Resources << /XObject << /Im1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n");
    offsets.add(out.size());
    writeAscii(out, "4 0 obj\n<< /Type /XObject /Subtype /Image /Width " + canvasWidth + " /Height " + canvasHeight + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + imageBytes.length + " >>\nstream\n");
    out.write(imageBytes);
    writeAscii(out, "\nendstream\nendobj\n");
    byte[] content = ("q\n" + pdfWidth + " 0 0 " + pdfHeight + " 0 0 cm\n/Im1 Do\nQ\n").getBytes(StandardCharsets.US_ASCII);
    offsets.add(out.size());
    writeAscii(out, "5 0 obj\n<< /Length " + content.length + " >>\nstream\n");
    out.write(content);
    writeAscii(out, "endstream\nendobj\n");
    int xrefOffset = out.size();
    writeAscii(out, "xref\n0 6\n0000000000 65535 f \n");
    for (int offset : offsets) {
      writeAscii(out, String.format(Locale.ROOT, "%010d 00000 n \n", offset));
    }
    writeAscii(out, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n");
    return out.toByteArray();
  }

  private void writeAscii(ByteArrayOutputStream out, String value) throws IOException {
    out.write(value.getBytes(StandardCharsets.US_ASCII));
  }

  private String chineseFontFamily() {
    Set<String> available = new HashSet<>(Arrays.asList(GraphicsEnvironment
        .getLocalGraphicsEnvironment()
        .getAvailableFontFamilyNames(Locale.SIMPLIFIED_CHINESE)));
    return List.of("SimSun", "Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Dialog")
        .stream()
        .filter(available::contains)
        .findFirst()
        .orElse("Dialog");
  }

  private String display(String value) {
    return display(value, "-");
  }

  private String display(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
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
    return amount(line.shippedQuantity()).multiply(amount(line.unitPrice())).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String dateCompact(String value) {
    if (value == null || value.length() < 10) {
      return "00000000";
    }
    return value.substring(0, 10).replace("-", "");
  }

  private String dateShort(String value) {
    String compact = dateCompact(value);
    return compact.length() == 8 ? compact.substring(2) : compact;
  }

  private String dateOnly(String value) {
    if (value == null || value.length() < 10) {
      return "-";
    }
    return value.substring(0, 10);
  }

  private String padded(long id) {
    return String.format("%04d", id);
  }

  private String safeCode(String value) {
    return value == null || value.isBlank() ? "0000" : value.replaceAll("[^a-zA-Z0-9_-]", "");
  }

  private String displayItemName(String name, String spec) {
    String itemName = display(name);
    if (spec == null || spec.isBlank() || "-".equals(spec)) {
      return itemName;
    }
    return itemName + "（" + spec + "）";
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
    private final Graphics2D graphics;
    private final String fontFamily;
    private final int canvasWidth;
    private final int canvasHeight;
    private int y = MARGIN;

    private Sheet(Graphics2D graphics, String fontFamily, int canvasWidth, int canvasHeight) {
      this.graphics = graphics;
      this.fontFamily = fontFamily;
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
        String note,
        List<List<String>> rows
    ) {
      int left = 24;
      int width = canvasWidth - left * 2;
      y = 46;
      graphics.setColor(Color.BLACK);
      setFont(22, Font.BOLD);
      center("配送单", left, width, y);
      y += 18;
      dashedLine(left, canvasWidth - left);
      y += 28;

      setFont(18, Font.PLAIN);
      graphics.drawString("单据号：" + blank(orderNo), left + 5, y);
      graphics.drawString("日期：" + blank(date), left + 385, y);
      graphics.drawString("状态：" + blank(status), left + 610, y);
      y += 26;
      graphics.drawString("配出部门：" + blank(fromDepartment), left + 5, y);
      setFont(27, Font.BOLD);
      graphics.drawString("配入部门：" + blank(toDepartment), left + 340, y + 2);
      setFont(18, Font.PLAIN);
      graphics.drawString("总金额：" + formatAmount(totalAmount), left + 710, y);
      y += 12;

      int[] widths = {70, 400, 245, 70, 70, 80, 126};
      deliveryTable(
          List.of("序号", "物品名称", "批次号", "数量", "单位", "单价", "小计"),
          rows,
          widths,
          left,
          y + 2
      );

      y += 8;
      setFont(19, Font.PLAIN);
      int noteHeight = 58;
      graphics.drawRect(left, y, width, noteHeight);
      graphics.drawString("备注：" + blank(note), left + 8, y + 35);
      y += noteHeight + 24;

      setFont(18, Font.PLAIN);
      graphics.drawString("收货人：" + blank(receiverName), left + 8, y);
      graphics.drawString("联系电话：" + blank(receiverPhone), left + 260, y);
      graphics.drawString("收货地址：" + blank(receiverAddress), left + 560, y);

      setFont(18, Font.PLAIN);
      center("第1页/共1页", left, width, canvasHeight - 18);
    }

    private void receiptSlip(
        String receiptNo,
        String date,
        String department,
        String supplierName,
        BigDecimal totalAmount,
        String note,
        List<List<String>> rows
    ) {
      int left = 48;
      int width = canvasWidth - left * 2;
      y = 64;
      graphics.setColor(Color.BLACK);
      setFont(30, Font.PLAIN);
      center("入库单", left, width, y);
      logoPlaceholder(canvasWidth - 280, 28, 190, 42);

      y = 108;
      setFont(24, Font.PLAIN);
      graphics.drawString("单据号：" + blank(receiptNo), left, y);
      graphics.drawString("日期：" + blank(date), left + 470, y);
      y += 36;
      graphics.drawString("部门：" + blank(department), left, y);
      graphics.drawString("供应商名称：" + blank(supplierName), left + 470, y);
      y += 36;
      graphics.drawString("金额：" + formatAmount(totalAmount), left, y);
      graphics.drawString("备注：" + blank(note), left + 470, y);
      y += 14;

      numberedSlipTable(
          List.of("序号", "物品名称", "内部编号", "规格", "数量", "单位", "单价", "小计", "备注"),
          rows,
          new int[] {70, 300, 130, 100, 80, 70, 80, 95, 219},
          left,
          y,
          48,
          24
      );
      y += 30;
      setFont(24, Font.PLAIN);
      graphics.drawString("状态：已核对", left, y);
    }

    private void returnSlip(
        String returnNo,
        String date,
        String returnDepartment,
        String receiveDepartment,
        String handledBy,
        List<List<String>> rows
    ) {
      int left = 24;
      int width = canvasWidth - left * 2;
      y = 76;
      graphics.setColor(Color.BLACK);
      logoPlaceholder(left, 48, 200, 42);
      setFont(30, Font.PLAIN);
      center("配送退货单", left, width, y);

      y = 150;
      setFont(24, Font.PLAIN);
      graphics.drawString("退货部门：" + blank(returnDepartment), left, y);
      graphics.drawString("收货部门：" + blank(receiveDepartment), left + 360, y);
      FontMetrics metrics = graphics.getFontMetrics();
      int handlerX = left + 770;
      List<String> handlerLines = wrap("经手人：" + blank(handledBy), metrics, canvasWidth - handlerX - 36);
      graphics.drawString(handlerLines.get(0), handlerX, y);
      if (handlerLines.size() > 1) {
        graphics.drawString(handlerLines.get(1), handlerX, y + 30);
      }
      y += 46;
      graphics.drawString("单据号：" + blank(returnNo), left + 20, y);
      graphics.drawString("日期：" + blank(date), left + 430, y);
      y += 14;

      numberedSlipTable(
          List.of("序号", "物品名称", "规格", "数量", "单位", "单价", "退货价", "小计"),
          rows,
          new int[] {80, 330, 120, 100, 80, 100, 120, 262},
          left,
          y,
          48,
          24
      );
    }

    private void title(String text) {
      setFont(34, Font.BOLD);
      graphics.setColor(Color.BLACK);
      graphics.drawString(text, MARGIN, y);
      y += 52;
      line();
      y += 30;
    }

    private void meta(List<String> values) {
      setFont(24, Font.PLAIN);
      for (String value : values) {
        graphics.drawString(value, MARGIN, y);
        y += 36;
      }
      y += 16;
    }

    private void section(String text) {
      y += 14;
      setFont(26, Font.BOLD);
      graphics.drawString(text, MARGIN, y);
      y += 24;
    }

    private void paragraph(String text) {
      y += 28;
      setFont(24, Font.PLAIN);
      FontMetrics metrics = graphics.getFontMetrics();
      for (String line : wrap(text, metrics, canvasWidth - MARGIN * 2)) {
        graphics.drawString(line, MARGIN, y);
        y += 34;
      }
    }

    private void signatures(String first, String second) {
      y += 70;
      setFont(26, Font.BOLD);
      graphics.drawString("签字区：", MARGIN, y);
      y += 70;
      setFont(25, Font.PLAIN);
      graphics.drawString(first + "：____________________        " + second + "：____________________", MARGIN, y);
      y += 82;
      graphics.drawString("日期：____________________", MARGIN, y);
    }

    private void table(List<String> headers, List<List<String>> rows, int[] widths) {
      setFont(21, Font.BOLD);
      drawRow(headers, widths, true);
      setFont(21, Font.PLAIN);
      for (List<String> row : rows) {
        drawRow(row, widths, false);
      }
    }

    private void deliveryTable(List<String> headers, List<List<String>> rows, int[] widths, int left, int top) {
      y = top;
      int rowHeight = rows.size() > 18 ? 28 : 34;
      int fontSize = rows.size() > 18 ? 18 : 23;
      drawDeliveryRow(headers, widths, left, rowHeight, 22, true);
      int index = 1;
      for (List<String> row : rows) {
        List<String> cells = new ArrayList<>();
        cells.add(String.valueOf(index++));
        cells.addAll(row);
        drawDeliveryRow(cells, widths, left, rowHeight, fontSize, false);
      }
    }

    private void numberedSlipTable(List<String> headers, List<List<String>> rows, int[] widths, int left, int top, int rowHeight, int fontSize) {
      y = top;
      drawSlipRow(headers, widths, left, rowHeight, fontSize, true);
      int index = 1;
      for (List<String> row : rows) {
        List<String> cells = new ArrayList<>();
        cells.add(String.valueOf(index++));
        cells.addAll(row);
        drawSlipRow(cells, widths, left, rowHeight, fontSize, false);
      }
    }

    private void drawSlipRow(List<String> cells, int[] widths, int left, int rowHeight, int fontSize, boolean header) {
      int totalWidth = Arrays.stream(widths).sum();
      int x = left;
      graphics.setStroke(new BasicStroke(1.2f));
      if (header) {
        graphics.setColor(new Color(210, 210, 210));
        graphics.fillRect(left, y, totalWidth, rowHeight);
      }
      graphics.setColor(Color.BLACK);
      for (int width : widths) {
        graphics.drawRect(x, y, width, rowHeight);
        x += width;
      }
      setFont(fontSize, Font.PLAIN);
      FontMetrics metrics = graphics.getFontMetrics();
      x = left;
      for (int index = 0; index < widths.length; index++) {
        String text = index < cells.size() ? cells.get(index) : "";
        String clipped = clip(text, metrics, widths[index] - 12);
        graphics.drawString(clipped, x + 6, y + rowHeight - 14);
        x += widths[index];
      }
      y += rowHeight;
    }

    private void drawDeliveryRow(List<String> cells, int[] widths, int left, int rowHeight, int fontSize, boolean header) {
      int x = left;
      graphics.setStroke(new BasicStroke(1f));
      if (header) {
        graphics.setColor(new Color(210, 210, 210));
        graphics.fillRect(left, y, Arrays.stream(widths).sum(), rowHeight);
      }
      graphics.setColor(Color.BLACK);
      for (int width : widths) {
        graphics.drawRect(x, y, width, rowHeight);
        x += width;
      }
      setFont(fontSize, header ? Font.PLAIN : Font.PLAIN);
      FontMetrics metrics = graphics.getFontMetrics();
      x = left;
      for (int index = 0; index < widths.length; index++) {
        String text = index < cells.size() ? cells.get(index) : "";
        int cellFontSize = (!header && index == 2) ? fittingFontSize(text, fontSize, 14, widths[index] - 12) : fontSize;
        setFont(cellFontSize, Font.PLAIN);
        metrics = graphics.getFontMetrics();
        String clipped = clip(text, metrics, widths[index] - 12);
        graphics.drawString(clipped, x + 6, y + rowHeight - 12);
        x += widths[index];
      }
      y += rowHeight;
    }

    private void drawRow(List<String> cells, int[] widths, boolean header) {
      int rowHeight = 56;
      int x = MARGIN;
      graphics.setStroke(new BasicStroke(1.25f));
      graphics.setColor(Color.BLACK);
      for (int width : widths) {
        graphics.drawRect(x, y, width, rowHeight);
        x += width;
      }
      FontMetrics metrics = graphics.getFontMetrics();
      x = MARGIN;
      for (int index = 0; index < widths.length; index++) {
        String text = index < cells.size() ? cells.get(index) : "";
        String clipped = clip(text, metrics, widths[index] - 20);
        graphics.drawString(clipped, x + 10, y + 36);
        x += widths[index];
      }
      y += rowHeight;
      if (header) {
        setFont(21, Font.PLAIN);
      }
    }

    private void line() {
      graphics.setStroke(new BasicStroke(1.5f));
      graphics.drawLine(MARGIN, y, canvasWidth - MARGIN, y);
    }

    private void dashedLine(int x1, int x2) {
      graphics.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {8, 6}, 0));
      graphics.drawLine(x1, y, x2, y);
      graphics.setStroke(new BasicStroke(1f));
    }

    private void logoPlaceholder(int x, int y, int width, int height) {
      graphics.setStroke(new BasicStroke(1f));
      graphics.setColor(Color.LIGHT_GRAY);
      graphics.drawRect(x, y, width, height);
      graphics.setColor(Color.BLACK);
    }

    private void center(String text, int left, int width, int baseline) {
      FontMetrics metrics = graphics.getFontMetrics();
      graphics.drawString(text, left + (width - metrics.stringWidth(text)) / 2, baseline);
    }

    private void setFont(int size, int style) {
      graphics.setFont(new Font(fontFamily, style, size));
    }

    private String blank(String value) {
      return value == null || value.isBlank() || "-".equals(value) ? "" : value;
    }

    private String formatAmount(BigDecimal value) {
      BigDecimal safe = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
      return safe.stripTrailingZeros().toPlainString();
    }

    private String clip(String value, FontMetrics metrics, int maxWidth) {
      String text = value == null || value.isBlank() ? "-" : value;
      if (metrics.stringWidth(text) <= maxWidth) {
        return text;
      }
      while (!text.isEmpty() && metrics.stringWidth(text + "...") > maxWidth) {
        text = text.substring(0, text.length() - 1);
      }
      return text + "...";
    }

    private int fittingFontSize(String value, int preferredSize, int minSize, int maxWidth) {
      String text = value == null || value.isBlank() ? "-" : value;
      for (int size = preferredSize; size > minSize; size--) {
        FontMetrics metrics = graphics.getFontMetrics(new Font(fontFamily, Font.PLAIN, size));
        if (metrics.stringWidth(text) <= maxWidth) {
          return size;
        }
      }
      return minSize;
    }

    private List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
      String value = text == null || text.isBlank() ? "-" : text;
      if (metrics.stringWidth(value) <= maxWidth) {
        return List.of(value);
      }
      List<String> lines = new ArrayList<>();
      String remaining = value;
      while (!remaining.isBlank()) {
        int end = remaining.length();
        while (end > 1 && metrics.stringWidth(remaining.substring(0, end)) > maxWidth) {
          end--;
        }
        lines.add(remaining.substring(0, end));
        remaining = remaining.substring(end).trim();
      }
      return lines.isEmpty() ? Arrays.asList("-") : lines;
    }
  }
}
