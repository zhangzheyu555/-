package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Reads server-owned recipes only; no browser-provided grams or conversion factors are trusted. */
@Repository
public class QmaiRecipeCatalogRepository {
  private final JdbcTemplate jdbcTemplate;

  public QmaiRecipeCatalogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<String, List<QmaiRecipeCalculationService.IngredientInput>> activeRecipes(long tenantId, String brand) {
    Map<String, List<QmaiRecipeCalculationService.IngredientInput>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select d.product_name, i.material_name, i.fruit_name, i.grams_per_cup,
               i.conversion_kind, i.conversion_factor
        from qmai_recipe_definition d
        join qmai_recipe_ingredient i on i.recipe_id = d.id
        where d.tenant_id = ? and d.brand_code = ? and d.active = 1
        order by d.product_name, i.sort_order, i.id
        """, (RowCallbackHandler) rs -> result.computeIfAbsent(rs.getString("product_name"), ignored -> new java.util.ArrayList<>()).add(
            new QmaiRecipeCalculationService.IngredientInput(rs.getString("material_name"),
                rs.getString("fruit_name"), rs.getBigDecimal("grams_per_cup"),
                rs.getString("conversion_kind"), rs.getBigDecimal("conversion_factor"))), tenantId, brand);
    return result;
  }
}
