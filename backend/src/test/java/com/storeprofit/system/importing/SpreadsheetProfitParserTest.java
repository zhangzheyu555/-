package com.storeprofit.system.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.organization.StoreResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    assertThat(rows.getFirst().storeId()).isEqualTo("rg1");
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
}
