package com.storeprofit.system.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class KnowledgeDocumentParserTest {
  private final KnowledgeDocumentParser parser = new KnowledgeDocumentParser();

  @Test
  void extractsExcelHeadersAndRowsWithTheirWorksheetCitation() throws Exception {
    byte[] workbook = workbook();

    KnowledgeDocumentParser.ParsedDocument result = parser.parse(
        new MockMultipartFile("file", "门店流程.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook));

    assertThat(result.fileName()).isEqualTo("门店流程.xlsx");
    assertThat(result.sections()).singleElement().satisfies(section -> {
      assertThat(section.locator()).isEqualTo("工作表：交接班");
      assertThat(section.text()).contains("表头：事项 | 要求");
      assertThat(section.text()).contains("事项：卫生检查");
      assertThat(section.text()).contains("要求：完成登记");
    });
  }

  @Test
  void extractsUtf8CsvAndRejectsUnsupportedLegacyWordFormat() {
    KnowledgeDocumentParser.ParsedDocument csv = parser.parse(
        new MockMultipartFile("file", "交接班.csv", "text/csv", "事项,要求\n卫生检查,完成登记".getBytes(StandardCharsets.UTF_8)));
    assertThat(csv.sections()).singleElement().satisfies(section -> assertThat(section.text()).contains("卫生检查"));

    assertThatThrownBy(() -> parser.parse(new MockMultipartFile("file", "旧文档.doc", "application/msword", new byte[]{1})))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("KNOWLEDGE_BASE_FILE_TYPE_UNSUPPORTED"));
  }

  private byte[] workbook() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("交接班");
      var header = sheet.createRow(0);
      header.createCell(0).setCellValue("事项");
      header.createCell(1).setCellValue("要求");
      var row = sheet.createRow(1);
      row.createCell(0).setCellValue("卫生检查");
      row.createCell(1).setCellValue("完成登记");
      workbook.write(output);
      return output.toByteArray();
    }
  }
}
