package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Builds auditable BigDecimal snapshots from local sales and server-owned recipe definitions. */
@Service
public class QmaiRecipeSnapshotService {
  private static final Pattern OTHER_PATTERN = Pattern.compile(
      "费|打包袋|吸管|餐具|杯套|贴纸|礼盒|预定|预订|预售|下个单|重新做|时令之选|零添加|加料|爆珠");
  private static final Set<String> OTHER_NAMES = Set.of(
      "蒟蒻", "西米", "椰果", "麻薯", "米麻薯", "茶冻", "茉莉茶冻", "奶盖",
      "珍珠", "波霸", "芋圆", "布丁", "仙草", "脆啵啵", "红豆", "芋泥", "奶油顶",
      "小胡鸭", "原切雪花牛肉干", "龙泉驿夏之梦水蜜桃", "关于水果");
  private static final Pattern SPECIFICATION_PATTERN = Pattern.compile(
      "[（(](?:中杯|大杯|中|大|\\d{3,4}\\s*ml)[)）]",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern NORMALIZE_PATTERN = Pattern.compile(
      "[\\s`·.。,+＋，、()（）【】\\[\\]_-]");
  private static final Pattern TRAILING_SPECIFICATION_PATTERN = Pattern.compile(
      "(?:中杯|大杯|中|大|\\d{3,4}ml)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern CONNECTOR_PATTERN = Pattern.compile("[的和与]");
  private static final Map<String, String> PRODUCT_ALIASES = Map.of(
      "牛油果追芒芒", "牛油果芒果",
      "牛油果追火龙果", "牛油果火龙果",
      "奇亚籽羽衣甘蓝牛油果", "羽衣甘蓝牛油果",
      "大颗粒芒果冰茶", "大颗芒果冰茶",
      "茉香芝芝芒芒", "茉香芝士芒芒",
      "超多芒果酸奶冰", "超多芒芒酸奶冰");

  private final QmaiOperatingDataRepository operatingDataRepository;
  private final QmaiRecipeCatalogRepository recipeCatalogRepository;
  private final QmaiRecipeCalculationService calculationService;

  public QmaiRecipeSnapshotService(QmaiOperatingDataRepository operatingDataRepository,
      QmaiRecipeCatalogRepository recipeCatalogRepository, QmaiRecipeCalculationService calculationService) {
    this.operatingDataRepository = operatingDataRepository;
    this.recipeCatalogRepository = recipeCatalogRepository;
    this.calculationService = calculationService;
  }

  public Snapshot monthly(long tenantId, String brand, String month, Collection<String> storeIds) {
    YearMonth parsed = YearMonth.parse(month);
    String normalizedBrand = QmaiConfigService.normBrand(brand);
    Map<String, List<QmaiRecipeCalculationService.IngredientInput>> recipes =
        recipeCatalogRepository.activeRecipes(tenantId, normalizedBrand);
    Map<String, BigDecimal> sold = new LinkedHashMap<>();
    for (QmaiOperatingDataRepository.ProductRow row : operatingDataRepository.products(
        tenantId, normalizedBrand, parsed.atDay(1), parsed.atEndOfMonth(), storeIds)) {
      if (isDrink(row.itemName())) {
        sold.merge(row.itemName(), row.quantity(), BigDecimal::add);
      }
    }

    RecipeIndex index = recipeIndex(recipes);
    Map<String, BigDecimal> cupsByRecipe = new LinkedHashMap<>();
    List<UnmatchedProduct> unmatchedProducts = new java.util.ArrayList<>();
    int matchedProductCount = 0;
    for (Map.Entry<String, BigDecimal> entry : sold.entrySet()) {
      if (entry.getValue() == null || entry.getValue().signum() <= 0) {
        continue;
      }
      String targetRecipe = index.match(entry.getKey());
      if (targetRecipe == null) {
        unmatchedProducts.add(new UnmatchedProduct(entry.getKey(), entry.getValue()));
        continue;
      }
      cupsByRecipe.merge(targetRecipe, entry.getValue(), BigDecimal::add);
      matchedProductCount++;
    }
    unmatchedProducts.sort((left, right) -> {
      int amount = right.cups().compareTo(left.cups());
      return amount != 0 ? amount : left.name().compareTo(right.name());
    });

    List<QmaiRecipeCalculationService.ProductInput> products = cupsByRecipe.entrySet().stream()
        .map(entry -> new QmaiRecipeCalculationService.ProductInput(
            entry.getKey(), entry.getValue(), recipes.get(entry.getKey())))
        .toList();
    List<MatchedProduct> matchedProducts = cupsByRecipe.entrySet().stream()
        .map(entry -> new MatchedProduct(entry.getKey(), entry.getValue()))
        .toList();
    if (products.isEmpty()) {
      return new Snapshot(month,
          new QmaiRecipeCalculationService.CalculationSnapshot(
              BigDecimal.ZERO.setScale(3), List.of(), List.of()),
          0, List.of(), List.copyOf(unmatchedProducts));
    }
    return new Snapshot(month, calculationService.calculate(
        new QmaiRecipeCalculationService.CalculationRequest(products)),
        matchedProductCount, matchedProducts, List.copyOf(unmatchedProducts));
  }

  private RecipeIndex recipeIndex(
      Map<String, List<QmaiRecipeCalculationService.IngredientInput>> recipes) {
    Map<String, String> byCanonicalName = new LinkedHashMap<>();
    for (String recipeName : recipes.keySet()) {
      byCanonicalName.putIfAbsent(canonicalProductName(recipeName), recipeName);
    }
    return new RecipeIndex(byCanonicalName);
  }

  private boolean isDrink(String productName) {
    return productName != null
        && !OTHER_PATTERN.matcher(productName).find()
        && !OTHER_NAMES.contains(productName);
  }

  private static String canonicalProductName(String value) {
    String normalized = SPECIFICATION_PATTERN.matcher(value == null ? "" : value).replaceAll("");
    normalized = NORMALIZE_PATTERN.matcher(normalized).replaceAll("");
    normalized = TRAILING_SPECIFICATION_PATTERN.matcher(normalized).replaceFirst("");
    if (normalized.startsWith("茹菓")) {
      normalized = normalized.substring(2);
    }
    if (normalized.endsWith("默认")) {
      normalized = normalized.substring(0, normalized.length() - 2);
    }
    normalized = CONNECTOR_PATTERN.matcher(normalized).replaceAll("");
    return PRODUCT_ALIASES.getOrDefault(normalized, normalized);
  }

  private static final class RecipeIndex {
    private final Map<String, String> byCanonicalName;

    private RecipeIndex(Map<String, String> byCanonicalName) {
      this.byCanonicalName = byCanonicalName;
    }

    private String match(String productName) {
      return byCanonicalName.get(canonicalProductName(productName));
    }
  }

  public record Snapshot(
      String month,
      QmaiRecipeCalculationService.CalculationSnapshot calculation,
      int matchedProductCount,
      List<MatchedProduct> matchedProducts,
      List<UnmatchedProduct> unmatchedProducts
  ) {
    /** Backward-compatible constructor for controller tests and internal callers. */
    public Snapshot(String month, QmaiRecipeCalculationService.CalculationSnapshot calculation,
        int matchedProductCount) {
      this(month, calculation, matchedProductCount, List.of(), List.of());
    }
  }

  public record MatchedProduct(String recipeName, BigDecimal cups) {}
  public record UnmatchedProduct(String name, BigDecimal cups) {}
}
