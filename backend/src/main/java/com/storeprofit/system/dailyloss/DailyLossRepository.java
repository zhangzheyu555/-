package com.storeprofit.system.dailyloss;

import com.storeprofit.system.platform.authorization.DataScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collection;
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
        select config.id, config.item_code, config.item_name, config.category,
               category.id as warehouse_category_id,
               category.name as warehouse_category_name,
               config.unit, config.unit_price, config.active
        from loss_item_config config
        left join warehouse_item_category category
          on category.tenant_id = config.tenant_id
         and category.id = config.warehouse_category_id
         and category.enabled = 1
        where config.tenant_id = ? and config.active = 1
        order by coalesce(category.sort_order, 999), coalesce(category.name, config.category),
                 config.source_sheet, config.item_code, config.id
        """, (rs, rowNum) -> {
          Long warehouseCategoryId = rs.getObject("warehouse_category_id", Long.class);
          String sourceCategory = rs.getString("category");
          String warehouseCategoryName = rs.getString("warehouse_category_name");
          return new DailyLossItemResponse(
              rs.getLong("id"),
              rs.getString("item_code"),
              rs.getString("item_name"),
              sourceCategory,
              dailyLossCategoryCode(warehouseCategoryId, sourceCategory),
              dailyLossCategoryName(warehouseCategoryName, sourceCategory),
              rs.getString("unit"),
              rs.getBigDecimal("unit_price"),
              rs.getBoolean("active"));
        }, tenantId);
  }

  public Optional<LossItemRow> activeItem(long tenantId, long itemId) {
    return jdbcTemplate.query("""
        select id, code, name, coalesce(stock_unit, unit, '件') as stock_unit, unit_price
        from warehouse_item where tenant_id = ? and id = ? and active = 1
        """, (rs, rowNum) -> new LossItemRow(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("stock_unit"), rs.getBigDecimal("unit_price")),
        tenantId, itemId).stream().findFirst();
  }

  public Optional<LossItemConfigRow> activeItemConfig(long tenantId, long itemConfigId) {
    return jdbcTemplate.query("""
        select id, item_code, item_name, category, unit, unit_price
        from loss_item_config
        where tenant_id = ? and id = ? and active = 1
        """, (rs, rowNum) -> new LossItemConfigRow(
            rs.getLong("id"),
            rs.getString("item_code"),
            rs.getString("item_name"),
            rs.getString("category"),
            rs.getString("unit"),
            rs.getBigDecimal("unit_price")),
        tenantId, itemConfigId).stream().findFirst();
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

  public Optional<DailyLossReportRow> findReportByStoreAndDate(long tenantId, String storeId, LocalDate lossDate) {
    return jdbcTemplate.query(reportSelect() + """
        where r.tenant_id = ? and r.store_id = ? and r.loss_date = ?
        """, reportMapper(), tenantId, storeId, lossDate).stream().findFirst();
  }

  public Optional<DailyLossReportRow> findReport(long tenantId, String id) {
    return jdbcTemplate.query(reportSelect() + " where r.tenant_id = ? and r.id = ?",
        reportMapper(), tenantId, id).stream().findFirst();
  }

  public Optional<DailyLossReportRow> findReportForUpdate(long tenantId, String id) {
    return jdbcTemplate.query("""
        select r.id, r.store_id, s.code as store_code, s.name as store_name, r.loss_date,
               r.status, r.submitted_by, submitter.display_name as submitted_by_name, r.submitted_at,
               r.reviewed_by, reviewer.display_name as reviewed_by_name, r.reviewed_at, r.review_note
        from daily_loss_report r
        join store_branch s on s.tenant_id = r.tenant_id and s.id = r.store_id
        left join auth_user submitter on submitter.tenant_id = r.tenant_id and submitter.id = r.submitted_by
        left join auth_user reviewer on reviewer.tenant_id = r.tenant_id and reviewer.id = r.reviewed_by
        where r.tenant_id = ? and r.id = ?
        for update
        """, reportMapper(), tenantId, id).stream().findFirst();
  }

  public List<DailyLossReportRow> reports(long tenantId, YearMonth month, String storeId, DataScope scope) {
    LocalDate start = month.atDay(1);
    LocalDate end = month.plusMonths(1).atDay(1);
    StringBuilder sql = new StringBuilder(reportSelect()).append("""
        where r.tenant_id = :tenantId
          and r.loss_date >= :start
          and r.loss_date < :end
        """);
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("start", start)
        .addValue("end", end);
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and r.store_id = :storeId");
      params.addValue("storeId", storeId.trim());
    }
    appendStoreScope(sql, params, scope, "r.store_id");
    sql.append(" order by r.loss_date desc, s.code, r.id");
    return namedJdbc.query(sql.toString(), params, reportMapper());
  }

  public List<ReportStoreRow> reportStores(long tenantId, String storeId, DataScope scope) {
    StringBuilder sql = new StringBuilder("""
        select s.id, s.code, s.name
        from store_branch s
        where s.tenant_id = :tenantId
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and s.id = :storeId");
      params.addValue("storeId", storeId.trim());
    }
    appendStoreScope(sql, params, scope, "s.id");
    sql.append(" order by s.code, s.id");
    return namedJdbc.query(sql.toString(), params,
        (rs, rowNum) -> new ReportStoreRow(rs.getString("id"), rs.getString("code"), rs.getString("name")));
  }

  public void insertReport(long tenantId, String id, String storeId, LocalDate lossDate, long actorId) {
    jdbcTemplate.update("""
        insert into daily_loss_report(
          id, tenant_id, store_id, loss_date, status, created_at
        ) values (?, ?, ?, ?, 'DRAFT', current_timestamp)
        """, id, tenantId, storeId, lossDate);
  }

  public void resetReportDetails(long tenantId, String reportId) {
    jdbcTemplate.update("""
        delete from daily_loss_record
        where tenant_id = ? and report_id = ?
        """, tenantId, reportId);
  }

  public void insertReportDetail(
      long tenantId,
      String id,
      String reportId,
      String storeId,
      LocalDate lossDate,
      LossItemConfigRow item,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      String reason,
      long actorId
  ) {
    jdbcTemplate.update("""
        insert into daily_loss_record(
          id, report_id, tenant_id, store_id, loss_date, item_id, item_config_id,
          item_code, item_name, stock_unit, loss_quantity, unit_price_snapshot,
          amount_snapshot, loss_reason, status, submitted_by, submitted_at
        ) values (?, ?, ?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, current_timestamp)
        """, id, reportId, tenantId, storeId, lossDate, item.id(), item.itemCode(), item.itemName(),
        item.unit(), quantity, unitPrice, amount, reason, actorId);
  }

  public void touchReportDraft(long tenantId, String reportId) {
    jdbcTemplate.update("""
        update daily_loss_report
        set status = 'DRAFT', submitted_by = null, submitted_at = null,
            reviewed_by = null, reviewed_at = null, review_note = null,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, tenantId, reportId);
  }

  public void markReportSubmitted(long tenantId, String reportId, long actorId) {
    jdbcTemplate.update("""
        update daily_loss_report
        set status = 'SUBMITTED', submitted_by = ?, submitted_at = current_timestamp,
            reviewed_by = null, reviewed_at = null, review_note = null,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, actorId, tenantId, reportId);
    jdbcTemplate.update("""
        update daily_loss_record
        set status = 'SUBMITTED', reviewed_by = null, reviewed_at = null, review_note = null,
            updated_at = current_timestamp
        where tenant_id = ? and report_id = ?
        """, tenantId, reportId);
  }

  public void markReportReviewed(long tenantId, String reportId, long actorId, String reviewNote) {
    jdbcTemplate.update("""
        update daily_loss_report
        set status = 'REVIEWED', reviewed_by = ?, reviewed_at = current_timestamp,
            review_note = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, actorId, blankToNull(reviewNote), tenantId, reportId);
    jdbcTemplate.update("""
        update daily_loss_record
        set status = 'APPROVED', reviewed_by = ?, reviewed_at = current_timestamp,
            review_note = ?, updated_at = current_timestamp
        where tenant_id = ? and report_id = ?
        """, actorId, blankToNull(reviewNote), tenantId, reportId);
  }

  public List<DailyLossReportDetailResponse> reportDetails(long tenantId, String reportId) {
    return jdbcTemplate.query("""
        select r.id, r.item_config_id, r.item_code, r.item_name,
               coalesce(wc.name, c.category, '每日报损') as category,
               r.stock_unit, r.loss_quantity, r.unit_price_snapshot,
               r.amount_snapshot, r.loss_reason
        from daily_loss_record r
        left join loss_item_config c on c.tenant_id = r.tenant_id and c.id = r.item_config_id
        left join warehouse_item_category wc on wc.tenant_id = c.tenant_id and wc.id = c.warehouse_category_id
        where r.tenant_id = ? and r.report_id = ?
        order by r.id
        """, (rs, rowNum) -> new DailyLossReportDetailResponse(
            rs.getString("id"),
            rs.getObject("item_config_id", Long.class),
            rs.getString("item_code"),
            rs.getString("item_name"),
            rs.getString("category"),
            rs.getString("stock_unit"),
            rs.getBigDecimal("loss_quantity"),
            rs.getBigDecimal("unit_price_snapshot"),
            rs.getBigDecimal("amount_snapshot"),
            rs.getString("loss_reason")),
        tenantId, reportId);
  }

  public int reportAttachmentCount(long tenantId, String reportId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from warehouse_attachment
        where tenant_id = ? and business_type = 'DAILY_LOSS' and business_id = ?
        """, Integer.class, tenantId, reportId);
    return count == null ? 0 : count;
  }

  public List<MonthlyExportDetailRow> monthlyExportDetails(
      long tenantId,
      YearMonth month,
      String storeId,
      DataScope scope
  ) {
    LocalDate start = month.atDay(1);
    LocalDate end = month.plusMonths(1).atDay(1);
    StringBuilder sql = new StringBuilder("""
        select report.id as report_id, report.store_id, s.code as store_code, s.name as store_name,
               report.loss_date, report.status, report.submitted_by,
               submitter.display_name as submitted_by_name, report.submitted_at,
               report.reviewed_by, reviewer.display_name as reviewed_by_name, report.reviewed_at,
               report.review_note, detail.item_code, detail.item_name,
               coalesce(category.name, config.category, '每日报损') as category,
               detail.loss_quantity, detail.stock_unit, detail.unit_price_snapshot,
               detail.amount_snapshot, detail.loss_reason
        from daily_loss_record detail
        join daily_loss_report report on report.tenant_id = detail.tenant_id and report.id = detail.report_id
        join store_branch s on s.tenant_id = report.tenant_id and s.id = report.store_id
        left join auth_user submitter on submitter.tenant_id = report.tenant_id and submitter.id = report.submitted_by
        left join auth_user reviewer on reviewer.tenant_id = report.tenant_id and reviewer.id = report.reviewed_by
        left join loss_item_config config on config.tenant_id = detail.tenant_id and config.id = detail.item_config_id
        left join warehouse_item_category category on category.tenant_id = config.tenant_id
          and category.id = config.warehouse_category_id
        where detail.tenant_id = :tenantId
          and report.loss_date >= :start
          and report.loss_date < :end
        """);
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("start", start)
        .addValue("end", end);
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and report.store_id = :storeId");
      params.addValue("storeId", storeId.trim());
    }
    appendStoreScope(sql, params, scope, "report.store_id");
    sql.append(" order by report.loss_date, s.code, report.id, detail.id");
    return namedJdbc.query(sql.toString(), params, (rs, rowNum) -> new MonthlyExportDetailRow(
        rs.getString("report_id"),
        rs.getString("store_id"),
        rs.getString("store_code"),
        rs.getString("store_name"),
        rs.getObject("loss_date", LocalDate.class),
        rs.getString("status"),
        rs.getString("item_code"),
        rs.getString("item_name"),
        rs.getString("category"),
        rs.getBigDecimal("loss_quantity"),
        rs.getString("stock_unit"),
        rs.getBigDecimal("unit_price_snapshot"),
        rs.getBigDecimal("amount_snapshot"),
        rs.getString("loss_reason"),
        rs.getString("submitted_by_name"),
        rs.getObject("submitted_at", LocalDateTime.class),
        rs.getString("reviewed_by_name"),
        rs.getObject("reviewed_at", LocalDateTime.class),
        rs.getString("review_note")));
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

  private String reportSelect() {
    return """
        select r.id, r.store_id, s.code as store_code, s.name as store_name, r.loss_date,
               r.status, r.submitted_by, submitter.display_name as submitted_by_name, r.submitted_at,
               r.reviewed_by, reviewer.display_name as reviewed_by_name, r.reviewed_at, r.review_note
        from daily_loss_report r
        join store_branch s on s.tenant_id = r.tenant_id and s.id = r.store_id
        left join auth_user submitter on submitter.tenant_id = r.tenant_id and submitter.id = r.submitted_by
        left join auth_user reviewer on reviewer.tenant_id = r.tenant_id and reviewer.id = r.reviewed_by
        """;
  }

  private RowMapper<DailyLossReportRow> reportMapper() {
    return (rs, rowNum) -> new DailyLossReportRow(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_code"),
        rs.getString("store_name"),
        rs.getObject("loss_date", LocalDate.class),
        rs.getString("status"),
        rs.getObject("submitted_by", Long.class),
        rs.getString("submitted_by_name"),
        rs.getObject("submitted_at", LocalDateTime.class),
        rs.getObject("reviewed_by", Long.class),
        rs.getString("reviewed_by_name"),
        rs.getObject("reviewed_at", LocalDateTime.class),
        rs.getString("review_note"));
  }

  private void appendStoreScope(StringBuilder sql, MapSqlParameterSource params, DataScope scope) {
    appendStoreScope(sql, params, scope, "r.store_id");
  }

  private void appendStoreScope(StringBuilder sql, MapSqlParameterSource params, DataScope scope, String storeColumn) {
    if (scope == null || scope.allowsAllStores()) return;
    if (scope.warehouseIds() != null && !scope.warehouseIds().isEmpty()) {
      sql.append(" and s.supply_warehouse_id in (:scopeWarehouseIds)");
      params.addValue("scopeWarehouseIds", scope.warehouseIds());
      return;
    }
    if (scope.deniesStoreAccess() || scope.storeIds().isEmpty()) {
      sql.append(" and 1 = 0");
      return;
    }
    sql.append(" and ").append(storeColumn).append(" in (:scopeStoreIds)");
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

  private String dailyLossCategoryCode(Long warehouseCategoryId, String sourceCategory) {
    return warehouseCategoryId == null ? blankToNull(sourceCategory) : "WAREHOUSE_CATEGORY_" + warehouseCategoryId;
  }

  private String dailyLossCategoryName(String warehouseCategoryName, String sourceCategory) {
    String mapped = blankToNull(warehouseCategoryName);
    return mapped == null ? blankToNull(sourceCategory) : mapped;
  }

  public record LossItemRow(long id, String code, String name, String stockUnit, BigDecimal unitPrice) {}
  public record LossItemConfigRow(long id, String itemCode, String itemName, String category, String unit,
                                  BigDecimal unitPrice) {}
  public record ReportStoreRow(String id, String code, String name) {}
  public record DailyLossReportRow(String id, String storeId, String storeCode, String storeName, LocalDate lossDate,
                                   String status, Long submittedBy, String submittedByName,
                                   LocalDateTime submittedAt, Long reviewedBy, String reviewedByName,
                                   LocalDateTime reviewedAt, String reviewNote) {}
  public record MonthlyExportDetailRow(
      String reportId, String storeId, String storeCode, String storeName, LocalDate lossDate, String status,
      String itemCode, String itemName, String category, BigDecimal lossQuantity, String unit,
      BigDecimal unitPrice, BigDecimal amount, String lossReason, String submittedByName,
      LocalDateTime submittedAt, String reviewedByName, LocalDateTime reviewedAt, String reviewNote
  ) {}
  public record LockedLossRow(String id, String storeId, long itemId, BigDecimal lossQuantity, String lossReason,
                              String status) {}
  public record DailyLossRow(String id, String storeId, String storeCode, String storeName, LocalDate lossDate,
      long itemId, String itemCode, String itemName, String stockUnit, BigDecimal lossQuantity,
      BigDecimal unitPriceSnapshot, BigDecimal amountSnapshot, String lossReason, String status,
      Long submittedBy, String submittedByName, LocalDateTime submittedAt, Long reviewedBy,
      String reviewedByName, LocalDateTime reviewedAt, String reviewNote) {}
}
