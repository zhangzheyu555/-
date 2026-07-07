package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private final JdbcTemplate jdbcTemplate;

  public WarehouseRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<WarehouseItemResponse> items(long tenantId) {
    return jdbcTemplate.query("""
        select i.id, i.code, i.name, i.category, i.unit, i.spec, i.unit_price, i.shelf_life_days,
               i.cups_per_unit, i.daily_usage_estimate, i.min_stock_days, i.max_stock_days, i.active,
               coalesce(sum(case when b.quantity > 0 then b.quantity else 0 end), 0) as stock_quantity,
               min(case when b.quantity > 0 then date_format(b.expiry_date, '%Y-%m-%d') else null end) as nearest_expiry_date
        from warehouse_item i
        left join warehouse_stock_batch b on b.tenant_id = i.tenant_id and b.item_id = i.id
        where i.tenant_id = ?
        group by i.id, i.code, i.name, i.category, i.unit, i.spec, i.unit_price, i.shelf_life_days,
                 i.cups_per_unit, i.daily_usage_estimate, i.min_stock_days, i.max_stock_days, i.active
        order by i.active desc, i.category, i.code
        """, this::mapItem, tenantId);
  }

  public Optional<WarehouseItemResponse> item(long tenantId, long itemId) {
    return items(tenantId).stream().filter(item -> item.id().equals(itemId)).findFirst();
  }

  public boolean itemExists(long tenantId, long itemId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from warehouse_item where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        itemId
    );
    return count != null && count > 0;
  }

  public void upsertItem(long tenantId, WarehouseItemRequest request) {
    jdbcTemplate.update("""
        insert into warehouse_item(
          tenant_id, code, name, category, unit, spec, unit_price, shelf_life_days,
          cups_per_unit, daily_usage_estimate, min_stock_days, max_stock_days, active, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          name = values(name),
          category = values(category),
          unit = values(unit),
          spec = values(spec),
          unit_price = values(unit_price),
          shelf_life_days = values(shelf_life_days),
          cups_per_unit = values(cups_per_unit),
          daily_usage_estimate = values(daily_usage_estimate),
          min_stock_days = values(min_stock_days),
          max_stock_days = values(max_stock_days),
          active = values(active),
          updated_at = current_timestamp
        """,
        tenantId,
        request.code().trim(),
        request.name().trim(),
        blankToNull(request.category()),
        defaultText(request.unit(), "件"),
        blankToNull(request.spec()),
        amount(request.unitPrice()),
        request.shelfLifeDays(),
        amount(request.cupsPerUnit()),
        amount(request.dailyUsageEstimate()),
        positiveInt(request.minStockDays(), 7),
        positiveInt(request.maxStockDays(), 60),
        request.active() == null || request.active()
    );
  }

  public void upsertBatch(long tenantId, WarehouseStockBatchRequest request) {
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(
          tenant_id, item_id, batch_no, received_date, expiry_date, quantity, unit_cost, note, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          received_date = values(received_date),
          expiry_date = values(expiry_date),
          quantity = values(quantity),
          unit_cost = values(unit_cost),
          note = values(note),
          updated_at = current_timestamp
        """,
        tenantId,
        request.itemId(),
        request.batchNo().trim(),
        request.receivedDate(),
        blankToNull(request.expiryDate()),
        amount(request.quantity()),
        amount(request.unitCost()),
        blankToNull(request.note())
    );
  }

  public void insertMovement(
      long tenantId,
      long itemId,
      Long batchId,
      String movementType,
      BigDecimal quantityDelta,
      String sourceType,
      String sourceId,
      String storeId,
      String note,
      Long operatorId
  ) {
    jdbcTemplate.update("""
        insert into warehouse_stock_movement(
          tenant_id, item_id, batch_id, movement_type, quantity_delta,
          source_type, source_id, store_id, note, operator_id, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        itemId,
        batchId,
        movementType,
        quantityDelta.setScale(2, RoundingMode.HALF_UP),
        sourceType,
        sourceId,
        storeId,
        note,
        operatorId
    );
  }

  public boolean storeExists(long tenantId, String storeId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from store_branch where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        storeId
    );
    return count != null && count > 0;
  }

  public void insertRequisition(long tenantId, String id, String storeId, BigDecimal total, String note, Long submittedBy) {
    jdbcTemplate.update("""
        insert into store_requisition(
          id, tenant_id, store_id, status, total_amount, note, submitted_by, submitted_at
        )
        values (?, ?, ?, 'SUBMITTED', ?, ?, ?, current_timestamp)
        """,
        id,
        tenantId,
        storeId,
        total.setScale(2, RoundingMode.HALF_UP),
        blankToNull(note),
        submittedBy
    );
  }

  public void insertRequisitionLine(
      long tenantId,
      String requisitionId,
      long itemId,
      BigDecimal requestedQuantity,
      BigDecimal unitPrice,
      String warningText,
      String note
  ) {
    BigDecimal requested = amount(requestedQuantity);
    BigDecimal price = amount(unitPrice);
    jdbcTemplate.update("""
        insert into store_requisition_line(
          tenant_id, requisition_id, item_id, requested_quantity, unit_price, amount, warning_text, note
        )
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tenantId,
        requisitionId,
        itemId,
        requested,
        price,
        requested.multiply(price).setScale(2, RoundingMode.HALF_UP),
        blankToNull(warningText),
        blankToNull(note)
    );
  }

  public List<WarehouseRequisitionResponse> requisitions(long tenantId, String storeId) {
    List<WarehouseRequisitionHeaderRow> headers = jdbcTemplate.query("""
        select r.id, r.store_id, s.name as store_name, r.status, r.total_amount, r.note,
               sub.display_name as submitted_by, rev.display_name as reviewed_by, ship.display_name as shipped_by,
               r.submitted_at, r.reviewed_at, r.shipped_at
        from store_requisition r
        join store_branch s on s.tenant_id = r.tenant_id and s.id = r.store_id
        left join auth_user sub on sub.tenant_id = r.tenant_id and sub.id = r.submitted_by
        left join auth_user rev on rev.tenant_id = r.tenant_id and rev.id = r.reviewed_by
        left join auth_user ship on ship.tenant_id = r.tenant_id and ship.id = r.shipped_by
        where r.tenant_id = ?
          and (? is null or r.store_id = ?)
        order by r.submitted_at desc
        limit 80
        """, this::mapHeader, tenantId, blankToNull(storeId), blankToNull(storeId));
    ArrayList<WarehouseRequisitionResponse> rows = new ArrayList<>();
    for (WarehouseRequisitionHeaderRow header : headers) {
      rows.add(new WarehouseRequisitionResponse(
          header.id(),
          header.storeId(),
          header.storeName(),
          header.status(),
          statusLabel(header.status()),
          header.totalAmount(),
          header.note(),
          header.submittedBy(),
          header.reviewedBy(),
          header.shippedBy(),
          header.submittedAt(),
          header.reviewedAt(),
          header.shippedAt(),
          requisitionLines(tenantId, header.id())
      ));
    }
    return rows;
  }

  public Optional<WarehouseRequisitionResponse> requisition(long tenantId, String requisitionId) {
    return requisitions(tenantId, null).stream().filter(item -> item.id().equals(requisitionId)).findFirst();
  }

  public void reviewRequisition(
      long tenantId,
      String requisitionId,
      boolean approved,
      BigDecimal totalAmount,
      Long reviewedBy,
      String note
  ) {
    jdbcTemplate.update("""
        update store_requisition
        set status = ?,
            total_amount = ?,
            reviewed_by = ?,
            reviewed_at = current_timestamp,
            note = coalesce(?, note),
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """,
        approved ? "APPROVED" : "REJECTED",
        totalAmount.setScale(2, RoundingMode.HALF_UP),
        reviewedBy,
        blankToNull(note),
        tenantId,
        requisitionId
    );
  }

  public void updateApprovedQuantity(long tenantId, String requisitionId, long itemId, BigDecimal quantity) {
    BigDecimal approved = amount(quantity);
    jdbcTemplate.update("""
        update store_requisition_line
        set approved_quantity = ?,
            amount = ? * unit_price
        where tenant_id = ? and requisition_id = ? and item_id = ?
        """,
        approved,
        approved,
        tenantId,
        requisitionId,
        itemId
    );
  }

  public void markShipped(long tenantId, String requisitionId, Long shippedBy) {
    jdbcTemplate.update("""
        update store_requisition
        set status = 'SHIPPED',
            shipped_by = ?,
            shipped_at = current_timestamp,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, shippedBy, tenantId, requisitionId);
  }

  public void updateShippedQuantity(long tenantId, String requisitionId, long itemId, BigDecimal quantity) {
    jdbcTemplate.update("""
        update store_requisition_line
        set shipped_quantity = ?
        where tenant_id = ? and requisition_id = ? and item_id = ?
        """, amount(quantity), tenantId, requisitionId, itemId);
  }

  public List<WarehouseStockBatchRow> positiveBatches(long tenantId, long itemId) {
    return jdbcTemplate.query("""
        select id, item_id, batch_no, date_format(expiry_date, '%Y-%m-%d') as expiry_date, quantity
        from warehouse_stock_batch
        where tenant_id = ? and item_id = ? and quantity > 0
        order by case when expiry_date is null then 1 else 0 end, expiry_date, received_date, id
        """, this::mapBatch, tenantId, itemId);
  }

  public void updateBatchQuantity(long tenantId, long batchId, BigDecimal quantity) {
    jdbcTemplate.update("""
        update warehouse_stock_batch
        set quantity = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, amount(quantity), tenantId, batchId);
  }

  public int pendingRequisitionCount(long tenantId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from store_requisition
        where tenant_id = ? and status in ('SUBMITTED', 'APPROVED')
        """, Integer.class, tenantId);
    return count == null ? 0 : count;
  }

  public void logAction(long tenantId, Long operatorId, String operatorName, String action, String targetId, String storeId, String reason) {
    jdbcTemplate.update("""
        insert into operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, store_id, reason, created_at)
        values (?, ?, ?, ?, 'warehouse', ?, ?, ?, current_timestamp)
        """, tenantId, operatorId, operatorName, action, targetId, storeId, reason);
  }

  private List<WarehouseRequisitionLineResponse> requisitionLines(long tenantId, String requisitionId) {
    return jdbcTemplate.query("""
        select l.id, l.item_id, i.name as item_name, i.unit, l.requested_quantity,
               l.approved_quantity, l.shipped_quantity, l.unit_price, l.amount, l.warning_text, l.note
        from store_requisition_line l
        join warehouse_item i on i.tenant_id = l.tenant_id and i.id = l.item_id
        where l.tenant_id = ? and l.requisition_id = ?
        order by l.id
        """, this::mapLine, tenantId, requisitionId);
  }

  private WarehouseItemResponse mapItem(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal stock = amount(rs.getBigDecimal("stock_quantity"));
    BigDecimal unitPrice = amount(rs.getBigDecimal("unit_price"));
    BigDecimal dailyUsage = amount(rs.getBigDecimal("daily_usage_estimate"));
    BigDecimal stockValue = stock.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    BigDecimal daysAvailable = dailyUsage.compareTo(BigDecimal.ZERO) == 0
        ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
        : stock.divide(dailyUsage, 1, RoundingMode.HALF_UP);
    String nearestExpiry = rs.getString("nearest_expiry_date");
    AlertInfo alert = alert(stock, dailyUsage, rs.getInt("min_stock_days"), rs.getInt("max_stock_days"), nearestExpiry);
    return new WarehouseItemResponse(
        rs.getLong("id"),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("category"),
        rs.getString("unit"),
        rs.getString("spec"),
        unitPrice,
        (Integer) rs.getObject("shelf_life_days"),
        amount(rs.getBigDecimal("cups_per_unit")),
        dailyUsage,
        rs.getInt("min_stock_days"),
        rs.getInt("max_stock_days"),
        rs.getBoolean("active"),
        stock,
        stockValue,
        daysAvailable,
        nearestExpiry,
        alert.level(),
        alert.text()
    );
  }

  private WarehouseRequisitionHeaderRow mapHeader(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseRequisitionHeaderRow(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("status"),
        amount(rs.getBigDecimal("total_amount")),
        rs.getString("note"),
        rs.getString("submitted_by"),
        rs.getString("reviewed_by"),
        rs.getString("shipped_by"),
        formatDateTime(rs.getObject("submitted_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("reviewed_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("shipped_at", LocalDateTime.class))
    );
  }

  private WarehouseRequisitionLineResponse mapLine(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseRequisitionLineResponse(
        rs.getLong("id"),
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("unit"),
        amount(rs.getBigDecimal("requested_quantity")),
        amount(rs.getBigDecimal("approved_quantity")),
        amount(rs.getBigDecimal("shipped_quantity")),
        amount(rs.getBigDecimal("unit_price")),
        amount(rs.getBigDecimal("amount")),
        rs.getString("warning_text"),
        rs.getString("note")
    );
  }

  private WarehouseStockBatchRow mapBatch(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseStockBatchRow(
        rs.getLong("id"),
        rs.getLong("item_id"),
        rs.getString("batch_no"),
        rs.getString("expiry_date"),
        amount(rs.getBigDecimal("quantity"))
    );
  }

  private AlertInfo alert(BigDecimal stock, BigDecimal dailyUsage, int minDays, int maxDays, String nearestExpiry) {
    if (nearestExpiry != null && !nearestExpiry.isBlank()) {
      java.time.LocalDate expiry = java.time.LocalDate.parse(nearestExpiry);
      long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), expiry);
      if (days <= 7) {
        return new AlertInfo("EXPIRING", "临期风险，最近批次 " + nearestExpiry + " 到期");
      }
    }
    if (dailyUsage.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal min = dailyUsage.multiply(BigDecimal.valueOf(minDays));
      BigDecimal max = dailyUsage.multiply(BigDecimal.valueOf(maxDays));
      if (stock.compareTo(min) <= 0) {
        return new AlertInfo("LOW", "库存偏低，预计可用天数不足 " + minDays + " 天");
      }
      if (stock.compareTo(max) > 0) {
        return new AlertInfo("OVERSTOCK", "库存偏多，存在积压或过期风险");
      }
    }
    return new AlertInfo("OK", "正常");
  }

  private String statusLabel(String status) {
    return switch (status) {
      case "SUBMITTED" -> "待审核";
      case "APPROVED" -> "待配货";
      case "SHIPPED" -> "已配货";
      case "REJECTED" -> "已驳回";
      default -> status;
    };
  }

  private String formatDateTime(LocalDateTime value) {
    return value == null ? null : value.format(DATE_TIME_FORMAT);
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String defaultText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private int positiveInt(Integer value, int fallback) {
    return value == null || value <= 0 ? fallback : value;
  }

  private record WarehouseRequisitionHeaderRow(
      String id,
      String storeId,
      String storeName,
      String status,
      BigDecimal totalAmount,
      String note,
      String submittedBy,
      String reviewedBy,
      String shippedBy,
      String submittedAt,
      String reviewedAt,
      String shippedAt
  ) {
  }

  private record AlertInfo(String level, String text) {
  }
}
