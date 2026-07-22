package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Builds auditable BigDecimal snapshots from local sales and server-owned recipe definitions. */
@Service
public class QmaiRecipeSnapshotService {
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
    List<QmaiRecipeCalculationService.ProductInput> products = operatingDataRepository.products(
        tenantId, normalizedBrand, parsed.atDay(1), parsed.atEndOfMonth(), storeIds).stream()
        .filter(row -> recipes.containsKey(row.itemName()))
        .map(row -> new QmaiRecipeCalculationService.ProductInput(row.itemName(), row.quantity(), recipes.get(row.itemName())))
        .toList();
    if (products.isEmpty()) {
      return new Snapshot(month, new QmaiRecipeCalculationService.CalculationSnapshot(BigDecimal.ZERO.setScale(3), List.of()), 0);
    }
    return new Snapshot(month, calculationService.calculate(
        new QmaiRecipeCalculationService.CalculationRequest(products)), products.size());
  }

  public record Snapshot(String month, QmaiRecipeCalculationService.CalculationSnapshot calculation, int matchedProductCount) {}
}
