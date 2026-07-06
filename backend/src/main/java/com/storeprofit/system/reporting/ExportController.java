package com.storeprofit.system.reporting;

import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/export")
public class ExportController {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final AuthService authService;
  private final FinanceService financeService;

  public ExportController(AuthService authService, FinanceService financeService) {
    this.authService = authService;
    this.financeService = financeService;
  }

  @GetMapping("/profit-ranking.csv")
  public ResponseEntity<byte[]> profitRankingCsv(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId
  ) {
    String targetMonth = month == null || month.isBlank() ? YearMonth.now(BUSINESS_ZONE).toString() : month;
    List<ProfitEntryResponse> rows = financeService.entries(authService.requireUser(authorization), targetMonth, brandId, null);
    String csv = toCsv(targetMonth, rows);
    byte[] bytes = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"profit-ranking-" + targetMonth + ".csv\"")
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(bytes);
  }

  private String toCsv(String month, List<ProfitEntryResponse> rows) {
    StringBuilder csv = new StringBuilder();
    csv.append("月份,排名,门店编码,门店,品牌,区域,营业总收入,实收收入,成本合计,费用合计,净利润,净利率,状态\n");
    for (int i = 0; i < rows.size(); i++) {
      ProfitEntryResponse row = rows.get(i);
      csv.append(escape(month)).append(',')
          .append(i + 1).append(',')
          .append(escape(row.storeCode())).append(',')
          .append(escape(row.storeName())).append(',')
          .append(escape(row.brandName())).append(',')
          .append(escape(row.area())).append(',')
          .append(row.sales()).append(',')
          .append(row.income()).append(',')
          .append(row.costSum()).append(',')
          .append(row.expenseSum()).append(',')
          .append(row.net()).append(',')
          .append(row.margin().multiply(new java.math.BigDecimal("100")).setScale(1, java.math.RoundingMode.HALF_UP)).append("%,")
          .append(escape(row.risk()))
          .append('\n');
    }
    return csv.toString();
  }

  private String escape(String value) {
    String safe = value == null ? "" : value;
    return "\"" + safe.replace("\"", "\"\"") + "\"";
  }
}
