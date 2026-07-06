package com.storeprofit.system.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.storage.StorageService;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Map;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("organizationSeedService")
public class FinanceSeedService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final FinanceRepository financeRepository;
  private final StorageService storageService;
  private final ObjectMapper objectMapper;

  public FinanceSeedService(FinanceRepository financeRepository, StorageService storageService, ObjectMapper objectMapper) {
    this.financeRepository = financeRepository;
    this.storageService = storageService;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  public void seed() {
    if (financeRepository.profitCount() == 0) {
      seedLegacyEntries();
    }
    seedFallbackEntries();
  }

  private int seedLegacyEntries() {
    return storageService.get("entries").map(raw -> {
      int saved = 0;
      try {
        JsonNode root = objectMapper.readTree(raw);
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> entry = fields.next();
          String[] parts = entry.getKey().split("\\|");
          if (parts.length != 2 || !parts[1].matches("\\d{4}-\\d{2}") || !financeRepository.storeExists(parts[0])) {
            continue;
          }
          JsonNode value = entry.getValue();
          financeRepository.upsert(new ProfitEntryRequest(
              parts[0],
              parts[1],
              firstAmount(value, "sales", "rev"),
              amount(value, "refund"),
              amount(value, "discount"),
              amount(value, "material"),
              amount(value, "packaging"),
              amount(value, "loss"),
              amount(value, "cost_other"),
              amount(value, "rent"),
              amount(value, "labor"),
              amount(value, "utility"),
              amount(value, "property"),
              amount(value, "commission"),
              amount(value, "promo"),
              amount(value, "repair"),
              amount(value, "equip"),
              firstAmount(value, "exp_other", "other"),
              "旧前端数据迁移"
          ), null);
          saved++;
        }
      } catch (Exception ex) {
        return 0;
      }
      return saved;
    }).orElse(0);
  }

  private void seedFallbackEntries() {
    YearMonth current = YearMonth.now(BUSINESS_ZONE);
    for (String storeId : financeRepository.storeIds()) {
      for (int i = 0; i < 4; i++) {
        YearMonth month = current.minusMonths(i);
        if (!financeRepository.entryExists(storeId, month.toString())) {
          financeRepository.upsert(fallback(storeId, month.toString()), null);
        }
      }
    }
  }

  private ProfitEntryRequest fallback(String storeId, String month) {
    int seed = Math.abs((storeId + "|" + month).hashCode());
    BigDecimal sales = BigDecimal.valueOf(120000L + seed % 520000).setScale(2, RoundingMode.HALF_UP);
    BigDecimal material = percent(sales, "0.33");
    BigDecimal packaging = percent(sales, "0.055");
    BigDecimal loss = percent(sales, seed % 7 == 0 ? "0.025" : "0.012");
    BigDecimal rent = seed % 3 == 0 ? percent(sales, "0.09") : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    BigDecimal labor = percent(sales, "0.155");
    BigDecimal utility = percent(sales, "0.018");
    BigDecimal commission = percent(sales, "0.036");
    BigDecimal expOther = percent(sales, seed % 11 == 0 ? "0.32" : "0.045");
    return new ProfitEntryRequest(
        storeId,
        month,
        sales,
        BigDecimal.ZERO,
        percent(sales, "0.012"),
        material,
        packaging,
        loss,
        BigDecimal.ZERO,
        rent,
        labor,
        utility,
        BigDecimal.ZERO,
        commission,
        percent(sales, "0.011"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        expOther,
        "系统阶段三样例数据"
    );
  }

  private BigDecimal firstAmount(JsonNode node, String first, String second) {
    BigDecimal value = amount(node, first);
    return value.compareTo(BigDecimal.ZERO) == 0 ? amount(node, second) : value;
  }

  private BigDecimal amount(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    try {
      if (value.isNumber()) {
        return value.decimalValue().setScale(2, RoundingMode.HALF_UP);
      }
      String text = value.asText("").replace(",", "").trim();
      return text.isBlank() ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
    } catch (Exception ex) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
  }

  private BigDecimal percent(BigDecimal value, String ratio) {
    return value.multiply(new BigDecimal(ratio)).setScale(2, RoundingMode.HALF_UP);
  }
}
