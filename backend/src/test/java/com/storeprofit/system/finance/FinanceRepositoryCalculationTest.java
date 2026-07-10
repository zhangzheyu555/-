package com.storeprofit.system.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class FinanceRepositoryCalculationTest {

  @Test
  void calculatesProfitAndCostRatiosWithBigDecimal() throws Exception {
    FinanceRepository repository = new FinanceRepository(
        mock(JdbcTemplate.class), mock(NamedParameterJdbcTemplate.class));
    ResultSet resultSet = mock(ResultSet.class);
    Map<String, BigDecimal> amounts = Map.ofEntries(
        Map.entry("sales", new BigDecimal("1000.00")),
        Map.entry("refund", new BigDecimal("100.00")),
        Map.entry("discount", new BigDecimal("50.00")),
        Map.entry("material", new BigDecimal("200.00")),
        Map.entry("packaging", new BigDecimal("50.00")),
        Map.entry("loss", new BigDecimal("10.00")),
        Map.entry("cost_other", new BigDecimal("40.00")),
        Map.entry("rent", new BigDecimal("100.00")),
        Map.entry("labor", new BigDecimal("200.00")),
        Map.entry("utility", new BigDecimal("20.00")),
        Map.entry("property", new BigDecimal("10.00")),
        Map.entry("commission", new BigDecimal("30.00")),
        Map.entry("promo", new BigDecimal("20.00")),
        Map.entry("repair", BigDecimal.ZERO),
        Map.entry("equip", BigDecimal.ZERO),
        Map.entry("exp_other", new BigDecimal("20.00"))
    );
    when(resultSet.getBigDecimal(anyString())).thenAnswer(call -> amounts.get(call.getArgument(0)));
    when(resultSet.getLong("id")).thenReturn(1L);
    when(resultSet.getLong("brand_id")).thenReturn(2L);
    when(resultSet.getString(anyString())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
      case "store_id" -> "store-1";
      case "store_code" -> "S001";
      case "store_name" -> "测试门店";
      case "brand_name" -> "测试品牌";
      case "area" -> "测试区域";
      case "manager" -> "测试店长";
      case "month" -> "2026-07";
      case "note" -> "";
      default -> null;
    });

    ProfitEntryResponse response = ReflectionTestUtils.invokeMethod(repository, "mapEntry", resultSet, 0);

    assertThat(response).isNotNull();
    assertThat(response.income()).isEqualByComparingTo("850.00");
    assertThat(response.costSum()).isEqualByComparingTo("300.00");
    assertThat(response.costRatio()).isEqualByComparingTo("0.3529");
    assertThat(response.gross()).isEqualByComparingTo("550.00");
    assertThat(response.grossMargin()).isEqualByComparingTo("0.6471");
    assertThat(response.expenseSum()).isEqualByComparingTo("400.00");
    assertThat(response.net()).isEqualByComparingTo("150.00");
    assertThat(response.margin()).isEqualByComparingTo("0.1765");
  }
}
