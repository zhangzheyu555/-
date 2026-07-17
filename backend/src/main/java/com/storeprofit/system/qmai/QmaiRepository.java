package com.storeprofit.system.qmai;

import static com.storeprofit.system.qmai.QmaiModels.DEFAULT_BRAND;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class QmaiRepository {
  private final JdbcTemplate jdbc;

  public QmaiRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public ConfigRow config(long tenantId) {
    return jdbc.query("""
        select enabled, display_name
        from qmai_platform_config
        where tenant_id = ? and brand_code = ?
        """, (rs, row) -> new ConfigRow(rs.getBoolean("enabled"), rs.getString("display_name")),
        tenantId, DEFAULT_BRAND).stream().findFirst().orElse(new ConfigRow(false, "企迈"));
  }

  @Transactional
  public void saveConfig(long tenantId, boolean enabled, String displayName, long userId,
      List<QmaiModels.MappingRequest> mappings) {
    jdbc.update("""
        insert into qmai_platform_config(tenant_id, brand_code, display_name, enabled, updated_by, updated_at)
        values (?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update display_name = values(display_name), enabled = values(enabled),
          updated_by = values(updated_by), updated_at = current_timestamp
        """, tenantId, DEFAULT_BRAND, displayName, enabled, userId);
    jdbc.update("delete from qmai_store_mapping where tenant_id = ? and brand_code = ?",
        tenantId, DEFAULT_BRAND);
    for (QmaiModels.MappingRequest mapping : mappings) {
      jdbc.update("""
          insert into qmai_store_mapping(
            tenant_id, brand_code, qmai_shop_id, qmai_shop_name, store_id, created_at, updated_at)
          values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """, tenantId, DEFAULT_BRAND, mapping.qmaiShopId(), mapping.qmaiShopName(), mapping.storeId());
    }
  }

  public List<QmaiModels.ShopMapping> mappings(long tenantId) {
    return jdbc.query("""
        select mapping.qmai_shop_id, mapping.qmai_shop_name, mapping.store_id, store.name as store_name
        from qmai_store_mapping mapping
        join store_branch store on store.id = mapping.store_id and store.tenant_id = mapping.tenant_id
        where mapping.tenant_id = ? and mapping.brand_code = ?
        order by store.code, store.id
        """, (rs, row) -> new QmaiModels.ShopMapping(
        rs.getString("qmai_shop_id"), rs.getString("qmai_shop_name"),
        rs.getString("store_id"), rs.getString("store_name")), tenantId, DEFAULT_BRAND);
  }

  public boolean storeExists(long tenantId, String storeId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from store_branch where tenant_id = ? and id = ?", Integer.class, tenantId, storeId);
    return count != null && count > 0;
  }

  public Optional<QmaiModels.BatchResponse> runningBatch(long tenantId, String month) {
    return jdbc.query("""
        select * from qmai_sync_batch
        where tenant_id = ? and brand_code = ? and target_month = ? and status in ('QUEUED', 'RUNNING')
        order by id desc limit 1
        """, this::mapBatch, tenantId, DEFAULT_BRAND, month).stream().findFirst();
  }

  public Optional<QmaiModels.BatchResponse> latestBatch(long tenantId, String month) {
    String sql = "select * from qmai_sync_batch where tenant_id = ? and brand_code = ?"
        + (month == null ? "" : " and target_month = ?") + " order by id desc limit 1";
    Object[] params = month == null
        ? new Object[] {tenantId, DEFAULT_BRAND}
        : new Object[] {tenantId, DEFAULT_BRAND, month};
    return jdbc.query(sql, this::mapBatch, params).stream().findFirst();
  }

  public long createBatch(long tenantId, String month, long userId, String userName, int totalTasks) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          insert into qmai_sync_batch(
            tenant_id, brand_code, target_month, status, requested_by, requested_by_name, total_tasks, created_at)
          values (?, ?, ?, 'QUEUED', ?, ?, ?, current_timestamp)
          """, Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1, tenantId);
      statement.setString(2, DEFAULT_BRAND);
      statement.setString(3, month);
      statement.setLong(4, userId);
      statement.setString(5, userName);
      statement.setInt(6, totalTasks);
      return statement;
    }, keys);
    Number key = keys.getKey();
    if (key == null) {
      throw new IllegalStateException("未取得企迈同步批次编号");
    }
    return key.longValue();
  }

  public void markRunning(long batchId) {
    jdbc.update("update qmai_sync_batch set status = 'RUNNING', started_at = current_timestamp where id = ?", batchId);
  }

  public void markProgress(long batchId, int completed, int failed, int dailyRows, int productRows) {
    jdbc.update("""
        update qmai_sync_batch
        set completed_tasks = ?, failed_tasks = ?, daily_rows = ?, product_rows = ?
        where id = ?
        """, completed, failed, dailyRows, productRows, batchId);
  }

  public void markFinished(long batchId, String status, String errorSummary) {
    jdbc.update("""
        update qmai_sync_batch
        set status = ?, error_summary = ?, finished_at = current_timestamp
        where id = ?
        """, status, errorSummary, batchId);
  }

  @Transactional
  public int replaceDay(long tenantId, long batchId, QmaiModels.DailySnapshot snapshot) {
    jdbc.update("""
        delete from qmai_product_sales
        where tenant_id = ? and brand_code = ? and qmai_shop_id = ? and business_date = ?
        """, tenantId, DEFAULT_BRAND, snapshot.qmaiShopId(), snapshot.businessDate());
    jdbc.update("""
        delete from qmai_daily_sales
        where tenant_id = ? and brand_code = ? and qmai_shop_id = ? and business_date = ?
        """, tenantId, DEFAULT_BRAND, snapshot.qmaiShopId(), snapshot.businessDate());
    jdbc.update("""
        insert into qmai_daily_sales(
          tenant_id, brand_code, qmai_shop_id, store_id, business_date, source_row_count,
          receivable_amount, received_amount, cost_amount, refund_amount, sync_batch_id, synced_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, DEFAULT_BRAND, snapshot.qmaiShopId(), snapshot.storeId(), snapshot.businessDate(),
        snapshot.sourceRows(), snapshot.receivable(), snapshot.received(), snapshot.cost(), snapshot.refund(), batchId);
    for (QmaiModels.ProductSnapshot product : snapshot.products()) {
      jdbc.update("""
          insert into qmai_product_sales(
            tenant_id, brand_code, qmai_shop_id, store_id, business_date, product_key,
            product_id, sku_id, item_name, category_name, quantity, refund_quantity,
            receivable_amount, received_amount, cost_amount, refund_amount, sync_batch_id, synced_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
          """, tenantId, DEFAULT_BRAND, snapshot.qmaiShopId(), snapshot.storeId(), snapshot.businessDate(),
          product.productKey(), blank(product.productId()), blank(product.skuId()), product.itemName(),
          blank(product.categoryName()), product.quantity(), product.refundQuantity(), product.receivable(),
          product.received(), product.cost(), product.refund(), batchId);
    }
    return snapshot.products().size();
  }

  public QmaiModels.SummaryResponse summary(long tenantId, YearMonth month, Collection<String> storeIds) {
    LocalDate from = month.atDay(1);
    LocalDate to = month.atEndOfMonth();
    ScopeSql scope = scope(storeIds, "sales.store_id");
    List<Object> params = new ArrayList<>(List.of(tenantId, DEFAULT_BRAND, from, to));
    params.addAll(scope.params());
    List<QmaiModels.StoreSummary> stores = jdbc.query("""
        select sales.store_id, store.name as store_name, count(distinct sales.business_date) active_days,
               sum(sales.source_row_count) source_rows,
               sum(sales.receivable_amount) receivable, sum(sales.received_amount) received,
               sum(sales.cost_amount) cost, sum(sales.refund_amount) refund,
               max(date_format(sales.synced_at, '%Y-%m-%d %H:%i:%s')) last_synced_at
        from qmai_daily_sales sales
        join store_branch store on store.id = sales.store_id and store.tenant_id = sales.tenant_id
        where sales.tenant_id = ? and sales.brand_code = ? and sales.business_date between ? and ?
        """ + scope.clause() + " group by sales.store_id, store.name order by received desc, store.name",
        this::mapStoreSummary, params.toArray());

    ScopeSql productScope = scope(storeIds, "sales.store_id");
    List<Object> productParams = new ArrayList<>(List.of(tenantId, DEFAULT_BRAND, from, to));
    productParams.addAll(productScope.params());
    List<QmaiModels.ProductSummary> products = jdbc.query("""
        select sales.store_id, store.name store_name, sales.product_key,
               max(sales.product_id) product_id, max(sales.sku_id) sku_id,
               max(sales.item_name) item_name, max(sales.category_name) category_name,
               sum(sales.quantity) quantity, sum(sales.refund_quantity) refund_quantity,
               sum(sales.receivable_amount) receivable, sum(sales.received_amount) received,
               sum(sales.cost_amount) cost, sum(sales.refund_amount) refund
        from qmai_product_sales sales
        join store_branch store on store.id = sales.store_id and store.tenant_id = sales.tenant_id
        where sales.tenant_id = ? and sales.brand_code = ? and sales.business_date between ? and ?
        """ + productScope.clause()
        + " group by sales.store_id, store.name, sales.product_key order by quantity desc, item_name",
        this::mapProductSummary, productParams.toArray());

    BigDecimal receivable = sum(stores, 0);
    BigDecimal received = sum(stores, 1);
    BigDecimal cost = sum(stores, 2);
    BigDecimal refund = sum(stores, 3);
    BigDecimal profit = received.subtract(cost);
    BigDecimal margin = received.signum() == 0 ? BigDecimal.ZERO
        : profit.divide(received, 4, RoundingMode.HALF_UP);
    List<Object> syncParams = new ArrayList<>(List.of(tenantId, DEFAULT_BRAND, from, to));
    syncParams.addAll(scope.params());
    String lastSynced = jdbc.query("""
        select max(date_format(synced_at, '%Y-%m-%d %H:%i:%s'))
        from qmai_daily_sales sales
        where tenant_id = ? and brand_code = ? and business_date between ? and ?
        """ + scope.clause(), rs -> rs.next() ? rs.getString(1) : null, syncParams.toArray());
    QmaiModels.BatchResponse batch = latestBatch(tenantId, month.toString()).orElse(null);
    return new QmaiModels.SummaryResponse(month.toString(), stores.isEmpty() ? "EMPTY" : "READY",
        lastSynced, receivable, received, cost, refund, profit, margin, stores, products, batch);
  }

  private QmaiModels.StoreSummary mapStoreSummary(ResultSet rs, int row) throws SQLException {
    BigDecimal received = amount(rs, "received");
    BigDecimal cost = amount(rs, "cost");
    BigDecimal profit = received.subtract(cost);
    BigDecimal margin = received.signum() == 0 ? BigDecimal.ZERO : profit.divide(received, 4, RoundingMode.HALF_UP);
    return new QmaiModels.StoreSummary(rs.getString("store_id"), rs.getString("store_name"),
        rs.getInt("active_days"), rs.getInt("source_rows"), amount(rs, "receivable"), received,
        cost, amount(rs, "refund"), profit, margin,
        received.signum() > 0 && margin.compareTo(new BigDecimal("0.4000")) < 0);
  }

  private QmaiModels.ProductSummary mapProductSummary(ResultSet rs, int row) throws SQLException {
    return new QmaiModels.ProductSummary(rs.getString("store_id"), rs.getString("store_name"),
        rs.getString("product_key"), rs.getString("product_id"), rs.getString("sku_id"),
        rs.getString("item_name"), rs.getString("category_name"), rs.getBigDecimal("quantity"),
        rs.getBigDecimal("refund_quantity"), amount(rs, "receivable"), amount(rs, "received"),
        amount(rs, "cost"), amount(rs, "refund"));
  }

  private QmaiModels.BatchResponse mapBatch(ResultSet rs, int row) throws SQLException {
    return new QmaiModels.BatchResponse(rs.getLong("id"), rs.getString("target_month"), rs.getString("status"),
        rs.getInt("total_tasks"), rs.getInt("completed_tasks"), rs.getInt("failed_tasks"),
        rs.getInt("daily_rows"), rs.getInt("product_rows"), rs.getString("error_summary"),
        rs.getString("requested_by_name"), text(rs, "created_at"), text(rs, "started_at"), text(rs, "finished_at"));
  }

  private String text(ResultSet rs, String column) throws SQLException {
    java.sql.Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toLocalDateTime().toString().replace('T', ' ');
  }

  private BigDecimal amount(ResultSet rs, String column) throws SQLException {
    BigDecimal value = rs.getBigDecimal(column);
    return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal sum(List<QmaiModels.StoreSummary> rows, int type) {
    return rows.stream().map(row -> switch (type) {
      case 0 -> row.receivable();
      case 1 -> row.received();
      case 2 -> row.cost();
      default -> row.refund();
    }).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private ScopeSql scope(Collection<String> storeIds, String column) {
    if (storeIds == null) {
      return new ScopeSql("", List.of());
    }
    List<String> normalized = storeIds.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    if (normalized.isEmpty()) {
      return new ScopeSql(" and 1 = 0", List.of());
    }
    return new ScopeSql(" and " + column + " in (" + String.join(",", java.util.Collections.nCopies(normalized.size(), "?")) + ")",
        new ArrayList<>(normalized));
  }

  private String blank(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record ConfigRow(boolean enabled, String displayName) {
  }

  private record ScopeSql(String clause, List<Object> params) {
  }
}
