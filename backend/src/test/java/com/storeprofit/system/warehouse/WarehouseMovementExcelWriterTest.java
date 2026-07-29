package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class WarehouseMovementExcelWriterTest {

  @Test
  void exportsConcreteItemDetailsAndOpensDetailSheetByDefault() throws Exception {
    WarehouseStockMovementResponse movement = new WarehouseStockMovementResponse(
        101L,
        12L,
        88L,
        "荆州叫货测试牛奶",
        "TEST-JZ-REQ-001",
        "测试物料",
        "12盒/箱",
        "箱",
        "OUT",
        "出库",
        new BigDecimal("-5.00"),
        1L,
        "荆州总仓",
        1L,
        "荆州总仓",
        2L,
        "山东分仓",
        "WAREHOUSE_TRANSFER",
        "transfer-test-1",
        null,
        null,
        "导出测试",
        "老板",
        "2026-07-29 15:05",
        "EXPORT-TEST-20260729-MILK"
    );

    byte[] content = WarehouseMovementExcelWriter.write(
        List.of(movement),
        "荆州总仓",
        LocalDate.of(2026, 7, 29),
        LocalDate.of(2026, 7, 29),
        List.of(),
        List.of(),
        List.of("出库")
    );

    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      assertThat(workbook.getActiveSheetIndex()).isEqualTo(1);
      assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("出入库明细");
      assertThat(workbook.getSheetAt(1).isSelected()).isTrue();

      Row detail = workbook.getSheet("出入库明细").getRow(1);
      assertThat(detail.getCell(8).getStringCellValue()).isEqualTo("TEST-JZ-REQ-001");
      assertThat(detail.getCell(9).getStringCellValue()).isEqualTo("荆州叫货测试牛奶");
      assertThat(detail.getCell(10).getStringCellValue()).isEqualTo("测试物料");
      assertThat(detail.getCell(11).getStringCellValue()).isEqualTo("12盒/箱");
      assertThat(detail.getCell(12).getStringCellValue()).isEqualTo("箱");
      assertThat(detail.getCell(14).getNumericCellValue()).isEqualTo(-5D);
      assertThat(detail.getCell(16).getNumericCellValue()).isEqualTo(5D);

      Row summary = workbook.getSheet("门店物料汇总").getRow(1);
      assertThat(summary.getCell(4).getStringCellValue()).isEqualTo("箱");
    }
  }
}
