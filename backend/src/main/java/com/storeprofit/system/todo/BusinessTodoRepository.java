package com.storeprofit.system.todo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessTodoRepository {
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public BusinessTodoRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  public List<Long> activeTenantIds() {
    return jdbcTemplate.queryForList("""
        select id
        from tenant
        where status = 'ACTIVE'
        order by id
        """, Long.class);
  }

  public BusinessTodoRow openOrRefresh(long tenantId, BusinessTodoDraft draft) {
    Optional<BusinessTodoRow> latest = latestByRuleAndSource(tenantId, draft.ruleCode(), draft.sourceKey());
    if (latest.isEmpty()) {
      return insert(tenantId, draft, 1);
    }
    BusinessTodoRow current = latest.get();
    if (!current.conditionActive()) {
      return insert(tenantId, draft, current.occurrenceNo() + 1);
    }
    refresh(current.id(), tenantId, draft);
    return findById(tenantId, current.id()).orElseThrow();
  }

  public Optional<BusinessTodoRow> findById(long tenantId, String id) {
    List<BusinessTodoRow> rows = namedJdbcTemplate.query(baseSelect() + """
        and t.id = :id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", id),
        this::mapRow
    );
    return rows.stream().findFirst();
  }

  public Optional<BusinessTodoRow> findVisibleById(
      long tenantId,
      String id,
      String role,
      boolean allRoles,
      boolean allStores,
      List<String> storeIds
  ) {
    if (!allStores && (storeIds == null || storeIds.isEmpty())) {
      return Optional.empty();
    }
    StringBuilder sql = new StringBuilder(baseSelect()).append(" and t.id = :id");
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("id", id);
    appendVisibility(sql, params, role, allRoles, allStores, storeIds);
    return namedJdbcTemplate.query(sql.toString(), params, this::mapRow).stream().findFirst();
  }

  public boolean existsById(long tenantId, String id) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from business_todo where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        id
    );
    return count != null && count > 0;
  }

  public Optional<BusinessTodoRow> latestByRuleAndSource(long tenantId, String ruleCode, String sourceKey) {
    List<BusinessTodoRow> rows = namedJdbcTemplate.query(baseSelect() + """
        and t.rule_code = :ruleCode
        and t.source_key = :sourceKey
        order by t.occurrence_no desc
        limit 1
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("ruleCode", ruleCode)
            .addValue("sourceKey", sourceKey),
        this::mapRow
    );
    return rows.stream().findFirst();
  }

  public List<BusinessTodoRow> list(long tenantId, String status, int limit) {
    StringBuilder sql = new StringBuilder(baseSelect());
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (status != null && !status.isBlank()) {
      sql.append(" and t.status = :status");
      params.addValue("status", status);
    }
    sql.append(" order by case when t.status in ('PENDING', 'IN_PROGRESS', 'PENDING_REVIEW') then 0 else 1 end, ")
        .append("t.priority desc, t.updated_at desc, t.id desc limit :limit");
    params.addValue("limit", limit);
    return namedJdbcTemplate.query(sql.toString(), params, this::mapRow);
  }

  public List<BusinessTodoRow> listVisible(
      long tenantId,
      String status,
      int limit,
      String role,
      boolean allRoles,
      boolean allStores,
      List<String> storeIds
  ) {
    if (!allStores && (storeIds == null || storeIds.isEmpty())) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder(baseSelect());
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (status != null && !status.isBlank()) {
      sql.append(" and t.status = :status");
      params.addValue("status", status);
    }
    appendVisibility(sql, params, role, allRoles, allStores, storeIds);
    sql.append(" order by case when t.status in ('PENDING', 'IN_PROGRESS', 'PENDING_REVIEW') then 0 else 1 end, ")
        .append("t.priority desc, t.updated_at desc, t.id desc limit :limit");
    params.addValue("limit", limit);
    return namedJdbcTemplate.query(sql.toString(), params, this::mapRow);
  }

  private void appendVisibility(
      StringBuilder sql,
      MapSqlParameterSource params,
      String role,
      boolean allRoles,
      boolean allStores,
      List<String> storeIds
  ) {
    if (!allRoles) {
      sql.append(" and (upper(t.assignee_role) = :role or upper(t.review_role) = :role)");
      params.addValue("role", role == null ? "" : role.trim().toUpperCase());
    }
    if (!allStores) {
      sql.append(" and t.store_id in (:storeIds)");
      params.addValue("storeIds", storeIds);
    }
  }

  public List<BusinessTodoRow> activeByRuleAndMonth(long tenantId, String ruleCode, String month) {
    return namedJdbcTemplate.query(baseSelect() + """
        and t.rule_code = :ruleCode
        and t.month = :month
        and t.condition_active = 1
        order by t.occurrence_no desc
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("ruleCode", ruleCode)
            .addValue("month", month),
        this::mapRow
    );
  }

  public boolean markConditionInactive(long tenantId, String id) {
    return namedJdbcTemplate.update("""
        update business_todo
        set condition_active = 0,
            updated_at = current_timestamp
        where tenant_id = :tenantId and id = :id and condition_active = 1
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", id)
    ) > 0;
  }

  public boolean updateStatus(
      long tenantId,
      String id,
      BusinessTodoStatus expected,
      BusinessTodoStatus target,
      Long operatorId,
      String operatorName
  ) {
    // 注意拼接处必须留空白：两个文本块直接相连会生成 "current_timestampwhere" 这种非法 SQL。
    String completedAt = target.terminal() ? "current_timestamp" : "null";
    return namedJdbcTemplate.update("""
        update business_todo
        set status = :targetStatus,
            last_operator_id = :operatorId,
            last_operator_name = :operatorName,
            updated_at = current_timestamp,
            completed_at = """ + " " + completedAt + " " + """
        where tenant_id = :tenantId and id = :id and status = :expectedStatus
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", id)
            .addValue("expectedStatus", expected.name())
            .addValue("targetStatus", target.name())
            .addValue("operatorId", operatorId)
            .addValue("operatorName", operatorName)
    ) > 0;
  }

  public List<MissingProfitRow> storesWithoutProfit(long tenantId, String month) {
    return namedJdbcTemplate.query("""
        select s.id as store_id, s.name as store_name, b.name as brand_name
        from store_branch s
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where s.tenant_id = :tenantId
          and not exists (
            select 1
            from profit_entry p
            where p.tenant_id = s.tenant_id and p.store_id = s.id and p.month = :month
          )
        order by b.sort_order, s.code, s.id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("month", month),
        (rs, rowNum) -> new MissingProfitRow(
            rs.getString("store_id"),
            rs.getString("store_name"),
            rs.getString("brand_name")
        )
    );
  }

  public List<PendingExpenseRow> pendingExpenses(long tenantId, String month) {
    return namedJdbcTemplate.query("""
        select e.id, e.store_id, s.name as store_name, b.name as brand_name,
               e.month, e.amount, e.category, e.reason, e.status
        from expense_claim e
        join store_branch s on s.id = e.store_id and s.tenant_id = e.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where e.tenant_id = :tenantId
          and e.month = :month
          and e.status in ('待审核', '待补资料', 'PENDING', 'PENDING_INFO')
        order by e.created_at asc, e.id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("month", month),
        (rs, rowNum) -> new PendingExpenseRow(
            rs.getString("id"),
            rs.getString("store_id"),
            rs.getString("store_name"),
            rs.getString("brand_name"),
            rs.getString("month"),
            rs.getBigDecimal("amount"),
            rs.getString("category"),
            rs.getString("reason"),
            rs.getString("status")
        )
    );
  }

  public List<PendingSalaryRow> pendingSalaries(long tenantId, String month) {
    return namedJdbcTemplate.query("""
        select sr.id, sr.store_id, s.name as store_name, b.name as brand_name,
               sr.month, sr.employee_name, sr.gross, sr.status
        from salary_record sr
        join store_branch s on s.id = sr.store_id and s.tenant_id = sr.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where sr.tenant_id = :tenantId
          and sr.month = :month
          and sr.status = 'PENDING_REVIEW'
        order by s.code, sr.employee_name, sr.id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("month", month),
        (rs, rowNum) -> new PendingSalaryRow(
            rs.getString("id"),
            rs.getString("store_id"),
            rs.getString("store_name"),
            rs.getString("brand_name"),
            rs.getString("month"),
            rs.getString("employee_name"),
            rs.getBigDecimal("gross"),
            rs.getString("status")
        )
    );
  }

  private BusinessTodoRow insert(long tenantId, BusinessTodoDraft draft, int occurrenceNo) {
    String id = "biz-todo-" + UUID.randomUUID();
    namedJdbcTemplate.update("""
        insert into business_todo(
          id, tenant_id, rule_code, source_module, source_record_id, source_key, occurrence_no,
          title, summary, assignee_role, review_role, store_id, month, priority, status,
          condition_active, metadata_json, created_at, updated_at
        ) values (
          :id, :tenantId, :ruleCode, :sourceModule, :sourceRecordId, :sourceKey, :occurrenceNo,
          :title, :summary, :assigneeRole, :reviewRole, :storeId, :month, :priority, 'PENDING',
          1, :metadataJson, current_timestamp, current_timestamp
        )
        """,
        draftParams(tenantId, draft)
            .addValue("id", id)
            .addValue("occurrenceNo", occurrenceNo)
    );
    return findById(tenantId, id).orElseThrow();
  }

  private void refresh(String id, long tenantId, BusinessTodoDraft draft) {
    namedJdbcTemplate.update("""
        update business_todo
        set source_module = :sourceModule,
            source_record_id = :sourceRecordId,
            title = :title,
            summary = :summary,
            assignee_role = :assigneeRole,
            review_role = :reviewRole,
            store_id = :storeId,
            month = :month,
            priority = :priority,
            condition_active = 1,
            metadata_json = :metadataJson,
            updated_at = current_timestamp
        where tenant_id = :tenantId and id = :id
        """,
        draftParams(tenantId, draft).addValue("id", id)
    );
  }

  private MapSqlParameterSource draftParams(long tenantId, BusinessTodoDraft draft) {
    return new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("ruleCode", draft.ruleCode())
        .addValue("sourceModule", draft.sourceModule())
        .addValue("sourceRecordId", draft.sourceRecordId())
        .addValue("sourceKey", draft.sourceKey())
        .addValue("title", draft.title())
        .addValue("summary", draft.summary())
        .addValue("assigneeRole", draft.assigneeRole())
        .addValue("reviewRole", draft.reviewRole())
        .addValue("storeId", draft.storeId())
        .addValue("month", draft.month())
        .addValue("priority", draft.priority())
        .addValue("metadataJson", draft.metadataJson());
  }

  private String baseSelect() {
    return """
        select t.id, t.rule_code, t.source_module, t.source_record_id, t.source_key, t.occurrence_no,
               t.title, t.summary, t.assignee_role, t.review_role, t.store_id, t.month, t.priority,
               t.status, t.condition_active, t.metadata_json, t.last_operator_id, t.last_operator_name,
               t.created_at, t.updated_at, t.completed_at,
               s.name as store_name, b.name as brand_name
        from business_todo t
        left join store_branch s on s.id = t.store_id and s.tenant_id = t.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where t.tenant_id = :tenantId
        """;
  }

  private BusinessTodoRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new BusinessTodoRow(
        rs.getString("id"),
        rs.getString("rule_code"),
        rs.getString("source_module"),
        rs.getString("source_record_id"),
        rs.getString("source_key"),
        rs.getInt("occurrence_no"),
        rs.getString("title"),
        rs.getString("summary"),
        rs.getString("assignee_role"),
        rs.getString("review_role"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("brand_name"),
        rs.getString("month"),
        rs.getInt("priority"),
        rs.getString("status"),
        rs.getBoolean("condition_active"),
        rs.getString("metadata_json"),
        nullableLong(rs, "last_operator_id"),
        rs.getString("last_operator_name"),
        timestamp(rs.getTimestamp("created_at")),
        timestamp(rs.getTimestamp("updated_at")),
        timestamp(rs.getTimestamp("completed_at"))
    );
  }

  private String timestamp(Timestamp value) {
    return value == null ? null : value.toLocalDateTime().toString();
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  public record BusinessTodoRow(
      String id,
      String ruleCode,
      String sourceModule,
      String sourceRecordId,
      String sourceKey,
      int occurrenceNo,
      String title,
      String summary,
      String assigneeRole,
      String reviewRole,
      String storeId,
      String storeName,
      String brandName,
      String month,
      int priority,
      String status,
      boolean conditionActive,
      String metadataJson,
      Long lastOperatorId,
      String lastOperatorName,
      String createdAt,
      String updatedAt,
      String completedAt
  ) {
  }

  public record MissingProfitRow(String storeId, String storeName, String brandName) {
  }

  public record PendingExpenseRow(
      String id,
      String storeId,
      String storeName,
      String brandName,
      String month,
      BigDecimal amount,
      String category,
      String reason,
      String status
  ) {
  }

  public record PendingSalaryRow(
      String id,
      String storeId,
      String storeName,
      String brandName,
      String month,
      String employeeName,
      BigDecimal gross,
      String status
  ) {
  }
}
