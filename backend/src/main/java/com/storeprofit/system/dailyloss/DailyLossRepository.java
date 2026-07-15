package com.storeprofit.system.dailyloss;

import com.storeprofit.system.platform.authorization.DataScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DailyLossRepository {
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbc;

  public DailyLossRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbc) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbc = namedJdbc;
  }

  public List<DailyLossItemResponse> activeItems(long tenantId) {
    return jdbcTemplate.query("""
        select id, code, name, coalesce(stock_unit, unit, '件') as stock_unit, unit_price
        from warehouse_item
        where tenant_id = ? and active = 1
        order by sort_order, code, id
        """, (rs, rowNum) -> new DailyLossItemResponse(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("stock_unit"), rs.getBigDecimal("unit_price")), tenantId);
  }

  public Optional<LossItemRow> activeItem(long tenantId, long itemId) {
    return jdbcTemplate.query("""
        select id, code, name, coalesce(stock_unit, unit, '件') as stock_unit, unit_price
        from warehouse_item where tenant_id = ? and id = ? and active = 1
        """, (rs, rowNum) -> new LossItemRow(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("stock_unit"), rs.getBigDecimal("unit_price")),
        tenantId, itemId).stream().findFirst();
  }

  public boolean storeExists(long tenantId, String storeId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from store_branch where tenant_id = ? and id = ?", Integer.class, tenantId, storeId);
    return count != null && count > 0;
  }

  public void insert(long tenantId, String id, DailyLossCreateRequest request, LossItemRow item,
      BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount, long submittedBy) {
    jdbcTemplate.update("""
        insert into daily_loss_record(
          id, tenant_id, store_id, loss_date, item_id, item_code, item_name, stock_unit,
          loss_quantity, unit_price_snapshot, amount_snapshot, loss_reason, status,
          submitted_by, submitted_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, current_timestamp)
        """, id, tenantId, request.storeId().trim(), request.lossDate(), item.id(), item.code(), item.name(),
        item.stockUnit(), quantity, unitPrice, amount, request.lossReason().trim(), submittedBy);
  }

  public List<DailyLossRow> list(long tenantId, LocalDate day, LocalDate monthStart,
      LocalDate monthEnd, String storeId, DataScope scope) {
    StringBuilder sql = new StringBuilder(baseSelect()).append(" where r.tenant_id = :tenantId");
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (day != null) { sql.append(" and r.loss_date = :day"); params.addValue("day", day); }
    if (monthStart != null) {
      sql.append(" and r.loss_date >= :monthStart and r.loss_date < :monthEnd");
      params.addValue("monthStart", monthStart).addValue("monthEnd", monthEnd);
    }
    if (storeId != null && !storeId.isBlank()) { sql.append(" and r.store_id = :storeId"); params.addValue("storeId", storeId.trim()); }
    appendStoreScope(sql, params, scope);
    sql.append(" order by r.loss_date desc, r.submitted_at desc, r.id desc");
    return namedJdbc.query(sql.toString(), params, rowMapper());
  }

  public Optional<DailyLossRow> find(long tenantId, String id) {
    return jdbcTemplate.query(baseSelect() + " where r.tenant_id = ? and r.id = ?", rowMapper(), tenantId, id)
        .stream().findFirst();
  }

  public Optional<LockedLossRow> findForUpdate(long tenantId, String id) {
    return jdbcTemplate.query("""
        select id, store_id, item_id, loss_quantity, loss_reason, status
        from daily_loss_record where tenant_id = ? and id = ? for update
        """, (rs, rowNum) -> new LockedLossRow(rs.getString("id"), rs.getString("store_id"),
            rs.getLong("item_id"), rs.getBigDecimal("loss_quantity"), rs.getString("loss_reason"),
            rs.getString("status")), tenantId, id).stream().findFirst();
  }

  public boolean inventoryApplicationExists(long tenantId, String lossId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from daily_loss_inventory_application where tenant_id = ? and daily_loss_id = ?
        """, Integer.class, tenantId, lossId);
    return count != null && count > 0;
  }

  public boolean insertInventoryApplication(long tenantId, LockedLossRow row, long actorId) {
    try {
      jdbcTemplate.update("""
          insert into daily_loss_inventory_application(
            tenant_id, daily_loss_id, store_id, item_id, quantity, movement_type, applied_by, applied_at
          ) values (?, ?, ?, ?, ?, 'LOSS_OUT', ?, current_timestamp)
          """, tenantId, row.id(), row.storeId(), row.itemId(), row.lossQuantity(), actorId);
      return true;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }

  public void markApproved(long tenantId, String id, long reviewerId, String reviewNote) {
    jdbcTemplate.update("""
        update daily_loss_record set status = 'APPROVED', reviewed_by = ?, reviewed_at = current_timestamp,
        review_note = ?, updated_at = current_timestamp where tenant_id = ? and id = ?
        """, reviewerId, blankToNull(reviewNote), tenantId, id);
  }

  public void markRejected(long tenantId, String id, long reviewerId, String reviewNote) {
    jdbcTemplate.update("""
        update daily_loss_record set status = 'REJECTED', reviewed_by = ?, reviewed_at = current_timestamp,
        review_note = ?, updated_at = current_timestamp where tenant_id = ? and id = ?
        """, reviewerId, blankToNull(reviewNote), tenantId, id);
  }

  public List<DailyLossAttachmentResponse> attachments(long tenantId, String lossId) {
    return jdbcTemplate.query("""
        select id, file_name, content_type, file_size from warehouse_attachment
        where tenant_id = ? and business_type = 'DAILY_LOSS' and business_id = ?
        order by uploaded_at, id
        """, (rs, rowNum) -> new DailyLossAttachmentResponse(rs.getLong("id"), rs.getString("file_name"),
            rs.getString("content_type"), rs.getLong("file_size"), "/api/storage/attachments/" + rs.getLong("id")),
        tenantId, lossId);
  }

  private String baseSelect() {
    return """
        select r.id, r.store_id, s.code as store_code, s.name as store_name, r.loss_date,
               r.item_id, r.item_code, r.item_name, r.stock_unit, r.loss_quantity,
               r.unit_price_snapshot, r.amount_snapshot, r.loss_reason, r.status,
               r.submitted_by, submitter.display_name as submitted_by_name, r.submitted_at,
               r.reviewed_by, reviewer.display_name as reviewed_by_name, r.reviewed_at, r.review_note
        from daily_loss_record r
        join store_branch s on s.tenant_id = r.tenant_id and s.id = r.store_id
        left join auth_user submitter on submitter.tenant_id = r.tenant_id and submitter.id = r.submitted_by
        left join auth_user reviewer on reviewer.tenant_id = r.tenant_id and reviewer.id = r.reviewed_by
        """;
  }

  private RowMapper<DailyLossRow> rowMapper() {
    return this::mapRow;
  }

  private DailyLossRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new DailyLossRow(rs.getString("id"), rs.getString("store_id"), rs.getString("store_code"),
        rs.getString("store_name"), rs.getObject("loss_date", LocalDate.class), rs.getLong("item_id"),
        rs.getString("item_code"), rs.getString("item_name"), rs.getString("stock_unit"),
        rs.getBigDecimal("loss_quantity"), rs.getBigDecimal("unit_price_snapshot"),
        rs.getBigDecimal("amount_snapshot"), rs.getString("loss_reason"), rs.getString("status"),
        rs.getObject("submitted_by", Long.class), rs.getString("submitted_by_name"),
        rs.getObject("submitted_at", LocalDateTime.class), rs.getObject("reviewed_by", Long.class),
        rs.getString("reviewed_by_name"), rs.getObject("reviewed_at", LocalDateTime.class),
        rs.getString("review_note"));
  }

  private void appendStoreScope(StringBuilder sql, MapSqlParameterSource params, DataScope scope) {
    if (scope == null || scope.allowsAllStores()) return;
    if (scope.warehouseIds() != null && !scope.warehouseIds().isEmpty()) {
      sql.append(" and s.supply_warehouse_id in (:scopeWarehouseIds)");
      params.addValue("scopeWarehouseIds", scope.warehouseIds());
      return;
    }
    if (scope.deniesStoreAccess() || scope.storeIds().isEmpty()) { sql.append(" and 1 = 0"); return; }
    sql.append(" and r.store_id in (:scopeStoreIds)");
    params.addValue("scopeStoreIds", scope.storeIds());
  }

  public boolean storeInWarehouseScope(long tenantId, String storeId, List<String> warehouseIds) {
    if (warehouseIds == null || warehouseIds.isEmpty()) {
      return false;
    }
    Integer count = namedJdbc.queryForObject("""
        select count(*)
        from store_branch
        where tenant_id = :tenantId and id = :storeId
          and cast(supply_warehouse_id as char) in (:warehouseIds)
        """, new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("storeId", storeId)
        .addValue("warehouseIds", warehouseIds), Integer.class);
    return count != null && count > 0;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record LossItemRow(long id, String code, String name, String stockUnit, BigDecimal unitPrice) {}
  public record LockedLossRow(String id, String storeId, long itemId, BigDecimal lossQuantity, String lossReason,
                              String status) {}
  public record DailyLossRow(String id, String storeId, String storeCode, String storeName, LocalDate lossDate,
      long itemId, String itemCode, String itemName, String stockUnit, BigDecimal lossQuantity,
      BigDecimal unitPriceSnapshot, BigDecimal amountSnapshot, String lossReason, String status,
      Long submittedBy, String submittedByName, LocalDateTime submittedAt, Long reviewedBy,
      String reviewedByName, LocalDateTime reviewedAt, String reviewNote) {}
}
