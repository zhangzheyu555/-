package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

final class WarehouseRequisitionSummaryExcelWriter {
  private static final String[] HEADERS = {
      "门店ID", "门店名称", "物料ID", "物料名称", "周期开始", "周期结束",
      "周期标签", "计量单位", "订货数量", "订货金额（元）"
  };

  private WarehouseRequisitionSummaryExcelWriter() {
  }

  static byte[] write(List<WarehouseRequisitionSummaryRow> rows) {
    try (Workbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("叫货汇总");
      CellStyle headerStyle = headerStyle(workbook);
      CellStyle quantityStyle = numberStyle(workbook, "#,##0.00");
      CellStyle amountStyle = numberStyle(workbook, "#,##0.00");
      Row header = sheet.createRow(0);
      for (int column = 0; column < HEADERS.length; column++) {
        Cell cell = header.createCell(column);
        cell.setCellValue(HEADERS[column]);
        cell.setCellStyle(headerStyle);
      }

      int rowIndex = 1;
      for (WarehouseRequisitionSummaryRow value : rows) {
        Row row = sheet.createRow(rowIndex++);
        text(row, 0, value.storeId());
        text(row, 1, value.storeName());
        text(row, 2, value.productId() == null ? "" : String.valueOf(value.productId()));
        text(row, 3, value.productName());
        text(row, 4, date(value.periodStart()));
        text(row, 5, date(value.periodEnd()));
        text(row, 6, value.periodLabel());
        text(row, 7, value.unit());
        number(row, 8, value.orderedQuantity(), quantityStyle);
        number(row, 9, value.orderedAmount(), amountStyle);
      }

      int[] widths = {16, 24, 14, 28, 14, 14, 18, 12, 16, 18};
      for (int column = 0; column < widths.length; column++) {
        sheet.setColumnWidth(column, widths[column] * 256);
      }
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
          0, Math.max(0, rowIndex - 1), 0, HEADERS.length - 1));
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new BusinessException(
          "REQUISITION_SUMMARY_EXPORT_FAILED",
          "叫货汇总报表生成失败，请稍后重试",
          HttpStatus.INTERNAL_SERVER_ERROR
      );
    }
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

  private static String date(LocalDate value) {
    return value == null ? "" : value.toString();
  }
}
