package com.storeprofit.system.todo;

import com.storeprofit.system.platform.tenant.TenantDefaults;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoleTodoRepository {
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public RoleTodoRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  public List<InspectionTodoRow> failedInspections(long tenantId, Long brandId, String storeId, int limit) {
    StringBuilder sql = new StringBuilder("""
        with inspection_presentation as (
          select ir.id, ir.tenant_id, ir.store_id, ir.inspection_date, ir.note,
                 case
                   when upper(coalesce(repair.repair_status, '')) = 'RECALCULATED'
                     then coalesce(repair.repaired_score, ir.score)
                   else ir.score
                 end as display_score,
                 case
                   when upper(coalesce(repair.repair_status, '')) = 'RECALCULATED'
                     then coalesce(repair.repaired_full_score, ir.full_score)
                   else ir.full_score
                 end as display_full_score,
                 case
                   when upper(coalesce(repair.repair_status, '')) = 'MANUAL_REVIEW'
                     then 'MANUAL_REVIEW'
                   when upper(coalesce(repair.repair_status, '')) = 'RECALCULATED' then
                     case
                       when upper(coalesce(repair.repaired_result_code, '')) = 'RED_LINE_FAILED'
                         then 'RED_LINE_FAILED'
                        when coalesce(repair.repaired_score, ir.score) >= coalesce(
                          repair.repaired_pass_score, 180
                        ) then 'PASSED'
                        else 'FAILED'
                      end
                    else
                      case
                       when upper(coalesce(ir.result_code, '')) = 'RED_LINE_FAILED'
                         or lower(trim(coalesce(ir.redlines_json, ''))) not in ('', '[]', 'null')
                         then 'RED_LINE_FAILED'
                        when ir.score >= 180 then 'PASSED'
                       else 'FAILED'
                     end
                 end as display_result_code
          from inspection_record ir
          left join inspection_result_repair_audit repair
            on repair.tenant_id = ir.tenant_id
           and repair.inspection_record_id = ir.id
           and repair.id = (
             select max(latest_repair.id)
             from inspection_result_repair_audit latest_repair
             where latest_repair.tenant_id = ir.tenant_id
               and latest_repair.inspection_record_id = ir.id
           )
        )
        select effective_ir.id, effective_ir.store_id,
               s.name as store_name, b.name as brand_name,
               effective_ir.inspection_date,
               effective_ir.display_score as score,
               effective_ir.display_full_score as full_score,
               effective_ir.display_result_code as result_code,
               effective_ir.note
        from inspection_presentation effective_ir
        join store_branch s on s.id = effective_ir.store_id and s.tenant_id = effective_ir.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where effective_ir.tenant_id = :tenantId
          and (
            effective_ir.display_result_code <> 'PASSED'
            or exists (
              select 1 from todo_action ta
              where ta.tenant_id = effective_ir.tenant_id
                and ta.todo_id = concat('inspection-', effective_ir.id)
                and ta.status = 'DONE'
            )
          )
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    addScopeFilters(sql, params, brandId, storeId, "s", "effective_ir");
    sql.append(" order by effective_ir.inspection_date desc, effective_ir.id limit :limit");
    params.addValue("limit", limit);
    return namedJdbcTemplate.query(sql.toString(), params, this::mapInspection);
  }

  public List<WarehouseTodoRow> pendingWarehouseRequisitions(long tenantId, Long brandId, String storeId, int limit) {
    return pendingWarehouseRequisitions(tenantId, brandId, storeId, List.of("SUBMITTED", "APPROVED"), limit);
  }

  public List<WarehouseTodoRow> pendingWarehouseRequisitions(
      long tenantId,
      Long brandId,
      String storeId,
      List<String> statuses,
      int limit
  ) {
    List<String> safeStatuses = statuses == null || statuses.isEmpty()
        ? List.of("SUBMITTED", "APPROVED")
        : statuses;
    StringBuilder sql = new StringBuilder("""
        select r.id, r.store_id, s.name as store_name, b.name as brand_name,
               r.status, r.total_amount, r.submitted_at
        from store_requisition r
        join store_branch s on s.id = r.store_id and s.tenant_id = r.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where r.tenant_id = :tenantId
          and r.status in (:statuses)
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId)
        .addValue("statuses", safeStatuses);
    addScopeFilters(sql, params, brandId, storeId, "s", "r");
    sql.append(" order by r.submitted_at asc, r.id limit :limit");
    params.addValue("limit", limit);
    return namedJdbcTemplate.query(sql.toString(), params, this::mapWarehouse);
  }

  public List<WarehouseReturnTodoRow> pendingWarehouseReturns(long tenantId, Long brandId, String storeId, int limit) {
    if (!tableExists("warehouse_return_order")) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder("""
        select ro.id, ro.return_no, ro.return_store_id as store_id,
               coalesce(ro.return_store_name, s.name, ro.return_store_id) as store_name,
               b.name as brand_name, ro.status, ro.total_amount, ro.created_at
        from warehouse_return_order ro
        left join store_branch s on s.id = ro.return_store_id and s.tenant_id = ro.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where ro.tenant_id = :tenantId
          and ro.status in ('SUBMITTED', 'APPROVED')
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (brandId != null) {
      sql.append(" and s.brand_id = :brandId");
      params.addValue("brandId", brandId);
    }
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and ro.return_store_id = :storeId");
      params.addValue("storeId", storeId.trim());
    }
    sql.append(" order by ro.created_at asc, ro.id limit :limit");
    params.addValue("limit", limit);
    return namedJdbcTemplate.query(sql.toString(), params, this::mapWarehouseReturn);
  }

  public Optional<String> warehouseRequisitionStatus(long tenantId, String todoId) {
    String requisitionId = warehouseRequisitionIdFromTodo(todoId);
    if (requisitionId.isBlank()) {
      return Optional.empty();
    }
    List<String> statuses = namedJdbcTemplate.query("""
        select status
        from store_requisition
        where tenant_id = :tenantId and id = :id
        limit 1
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", requisitionId),
        (rs, rowNum) -> rs.getString("status")
    );
    return statuses.stream().findFirst();
  }

  public boolean hasOpenWarehouseStockAlert(long tenantId, String todoId) {
    if (todoId == null || !todoId.startsWith("warehouse-alert-")) {
      return false;
    }
    return warehouseStockAlerts(tenantId, 500).stream()
        .anyMatch(row -> todoId.equals(row.id()));
  }

  public List<WarehouseStockAlertTodoRow> warehouseStockAlerts(long tenantId, int limit) {
    if (!tableExists("warehouse_item") || !tableExists("warehouse_stock_batch")) {
      return List.of();
    }
    return namedJdbcTemplate.query("""
        select concat(case
                 when stock.nearest_expiry_date is not null
                   and stock.expiry_alert_days is not null
                   and timestampdiff(day, curdate(), stock.nearest_expiry_date) <= stock.expiry_alert_days
                   then 'warehouse-alert-expiring-'
                 else 'warehouse-alert-low-'
               end, stock.item_id) as id,
               stock.item_id,
               stock.item_name,
               case
                 when stock.nearest_expiry_date is not null
                   and stock.expiry_alert_days is not null
                   and timestampdiff(day, curdate(), stock.nearest_expiry_date) <= stock.expiry_alert_days
                   then 'EXPIRING'
                 else 'LOW'
               end as alert_type,
               case
                 when stock.nearest_expiry_date is not null
                   and stock.expiry_alert_days is not null
                   and timestampdiff(day, curdate(), stock.nearest_expiry_date) <= stock.expiry_alert_days
                   then concat('临期风险，最近批次 ', stock.nearest_expiry_date, ' 到期')
                 else concat('库存不足：', stock.item_name, ' 当前 ', stock.stock_quantity, stock.unit, '，最低安全库存 ', stock.min_stock_quantity, stock.unit, '。')
               end as message,
               stock.stock_quantity,
               stock.nearest_expiry_date as nearest_expiry_date
        from (
          select i.id as item_id,
                 i.name as item_name,
                 i.unit,
                 i.min_stock_quantity,
                 i.alert_enabled,
                 i.expiry_alert_days,
                 coalesce(sum(case when b.quantity > 0 then b.quantity else 0 end), 0) as stock_quantity,
                 min(case when b.quantity > 0 then date(b.expiry_date) else null end) as nearest_expiry_date
          from warehouse_item i
          left join warehouse_stock_batch b on b.tenant_id = i.tenant_id and b.item_id = i.id
          where i.tenant_id = :tenantId and i.active = 1
          group by i.id, i.name, i.unit, i.min_stock_quantity, i.alert_enabled, i.expiry_alert_days
        ) stock
        where (
            stock.nearest_expiry_date is not null
            and stock.expiry_alert_days is not null
            and timestampdiff(day, curdate(), stock.nearest_expiry_date) <= stock.expiry_alert_days
          )
          or (
            stock.alert_enabled = 1
            and stock.min_stock_quantity > 0
            and stock.stock_quantity < stock.min_stock_quantity
          )
        order by case
            when stock.nearest_expiry_date is not null
              and stock.expiry_alert_days is not null
              and timestampdiff(day, curdate(), stock.nearest_expiry_date) <= stock.expiry_alert_days then 0
            else 1
          end,
          stock.item_name
        limit :limit
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", limit),
        this::mapWarehouseStockAlert
    );
  }

  private boolean tableExists(String tableName) {
    try {
      Integer count = jdbcTemplate.queryForObject("""
          select count(*)
          from information_schema.tables
          where lower(table_name) = lower(?)
          """, Integer.class, tableName);
      return count != null && count > 0;
    } catch (RuntimeException ex) {
      return true;
    }
  }

  public List<WarehousePurchaseTodoRow> pendingWarehousePurchases(long tenantId, int limit) {
    if (!tableExists("warehouse_purchase_order")) {
      return List.of();
    }
    return namedJdbcTemplate.query("""
        select po.id,
               coalesce(s.name, '未指定供应商') as supplier_name,
               po.status,
               po.total_amount,
               po.created_at
        from warehouse_purchase_order po
        left join warehouse_supplier s on s.tenant_id = po.tenant_id and s.id = po.supplier_id
        where po.tenant_id = :tenantId
          and (
            po.status in ('DRAFT', 'ORDERED')
            or exists (
              select 1 from todo_action ta
              where ta.tenant_id = po.tenant_id
                and ta.todo_id = concat('warehouse-purchase-', po.id)
                and ta.status = 'DONE'
            )
          )
        order by po.created_at asc, po.id
        limit :limit
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", limit),
        this::mapWarehousePurchase
    );
  }

  public List<WarehouseAdjustmentTodoRow> stockLossAdjustments(long tenantId, int limit) {
    if (!tableExists("warehouse_stock_adjustment") || !tableExists("warehouse_item")) {
      return List.of();
    }
    return namedJdbcTemplate.query("""
        select a.id,
               i.name as item_name,
               a.adjustment_type,
               a.quantity_delta,
               a.reason,
               a.created_at
        from warehouse_stock_adjustment a
        join warehouse_item i on i.tenant_id = a.tenant_id and i.id = a.item_id
        where a.tenant_id = :tenantId
          and (
            a.quantity_delta < 0
            or exists (
              select 1 from todo_action ta
              where ta.tenant_id = a.tenant_id
                and ta.todo_id = concat('warehouse-adjustment-', a.id)
                and ta.status = 'DONE'
            )
          )
        order by a.created_at desc, a.id desc
        limit :limit
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", limit),
        this::mapWarehouseAdjustment
    );
  }

  public List<ExpenseTodoRow> pendingExpenseClaims(long tenantId, Long brandId, String storeId, int limit) {
    StringBuilder sql = new StringBuilder("""
        select ec.id, ec.store_id, s.name as store_name, b.name as brand_name,
               ec.month, ec.amount, ec.category, ec.reason, ec.status, ec.created_at
        from expense_claim ec
        join store_branch s on s.id = ec.store_id and s.tenant_id = ec.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where ec.tenant_id = :tenantId
          and (
            ec.status in ('待审核', 'PENDING', 'SUBMITTED')
            or exists (
              select 1 from todo_action ta
              where ta.tenant_id = ec.tenant_id
                and ta.todo_id = concat('expense-', ec.id)
                and ta.status = 'DONE'
            )
          )
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    addScopeFilters(sql, params, brandId, storeId, "s", "ec");
    sql.append(" order by ec.created_at asc, ec.id limit :limit");
    params.addValue("limit", limit);
    return namedJdbcTemplate.query(sql.toString(), params, this::mapExpense);
  }

  public List<ProfitRiskTodoRow> profitRiskEntries(long tenantId, Long brandId, String storeId, int limit) {
    StringBuilder sql = new StringBuilder("""
        select *
        from (
          select p.store_id, s.name as store_name, b.name as brand_name, p.month,
                 (p.sales - p.refund - p.discount) as income,
                 (
                   p.sales - p.refund - p.discount
                   - p.material - p.packaging - p.loss - p.cost_other
                   - p.rent - p.labor - p.utility - p.property - p.commission - p.promo - p.repair - p.equip - p.exp_other
                 ) as net,
                 p.note
          from profit_entry p
          join store_branch s on s.id = p.store_id and s.tenant_id = p.tenant_id
          left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
          where p.tenant_id = :tenantId
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    addScopeFilters(sql, params, brandId, storeId, "s", "p");
    sql.append("""
        ) risk
        where risk.net < 0
           or (risk.income > 0 and (risk.net / risk.income) < 0.08)
        order by risk.month desc, risk.net asc, risk.store_id
        limit :limit
        """);
    params.addValue("limit", limit);
    return namedJdbcTemplate.query(sql.toString(), params, this::mapProfitRisk);
  }

  public List<DataImportIssueTodoRow> dataImportIssues(long tenantId, int limit) {
    if (tenantId != TenantDefaults.DEFAULT_TENANT_ID) {
      return List.of();
    }
    return namedJdbcTemplate.query("""
        select storage_key, updated_at
        from kv_storage
        where :tenantId = :legacyTenantId
          and (
            storage_key like 'migration_error:%'
            or storage_key like 'import_error:%'
            or storage_key like 'legacy_error:%'
          )
        order by updated_at desc, storage_key
        limit :limit
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("legacyTenantId", TenantDefaults.DEFAULT_TENANT_ID)
            .addValue("limit", limit),
        this::mapDataImportIssue
    );
  }

  public int markSourceHandled(long tenantId, String todoId, Long actorUserId) {
    if (todoId == null || todoId.isBlank()) {
      return 0;
    }
    String normalizedTodoId = todoId.trim();
    if (normalizedTodoId.startsWith("expense-")) {
      return markExpenseHandled(tenantId, idAfterPrefix(normalizedTodoId, "expense-"), actorUserId);
    }
    if (normalizedTodoId.startsWith("inspection-")) {
      // The completion is stored in todo_action. Historical inspection scores and outcomes are immutable.
      return 0;
    }
    if (normalizedTodoId.startsWith("warehouse-alert-") || normalizedTodoId.startsWith("store-receipt-")) {
      return 0;
    }
    if (normalizedTodoId.startsWith("warehouse-return-")) {
      return 0;
    }
    if (normalizedTodoId.startsWith("warehouse-")) {
      return markWarehouseHandled(tenantId, idAfterPrefix(normalizedTodoId, "warehouse-"), actorUserId);
    }
    return 0;
  }

  private String warehouseRequisitionIdFromTodo(String todoId) {
    if (todoId == null || todoId.isBlank()) {
      return "";
    }
    String normalized = todoId.trim();
    if (normalized.startsWith("warehouse-") && !normalized.startsWith("warehouse-alert-")) {
      return idAfterPrefix(normalized, "warehouse-");
    }
    if (normalized.startsWith("store-receipt-")) {
      return idAfterPrefix(normalized, "store-receipt-");
    }
    return "";
  }

  private int markExpenseHandled(long tenantId, String expenseId, Long actorUserId) {
    if (expenseId.isBlank()) {
      return 0;
    }
    return namedJdbcTemplate.update("""
        update expense_claim
        set status = '已完成',
            reviewed_by = coalesce(:actorUserId, reviewed_by),
            reviewed_at = current_timestamp,
            updated_at = current_timestamp
        where tenant_id = :tenantId and id = :id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", expenseId)
            .addValue("actorUserId", actorUserId)
    );
  }

  private int markWarehouseHandled(long tenantId, String requisitionId, Long actorUserId) {
    if (requisitionId.isBlank()) {
      return 0;
    }
    return namedJdbcTemplate.update("""
        update store_requisition
        set status = 'TODO_DONE',
            reviewed_by = coalesce(reviewed_by, :actorUserId),
            reviewed_at = coalesce(reviewed_at, current_timestamp),
            updated_at = current_timestamp
        where tenant_id = :tenantId and id = :id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", requisitionId)
            .addValue("actorUserId", actorUserId)
    );
  }

  private String idAfterPrefix(String value, String prefix) {
    return value.length() <= prefix.length() ? "" : value.substring(prefix.length()).trim();
  }

  private void addScopeFilters(
      StringBuilder sql,
      MapSqlParameterSource params,
      Long brandId,
      String storeId,
      String storeAlias,
      String recordAlias
  ) {
    if (brandId != null) {
      sql.append(" and ").append(storeAlias).append(".brand_id = :brandId");
      params.addValue("brandId", brandId);
    }
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and ").append(recordAlias).append(".store_id = :storeId");
      params.addValue("storeId", storeId.trim());
    }
  }

  private InspectionTodoRow mapInspection(ResultSet rs, int rowNum) throws SQLException {
    Date date = rs.getDate("inspection_date");
    return new InspectionTodoRow(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("brand_name"),
        date == null ? null : date.toLocalDate().toString(),
        rs.getBigDecimal("score"),
        rs.getBigDecimal("full_score"),
        rs.getString("result_code"),
        rs.getString("note")
    );
  }

  private WarehouseTodoRow mapWarehouse(ResultSet rs, int rowNum) throws SQLException {
    Timestamp submittedAt = rs.getTimestamp("submitted_at");
    return new WarehouseTodoRow(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("brand_name"),
        rs.getString("status"),
        rs.getBigDecimal("total_amount"),
        submittedAt == null ? null : submittedAt.toLocalDateTime().toString()
    );
  }

  private WarehouseStockAlertTodoRow mapWarehouseStockAlert(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseStockAlertTodoRow(
        rs.getString("id"),
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("alert_type"),
        rs.getString("message"),
        rs.getBigDecimal("stock_quantity"),
        rs.getString("nearest_expiry_date")
    );
  }

  private WarehouseReturnTodoRow mapWarehouseReturn(ResultSet rs, int rowNum) throws SQLException {
    Timestamp createdAt = rs.getTimestamp("created_at");
    return new WarehouseReturnTodoRow(
        rs.getString("id"),
        rs.getString("return_no"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("brand_name"),
        rs.getString("status"),
        rs.getBigDecimal("total_amount"),
        createdAt == null ? null : createdAt.toLocalDateTime().toString()
    );
  }

  private WarehousePurchaseTodoRow mapWarehousePurchase(ResultSet rs, int rowNum) throws SQLException {
    Timestamp createdAt = rs.getTimestamp("created_at");
    return new WarehousePurchaseTodoRow(
        rs.getString("id"),
        rs.getString("supplier_name"),
        rs.getString("status"),
        rs.getBigDecimal("total_amount"),
        createdAt == null ? null : createdAt.toLocalDateTime().toString()
    );
  }

  private WarehouseAdjustmentTodoRow mapWarehouseAdjustment(ResultSet rs, int rowNum) throws SQLException {
    Timestamp createdAt = rs.getTimestamp("created_at");
    return new WarehouseAdjustmentTodoRow(
        rs.getLong("id"),
        rs.getString("item_name"),
        rs.getString("adjustment_type"),
        rs.getBigDecimal("quantity_delta"),
        rs.getString("reason"),
        createdAt == null ? null : createdAt.toLocalDateTime().toString()
    );
  }

  private ExpenseTodoRow mapExpense(ResultSet rs, int rowNum) throws SQLException {
    Timestamp createdAt = rs.getTimestamp("created_at");
    return new ExpenseTodoRow(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("brand_name"),
        rs.getString("month"),
        rs.getBigDecimal("amount"),
        rs.getString("category"),
        rs.getString("reason"),
        rs.getString("status"),
        createdAt == null ? null : createdAt.toLocalDateTime().toString()
    );
  }

  private ProfitRiskTodoRow mapProfitRisk(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal income = rs.getBigDecimal("income");
    BigDecimal net = rs.getBigDecimal("net");
    BigDecimal margin = BigDecimal.ZERO;
    if (income != null && income.compareTo(BigDecimal.ZERO) > 0 && net != null) {
      margin = net.divide(income, 4, java.math.RoundingMode.HALF_UP);
    }
    return new ProfitRiskTodoRow(
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("brand_name"),
        rs.getString("month"),
        income,
        net,
        margin,
        rs.getString("note")
    );
  }

  private DataImportIssueTodoRow mapDataImportIssue(ResultSet rs, int rowNum) throws SQLException {
    Timestamp updatedAt = rs.getTimestamp("updated_at");
    return new DataImportIssueTodoRow(
        rs.getString("storage_key"),
        updatedAt == null ? null : updatedAt.toLocalDateTime().toString()
    );
  }

  public record InspectionTodoRow(
      String id,
      String storeId,
      String storeName,
      String brandName,
      String inspectionDate,
      BigDecimal score,
      BigDecimal fullScore,
      String resultCode,
      String note
  ) {
  }

  public record WarehouseTodoRow(
      String id,
      String storeId,
      String storeName,
      String brandName,
      String status,
      BigDecimal totalAmount,
      String submittedAt
  ) {
  }

  public record WarehouseStockAlertTodoRow(
      String id,
      long itemId,
      String itemName,
      String alertType,
      String message,
      BigDecimal stockQuantity,
      String nearestExpiryDate
  ) {
  }

  public record WarehouseReturnTodoRow(
      String id,
      String returnNo,
      String storeId,
      String storeName,
      String brandName,
      String status,
      BigDecimal totalAmount,
      String createdAt
  ) {
  }

  public record WarehousePurchaseTodoRow(
      String id,
      String supplierName,
      String status,
      BigDecimal totalAmount,
      String createdAt
  ) {
  }

  public record WarehouseAdjustmentTodoRow(
      long id,
      String itemName,
      String adjustmentType,
      BigDecimal quantityDelta,
      String reason,
      String createdAt
  ) {
  }

  public record ExpenseTodoRow(
      String id,
      String storeId,
      String storeName,
      String brandName,
      String month,
      BigDecimal amount,
      String category,
      String reason,
      String status,
      String createdAt
  ) {
  }

  public record ProfitRiskTodoRow(
      String storeId,
      String storeName,
      String brandName,
      String month,
      BigDecimal income,
      BigDecimal net,
      BigDecimal margin,
      String note
  ) {
  }

  public record DataImportIssueTodoRow(
      String storageKey,
      String updatedAt
  ) {
  }
}
