package com.storeprofit.system.qmai;

import com.storeprofit.system.common.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Server-side, decimal-only recipe calculation. The result is an immutable request snapshot. */
@Service
public class QmaiRecipeCalculationService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(3);
  private static final BigDecimal MAX_VALUE = new BigDecimal("1000000");
  private static final BigDecimal GRAMS_PER_JIN = new BigDecimal("500");

  public CalculationSnapshot calculate(CalculationRequest request) {
    if (request == null || request.products() == null || request.products().isEmpty()) {
      throw new BusinessException("QMAI_RECIPE_EMPTY", "请至少提供一条配方销量", HttpStatus.BAD_REQUEST);
    }
    Map<String, MutableUsage> totals = new LinkedHashMap<>();
    BigDecimal cups = ZERO;
    for (ProductInput product : request.products()) {
      if (product == null || blank(product.productName()) || product.cups() == null
          || product.ingredients() == null || product.ingredients().isEmpty()) {
        throw new BusinessException("QMAI_RECIPE_INVALID", "配方名称、销量和原料不能为空", HttpStatus.BAD_REQUEST);
      }
      BigDecimal count = positive(product.cups(), "销量");
      cups = cups.add(count);
      for (IngredientInput ingredient : product.ingredients()) {
        if (ingredient == null || blank(ingredient.materialName()) || blank(ingredient.fruit())
            || ingredient.gramsPerCup() == null || blank(ingredient.kind())) {
          throw new BusinessException("QMAI_RECIPE_INVALID", "配方原料信息不完整", HttpStatus.BAD_REQUEST);
        }
        BigDecimal net = positive(ingredient.gramsPerCup(), "单杯用量").multiply(count);
        BigDecimal raw = switch (ingredient.kind().trim().toUpperCase()) {
          case "FLESH" -> net.divide(positiveFactor(ingredient.factor(), "出肉率"), 6, RoundingMode.HALF_UP);
          case "JUICE" -> net.multiply(positiveFactor(ingredient.factor(), "折算系数"));
          case "ONE" -> net;
          default -> throw new BusinessException("QMAI_RECIPE_KIND_INVALID", "配方原料类型无效", HttpStatus.BAD_REQUEST);
        };
        MutableUsage total = totals.computeIfAbsent(ingredient.fruit().trim(), ignored -> new MutableUsage());
        total.netGrams = total.netGrams.add(net);
        total.rawGrams = total.rawGrams.add(raw);
        total.approximate |= "ONE".equalsIgnoreCase(ingredient.kind().trim());
      }
    }
    List<FruitUsage> fruits = totals.entrySet().stream().map(entry -> new FruitUsage(
        entry.getKey(), scale(entry.getValue().netGrams), scale(entry.getValue().rawGrams),
        scale(entry.getValue().rawGrams.divide(GRAMS_PER_JIN, 6, RoundingMode.HALF_UP)),
        entry.getValue().approximate)).toList();
    return new CalculationSnapshot(scale(cups), fruits);
  }

  private BigDecimal positive(BigDecimal value, String field) {
    if (value == null || value.signum() < 0 || value.compareTo(MAX_VALUE) > 0) {
      throw new BusinessException("QMAI_RECIPE_VALUE_INVALID", field + "必须在 0 至 1000000 之间", HttpStatus.BAD_REQUEST);
    }
    return value;
  }

  private BigDecimal positiveFactor(BigDecimal value, String field) {
    BigDecimal normalized = positive(value, field);
    if (normalized.signum() == 0) {
      throw new BusinessException("QMAI_RECIPE_VALUE_INVALID", field + "必须大于 0", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private BigDecimal scale(BigDecimal value) {
    return value.setScale(3, RoundingMode.HALF_UP);
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static final class MutableUsage {
    private BigDecimal netGrams = ZERO;
    private BigDecimal rawGrams = ZERO;
    private boolean approximate;
  }

  public record CalculationRequest(List<ProductInput> products) {}
  public record ProductInput(String productName, BigDecimal cups, List<IngredientInput> ingredients) {}
  public record IngredientInput(String materialName, String fruit, BigDecimal gramsPerCup, String kind,
      BigDecimal factor) {}
  public record CalculationSnapshot(BigDecimal totalCups, List<FruitUsage> fruits) {}
  public record FruitUsage(String fruit, BigDecimal netGrams, BigDecimal rawGrams, BigDecimal rawJin,
      boolean approximate) {}
}
