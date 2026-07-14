package com.storeprofit.system.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.organization.StoreResponse;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class SpreadsheetProfitParserTest {
  private final SpreadsheetProfitParser parser = new SpreadsheetProfitParser();
  private final List<StoreResponse> stores = List.of(
      new StoreResponse("rg1", "RG001", "保利店", 1L, "茹果奶茶", "荆州", "张三", null, "营业中", null),
      new StoreResponse("rg2", "RG002", "荆州之星店", 1L, "茹果奶茶", "荆州", "李四", null, "营业中", null)
  );

  @Test
  void parsesWideCsvRows() {
    String csv = """
        门店,月份,营业总收入,退款金额,原材料成本,人工工资
        保利店,2026-07,83896,-123,27871,12000
        """;

    List<ProfitImportRow> rows = parser.parseCsvText(csv, stores, "", "");

    assertThat(rows).hasSize(1);
    ProfitImportRow row = rows.getFirst();
    assertThat(row.storeId()).isEqualTo("rg1");
    assertThat(row.month()).isEqualTo("2026-07");
    assertThat(row.values().get("sales")).isEqualByComparingTo(new BigDecimal("83896.00"));
    assertThat(row.values().get("refund")).isEqualByComparingTo(new BigDecimal("123.00"));
    assertThat(row.values().get("material")).isEqualByComparingTo(new BigDecimal("27871.00"));
    assertThat(row.values().get("labor")).isEqualByComparingTo(new BigDecimal("12000.00"));
    assertThat(row.errors()).isEmpty();
  }

  @Test
  void keepsSourceMonthWhenDefaultMonthIsDifferent() {
    String csv = """
        门店,月份,营业总收入,原材料成本
        保利店,2099-12,8888,1234
        """;

    List<ProfitImportRow> rows = parser.parseCsvText(csv, stores, "rg1", "2026-07");

    assertThat(rows).hasSize(1);
    ProfitImportRow row = rows.getFirst();
    assertThat(row.storeId()).isEqualTo("rg1");
    assertThat(row.month()).isEqualTo("2099-12");
    assertThat(row.values().get("sales")).isEqualByComparingTo(new BigDecimal("8888.00"));
    assertThat(row.errors()).isEmpty();
  }

  @Test
  void parsesUploadedUtf8CsvRows() throws Exception {
    String csv = """
        门店,月份,营业总收入,退款金额,原材料成本,人工工资
        荆州之星店,2099-12,8888,0,1234,1000
        """;
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "month-conflict-sample.csv",
        "text/csv",
        csv.getBytes(StandardCharsets.UTF_8)
    );

    List<ProfitImportRow> rows = parser.parse(file, ProfitImportSourceType.CSV, stores, "rg1", "2026-07");

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().storeId()).isEqualTo("rg2");
    assertThat(rows.getFirst().month()).isEqualTo("2099-12");
    assertThat(rows.getFirst().values().get("sales")).isEqualByComparingTo(new BigDecimal("8888.00"));
  }

  @Test
  void parsesVerticalCsvWithDefaultStoreAndMonth() {
    String csv = """
        项目,金额
        营业总收入,83896
        原材料成本,27871
        房租,8000
        """;

    List<ProfitImportRow> rows = parser.parseCsvText(csv, stores, "rg2", "2026-05");

    assertThat(rows).hasSize(1);
    ProfitImportRow row = rows.getFirst();
    assertThat(row.storeId()).isEqualTo("rg2");
    assertThat(row.month()).isEqualTo("2026-05");
    assertThat(row.values().get("sales")).isEqualByComparingTo(new BigDecimal("83896.00"));
    assertThat(row.values().get("material")).isEqualByComparingTo(new BigDecimal("27871.00"));
    assertThat(row.values().get("rent")).isEqualByComparingTo(new BigDecimal("8000.00"));
    assertThat(row.errors()).isEmpty();
  }

  @Test
  void parsesUtf8BomSemicolonAndChineseHeaderAliases() throws Exception {
    String csv = "\uFEFF门店名称;经营月份;销售额;物料成本;人工成本;营销费\r\n"
        + "保利店;2026-07;\"¥1,234.56\";234.50;300.00;12.30\r\n";
    MockMultipartFile file = new MockMultipartFile(
        "file", "门店利润_2026-07.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

    List<ProfitImportRow> rows = parser.parse(file, ProfitImportSourceType.CSV, stores, "", "");

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().storeId()).isEqualTo("rg1");
    assertThat(rows.getFirst().month()).isEqualTo("2026-07");
    assertThat(rows.getFirst().values()).containsEntry("sales", new BigDecimal("1234.56"));
    assertThat(rows.getFirst().values()).containsEntry("material", new BigDecimal("234.50"));
    assertThat(rows.getFirst().values()).containsEntry("labor", new BigDecimal("300.00"));
    assertThat(rows.getFirst().values()).containsEntry("promo", new BigDecimal("12.30"));
  }

  @Test
  void parsesGbkTabSeparatedCsv() throws Exception {
    String csv = "店铺\t月份\t营业收入\t包装成本\r\n荆州之星店\t2026-07\t8888.00\t321.00\r\n";
    MockMultipartFile file = new MockMultipartFile(
        "file", "gbk-profit.csv", "text/csv", csv.getBytes(Charset.forName("GBK")));

    List<ProfitImportRow> rows = parser.parse(file, ProfitImportSourceType.CSV, stores, "", "");

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().storeId()).isEqualTo("rg2");
    assertThat(rows.getFirst().values()).containsEntry("sales", new BigDecimal("8888.00"));
    assertThat(rows.getFirst().values()).containsEntry("packaging", new BigDecimal("321.00"));
  }

  @Test
  void reportsActualHeadersAndMissingFields() {
    String csv = "门店名称,经营月份,备注\n保利店,2026-07,没有金额\n";

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parseCsvText(csv, stores, "", ""))
        .isInstanceOf(ProfitImportParseException.class)
        .hasMessageContaining("已读取表头：门店名称、经营月份、备注")
        .hasMessageContaining("缺少字段：营业额");
  }

  @Test
  void aggregatesDailySalesForXlsxAndXlsAndKeepsDetectedMonth() throws Exception {
    for (Workbook workbook : List.of(new XSSFWorkbook(), new HSSFWorkbook())) {
      byte[] bytes;
      try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        var sheet = workbook.createSheet("荆州之星");
        var header = sheet.createRow(0);
        header.createCell(0).setCellValue("荆州之星店");
        header.createCell(1).setCellValue("微信");
        header.createCell(2).setCellValue("支付宝");
        header.createCell(3).setCellValue("总合计");
        var dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
        for (int day = 1; day <= 3; day++) {
          var row = sheet.createRow(day);
          var date = row.createCell(0);
          date.setCellValue(Date.from(LocalDate.of(2026, 5, day).atStartOfDay(ZoneId.systemDefault()).toInstant()));
          date.setCellStyle(dateStyle);
          row.createCell(1).setCellValue(day * 10);
          row.createCell(2).setCellValue(day);
          row.createCell(3).setCellFormula("B" + (day + 1) + "+C" + (day + 1));
        }
        workbook.write(output);
        bytes = output.toByteArray();
      }
      MockMultipartFile file = new MockMultipartFile(
          "file", workbook instanceof HSSFWorkbook ? "daily.xls" : "daily.xlsx",
          "application/octet-stream", bytes);

      List<ProfitImportRow> rows = parser.parse(file, ProfitImportSourceType.EXCEL, stores, "rg1", "2026-07");

      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().storeId()).isEqualTo("rg2");
      assertThat(rows.getFirst().month()).isEqualTo("2026-05");
      assertThat(rows.getFirst().values()).containsEntry("sales", new BigDecimal("66.00"));
      assertThat(rows.getFirst().warnings()).contains("已汇总 3 天日营业额");
    }
  }

  @Test
  void rejectsDamagedExcelAndTooManySheets() throws Exception {
    MockMultipartFile damaged = new MockMultipartFile(
        "file", "damaged.xlsx", "application/octet-stream", "not-an-excel-file".getBytes(StandardCharsets.UTF_8));
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> parser.parse(damaged, ProfitImportSourceType.EXCEL, stores, "rg1", "2026-07"))
        .isInstanceOf(IOException.class);

    byte[] bytes;
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      for (int i = 0; i < 51; i++) workbook.createSheet("sheet-" + i);
      workbook.write(output);
      bytes = output.toByteArray();
    }
    MockMultipartFile tooManySheets = new MockMultipartFile(
        "file", "too-many.xlsx", "application/octet-stream", bytes);
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> parser.parse(tooManySheets, ProfitImportSourceType.EXCEL, stores, "rg1", "2026-07"))
        .isInstanceOf(ProfitImportParseException.class)
        .hasMessageContaining("工作表不能超过 50 个");
  }
}
