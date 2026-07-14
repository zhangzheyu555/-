package com.storeprofit.system.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.importing.ProfitImportRow;
import com.storeprofit.system.importing.ProfitImportSourceType;
import com.storeprofit.system.importing.SpreadsheetProfitParser;
import com.storeprofit.system.organization.StoreResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ProfitCsvRoundTripTest {
  @Test
  void exportedProfitCsvCanBeRecognizedWithoutEditing() throws Exception {
    ProfitEntryResponse entry = new ProfitEntryResponse(
        1L, "rg1", "RG001", "保利店", 1L, "茹果奶茶", "荆州", "张三", "2026-07",
        amount("538252.94"), amount("100.00"), amount("50.00"), amount("538102.94"),
        amount("178747.23"), amount("1200.00"), amount("300.00"), amount("400.00"),
        amount("180647.23"), amount("0.34"), amount("357455.71"), amount("0.66"),
        amount("8000.00"), amount("52804.00"), amount("1800.00"), amount("900.00"),
        amount("3254.00"), amount("1200.00"), amount("500.00"), amount("600.00"),
        amount("214250.05"), amount("283308.05"), amount("74147.66"), amount("0.14"), "正常", "七月数据");
    String csv = "\uFEFF" + ExportController.toProfitCsv("2026-07", List.of(entry));
    MockMultipartFile file = new MockMultipartFile(
        "file", "门店利润_2026-07.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    List<StoreResponse> stores = List.of(
        new StoreResponse("rg1", "RG001", "保利店", 1L, "茹果奶茶", "荆州", "张三", null, "营业中", null));

    List<ProfitImportRow> rows = new SpreadsheetProfitParser().parse(
        file, ProfitImportSourceType.CSV, stores, "", "");

    assertThat(rows).hasSize(1);
    ProfitImportRow row = rows.getFirst();
    assertThat(row.storeId()).isEqualTo("rg1");
    assertThat(row.month()).isEqualTo("2026-07");
    assertThat(row.errors()).isEmpty();
    assertThat(row.values()).containsEntry("sales", amount("538252.94"));
    assertThat(row.values()).containsEntry("material", amount("178747.23"));
    assertThat(row.values()).containsEntry("labor", amount("52804.00"));
    assertThat(row.values()).containsEntry("expOther", amount("214250.05"));
  }

  private static BigDecimal amount(String value) {
    return new BigDecimal(value);
  }
}
