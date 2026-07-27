package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;

/**
 * Generates a multi-sheet XLSX workbook for warehouse movement export.
 * Sheets: "筛选说明", "出入库明细", "门店物料汇总".
 */
final class WarehouseMovementExcelWriter {
  private static final String[] DETAIL_HEADERS = {
      "业务时间", "方向", "业务来源", "来源单号", "仓库", "来源仓", "目标仓",
      "关联门店", "物料编码", "物料名称", "分类", "规格", "单位", "批次号",
      "数量变化", "入库数量", "出库数量", "操作人", "备注"
  };

  private static final String[] SUMMARY_HEADERS = {
      "门店ID", "门店名称", "物料ID", "物料名称", "单位", "入库数量", "出库数量", "净变化"
  };

  private WarehouseMovementExcelWriter() {
  }

  static byte[] write(
      List<WarehouseStockMovementResponse> rows,
      String warehouseName,
      LocalDate startDate,
      LocalDate endDate,
      List<String> storeNames,
      List<String> itemNames,
      List<String> directions
  ) {
    try (Workbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      CellStyle headerStyle = headerStyle(workbook);
      CellStyle quantityStyle = numberStyle(workbook, "#,##0.00");

      writeFilterSheet(workbook, headerStyle, warehouseName, startDate, endDate,
          storeNames, itemNames, directions);
      writeDetailSheet(workbook, headerStyle, quantityStyle, rows);
      writeSummarySheet(workbook, headerStyle, quantityStyle, rows);

      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new BusinessException(
          "MOVEMENT_EXPORT_FAILED",
          "出入库流水导出失败，请稍后重试",
          HttpStatus.INTERNAL_SERVER_ERROR
      );
    }
  }

  private static void writeFilterSheet(
      Workbook workbook, CellStyle headerStyle,
      String warehouseName, LocalDate startDate, LocalDate endDate,
      List<String> storeNames, List<String> itemNames, List<String> directions
  ) {
    Sheet sheet = workbook.createSheet("筛选说明");
    int rowIndex = 0;
    rowIndex = filterRow(sheet, headerStyle, rowIndex, "仓库", warehouseName);
    rowIndex = filterRow(sheet, headerStyle, rowIndex, "开始日期", startDate.toString());
    rowIndex = filterRow(sheet, headerStyle, rowIndex, "结束日期", endDate.toString());
    rowIndex = filterRow(sheet, headerStyle, rowIndex, "门店范围",
        storeNames.isEmpty() ? "全部授权门店" : String.join("、", storeNames));
    rowIndex = filterRow(sheet, headerStyle, rowIndex, "物料范围",
        itemNames.isEmpty() ? "全部可见物料" : String.join("、", itemNames));
    rowIndex = filterRow(sheet, headerStyle, rowIndex, "方向",
        directions.isEmpty() ? "全部" : String.join("、", directions));
    filterRow(sheet, headerStyle, rowIndex, "生成时间",
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    sheet.setColumnWidth(0, 14 * 256);
    sheet.setColumnWidth(1, 60 * 256);
  }

  private static int filterRow(Sheet sheet, CellStyle style, int rowIndex, String label, String value) {
    Row row = sheet.createRow(rowIndex);
    Cell labelCell = row.createCell(0);
    labelCell.setCellValue(label);
    labelCell.setCellStyle(style);
    row.createCell(1).setCellValue(value == null ? "" : value);
    return rowIndex + 1;
  }

  private static void writeDetailSheet(
      Workbook workbook, CellStyle headerStyle, CellStyle quantityStyle,
      List<WarehouseStockMovementResponse> rows
  ) {
    Sheet sheet = workbook.createSheet("出入库明细");
    Row header = sheet.createRow(0);
    for (int col = 0; col < DETAIL_HEADERS.length; col++) {
      Cell cell = header.createCell(col);
      cell.setCellValue(DETAIL_HEADERS[col]);
      cell.setCellStyle(headerStyle);
    }

    int rowIndex = 1;
    for (WarehouseStockMovementResponse m : rows) {
      Row row = sheet.createRow(rowIndex++);
      text(row, 0, m.createdAt());
      text(row, 1, directionLabel(m.movementType()));
      text(row, 2, sourceLabel(m.sourceType()));
      text(row, 3, m.sourceId());
      text(row, 4, m.warehouseName());
      text(row, 5, m.sourceWarehouseName());
      text(row, 6, m.targetWarehouseName());
      text(row, 7, m.storeName());
      text(row, 8, ""); // itemCode — not in current response, leave blank
      text(row, 9, m.itemName());
      text(row, 10, ""); // category — not in current response
      text(row, 11, ""); // spec
      text(row, 12, ""); // unit
      text(row, 13, m.batchNo());
      number(row, 14, m.quantityDelta(), quantityStyle);
      number(row, 15, m.quantityDelta() != null && m.quantityDelta().signum() > 0 ? m.quantityDelta() : BigDecimal.ZERO, quantityStyle);
      number(row, 16, m.quantityDelta() != null && m.quantityDelta().signum() < 0 ? m.quantityDelta().abs() : BigDecimal.ZERO, quantityStyle);
      text(row, 17, m.operatorName());
      text(row, 18, m.note());
    }

    if (rows.isEmpty()) {
      sheet.createRow(1).createCell(0).setCellValue("当前条件无匹配流水");
    }

    int[] widths = {18, 8, 14, 16, 14, 14, 14, 14, 12, 18, 10, 10, 8, 14, 12, 12, 12, 12, 24};
    for (int col = 0; col < widths.length; col++) {
      sheet.setColumnWidth(col, widths[col] * 256);
    }
    sheet.createFreezePane(0, 1);
    sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
        0, Math.max(0, rowIndex - 1), 0, DETAIL_HEADERS.length - 1));
  }

  private static void writeSummarySheet(
      Workbook workbook, CellStyle headerStyle, CellStyle quantityStyle,
      List<WarehouseStockMovementResponse> rows
  ) {
    Sheet sheet = workbook.createSheet("门店物料汇总");
    Row header = sheet.createRow(0);
    for (int col = 0; col < SUMMARY_HEADERS.length; col++) {
      Cell cell = header.createCell(col);
      cell.setCellValue(SUMMARY_HEADERS[col]);
      cell.setCellStyle(headerStyle);
    }

    // Aggregate by storeId + itemId
    Map<String, BigDecimal[]> agg = new LinkedHashMap<>();
    Map<String, String[]> meta = new LinkedHashMap<>();
    for (WarehouseStockMovementResponse m : rows) {
      String key = (m.storeId() == null ? "" : m.storeId()) + "|" + m.itemId();
      BigDecimal[] totals = agg.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
      if (m.quantityDelta() != null) {
        if (m.quantityDelta().signum() > 0) {
          totals[0] = totals[0].add(m.quantityDelta());
        } else {
          totals[1] = totals[1].add(m.quantityDelta().abs());
        }
        totals[2] = totals[2].add(m.quantityDelta());
      }
      meta.putIfAbsent(key, new String[]{
          m.storeId(), m.storeName(), String.valueOf(m.itemId()), m.itemName(), ""
      });
    }

    int rowIndex = 1;
    for (var entry : agg.entrySet()) {
      String[] info = meta.get(entry.getKey());
      BigDecimal[] totals = entry.getValue();
      Row row = sheet.createRow(rowIndex++);
      text(row, 0, info[0]);
      text(row, 1, info[1]);
      text(row, 2, info[2]);
      text(row, 3, info[3]);
      text(row, 4, info[4]);
      number(row, 5, totals[0], quantityStyle);
      number(row, 6, totals[1], quantityStyle);
      number(row, 7, totals[2], quantityStyle);
    }

    int[] widths = {14, 20, 10, 20, 10, 14, 14, 14};
    for (int col = 0; col < widths.length; col++) {
      sheet.setColumnWidth(col, widths[col] * 256);
    }
    sheet.createFreezePane(0, 1);
  }

  private static String directionLabel(String movementType) {
    if ("IN".equals(movementType)) return "入库";
    if ("OUT".equals(movementType)) return "出库";
    if ("ADJUST".equals(movementType)) return "调整";
    return movementType == null ? "" : movementType;
  }

  private static String sourceLabel(String sourceType) {
    if (sourceType == null) return "库存流水";
    return switch (sourceType) {
      case "REQUISITION" -> "门店叫货发货";
      case "RETURN_RECEIVE" -> "配送退货入库";
      case "WAREHOUSE_TRANSFER" -> "仓间调拨";
      case "PURCHASE_ORDER" -> "采购入库";
      case "STOCK_RECEIVE" -> "手工入库";
      case "STOCK_ADJUST" -> "库存调整";
      default -> sourceType.contains("TRANSFER") ? "仓间调拨"
          : sourceType.contains("RECEIVE") ? "采购入库" : sourceType;
    };
  }

  private static CellStyle headerStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    font.setColor(IndexedColors.WHITE.getIndex());
    style.setFont(font);
    style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    style.setAlignment(HorizontalAlignment.CENTER);
    return style;
  }

  private static CellStyle numberStyle(Workbook workbook, String format) {
    CellStyle style = workbook.createCellStyle();
    style.setDataFormat(workbook.createDataFormat().getFormat(format));
    return style;
  }

  private static void text(Row row, int column, String value) {
    row.createCell(column).setCellValue(value == null ? "" : value);
  }

  private static void number(Row row, int column, BigDecimal value, CellStyle style) {
    Cell cell = row.createCell(column);
    cell.setCellValue(value == null ? 0D : value.doubleValue());
    if (style != null) {
      cell.setCellStyle(style);
    }
  }
}
