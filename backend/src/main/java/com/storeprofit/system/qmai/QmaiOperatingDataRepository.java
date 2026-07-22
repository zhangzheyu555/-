package com.storeprofit.system.qmai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads only normalized QMAI snapshots; it never calls an external platform. */
@Repository
public class QmaiOperatingDataRepository {
  private final JdbcTemplate jdbcTemplate;

  public QmaiOperatingDataRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<RevenueRow> revenue(long tenantId, String brand, LocalDate from, LocalDate to,
      Collection<String> allowedStoreIds) {
    Sql sql = scopeSql("""
        select store_id, sum(source_row_count) as order_count, sum(received_amount) as revenue,
               sum(refund_amount) as refund, sum(cost_amount) as cost
        from qmai_daily_sales
        where tenant_id = ? and brand_code = ? and business_date between ? and ?
        """, tenantId, brand, from, to, allowedStoreIds);
    return jdbcTemplate.query(sql.text() + " group by store_id order by store_id", (rs, row) -> new RevenueRow(
        rs.getString("store_id"), rs.getLong("order_count"), money(rs.getBigDecimal("revenue")),
        money(rs.getBigDecimal("refund")), money(rs.getBigDecimal("cost"))), sql.args().toArray());
  }

  public List<ProductRow> products(long tenantId, String brand, LocalDate from, LocalDate to,
      Collection<String> allowedStoreIds) {
    Sql sql = scopeSql("""
        select store_id, item_name, coalesce(category_name, '') as category_name,
               sum(quantity) as quantity, sum(refund_quantity) as refund_quantity,
               sum(received_amount) as revenue, sum(refund_amount) as refund
        from qmai_product_sales
        where tenant_id = ? and brand_code = ? and business_date between ? and ?
        """, tenantId, brand, from, to, allowedStoreIds);
    return jdbcTemplate.query(sql.text() + " group by store_id, item_name, category_name order by store_id, item_name",
        (rs, row) -> new ProductRow(rs.getString("store_id"), rs.getString("item_name"),
            rs.getString("category_name"), qty(rs.getBigDecimal("quantity")),
            qty(rs.getBigDecimal("refund_quantity")), money(rs.getBigDecimal("revenue")),
            money(rs.getBigDecimal("refund"))), sql.args().toArray());
  }

  private Sql scopeSql(String base, long tenantId, String brand, LocalDate from, LocalDate to,
      Collection<String> allowedStoreIds) {
    List<Object> args = new ArrayList<>(List.of(tenantId, brand, from, to));
    StringBuilder text = new StringBuilder(base);
    if (allowedStoreIds != null) {
      if (allowedStoreIds.isEmpty()) {
        text.append(" and 1 = 0");
      } else {
        text.append(" and store_id in (");
        text.append(String.join(",", Collections.nCopies(allowedStoreIds.size(), "?")));
        text.append(")");
        args.addAll(allowedStoreIds);
      }
    }
    return new Sql(text.toString(), args);
  }

  private BigDecimal money(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal qty(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.setScale(3, RoundingMode.HALF_UP);
  }

  private record Sql(String text, List<Object> args) {}
  public record RevenueRow(String storeId, long orderCount, BigDecimal revenue, BigDecimal refund, BigDecimal cost) {}
  public record ProductRow(String storeId, String itemName, String categoryName, BigDecimal quantity,
      BigDecimal refundQuantity, BigDecimal revenue, BigDecimal refund) {}
}
