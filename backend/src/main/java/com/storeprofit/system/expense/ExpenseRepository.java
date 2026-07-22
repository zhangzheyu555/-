package com.storeprofit.system.expense;

import com.storeprofit.system.platform.authorization.DataScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class ExpenseRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public ExpenseRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  public List<ExpenseClaimResponse> claims(long tenantId, String month, Long brandId, String storeId, String status) {
    return claims(tenantId, month, brandId, storeId, status, null);
  }

  public List<ExpenseClaimResponse> claims(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      String status,
      DataScope dataScope
  ) {
    StringBuilder sql = new StringBuilder("""
        select ec.id, ec.store_id, s.code as store_code, s.name as store_name,
               s.brand_id, b.name as brand_name, ec.month, ec.expense_date, ec.amount, ec.category,
               ec.reason, ec.status, ec.image_url, ec.submitted_by, ec.reviewed_by,
               ec.reviewed_at,
               (
                 select ol.reason
                 from operation_log ol
                 where ol.tenant_id = ec.tenant_id
                   and ol.target_type = 'expense_claim'
                   and ol.target_id = ec.id
                   and ol.action in ('expense_request_info', 'reimbursement_reject', 'reimbursement_approve')
                 order by ol.created_at desc, ol.id desc
                 limit 1
               ) as review_note
        from expense_claim ec
        join store_branch s on s.id = ec.store_id and s.tenant_id = ec.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where ec.tenant_id = :tenantId
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (month != null && !month.isBlank()) {
      sql.append(" and ec.month = :month");
      params.addValue("month", month);
    }
    if (brandId != null) {
      sql.append(" and s.brand_id = :brandId");
      params.addValue("brandId", brandId);
    }
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and ec.store_id = :storeId");
      params.addValue("storeId", storeId);
    }
    if (status != null && !status.isBlank()) {
      sql.append(" and ec.status = :status");
      params.addValue("status", status.trim());
    }
    appendStoreScope(sql, params, "ec.store_id", dataScope);
    sql.append(" order by ec.month desc, s.code, ec.created_at desc, ec.id");
    return namedJdbcTemplate.query(sql.toString(), params, this::mapClaim);
  }

  public Optional<ExpenseClaimResponse> claim(long tenantId, String id) {
    return claim(tenantId, id, null);
  }

  public Optional<ExpenseClaimResponse> claim(long tenantId, String id, DataScope dataScope) {
    StringBuilder sql = new StringBuilder("""
        select ec.id, ec.store_id, s.code as store_code, s.name as store_name,
               s.brand_id, b.name as brand_name, ec.month, ec.expense_date, ec.amount, ec.category,
               ec.reason, ec.status, ec.image_url, ec.submitted_by, ec.reviewed_by,
               ec.reviewed_at,
               (
                 select ol.reason
                 from operation_log ol
                 where ol.tenant_id = ec.tenant_id
                   and ol.target_type = 'expense_claim'
                   and ol.target_id = ec.id
                   and ol.action in ('expense_request_info', 'reimbursement_reject', 'reimbursement_approve')
                 order by ol.created_at desc, ol.id desc
                 limit 1
               ) as review_note
        from expense_claim ec
        join store_branch s on s.id = ec.store_id and s.tenant_id = ec.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where ec.tenant_id = :tenantId and ec.id = :id
        """);
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("id", id);
    appendStoreScope(sql, params, "ec.store_id", dataScope);
    List<ExpenseClaimResponse> rows = namedJdbcTemplate.query(sql.toString(), params, this::mapClaim);
    return rows.stream().findFirst();
  }

  public Optional<String> claimStoreId(long tenantId, String id) {
    return jdbcTemplate.queryForList(
        "select store_id from expense_claim where tenant_id = ? and id = ?",
        String.class,
        tenantId,
        id
    ).stream().findFirst();
  }

  /** Minimal record used only to enrich authorization-denial audits before the main scoped read. */
  public Optional<ClaimScope> claimScope(long tenantId, String id) {
    return jdbcTemplate.query("""
        select id, store_id, month
        from expense_claim
        where tenant_id = ? and id = ?
        """,
        (rs, rowNum) -> new ClaimScope(rs.getString("id"), rs.getString("store_id"), rs.getString("month")),
        tenantId,
        id
    ).stream().findFirst();
  }

  public boolean hasControlledImageAttachment(long tenantId, String expenseId, String storeId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*)
        from warehouse_attachment
        where tenant_id = ?
          and store_id = ?
          and business_id = ?
          and business_type in ('EXPENSE', 'EXPENSE_CLAIM')
          and lower(coalesce(content_type, '')) like 'image/%'
        """, Integer.class, tenantId, storeId, expenseId);
    return count != null && count > 0;
  }

  public List<ExpenseAttachmentResponse> attachments(long tenantId, String expenseId, String storeId) {
    return jdbcTemplate.query("""
        select id, file_name, content_type, coalesce(file_size, 0) as file_size, uploaded_at
        from warehouse_attachment
        where tenant_id = ?
          and store_id = ?
          and business_id = ?
          and business_type in ('EXPENSE', 'EXPENSE_CLAIM')
        order by uploaded_at, id
        """,
        (rs, rowNum) -> {
          long id = rs.getLong("id");
          String url = "/api/storage/attachments/" + id;
          return new ExpenseAttachmentResponse(
              id,
              rs.getString("file_name"),
              rs.getString("content_type"),
              rs.getLong("file_size"),
              getDateTimeOrNull(rs, "uploaded_at"),
              url,
              url
          );
        },
        tenantId,
        storeId,
        expenseId
    );
  }

  /**
   * Saves a draft only while the existing claim remains editable.  The affected-row result is
   * intentionally exposed so callers can distinguish a concurrent submit/review from a normal
   * validation failure instead of overwriting a newer workflow state.
   */
  public boolean upsert(long tenantId, String id, ExpenseClaimRequest request, String status, Long submittedBy) {
    if (claimExists(tenantId, id)) {
      return update(tenantId, id, request) > 0;
    }
    insert(tenantId, id, request, status, submittedBy);
    return true;
  }

  /** Inserts a deterministic idempotent-create row exactly once; the caller reads the winner on retry. */
  public boolean insertIfAbsent(long tenantId, String id, ExpenseClaimRequest request, String status, Long submittedBy) {
    try {
      insert(tenantId, id, request, status, submittedBy);
      return true;
    } catch (DuplicateKeyException duplicate) {
      return false;
    }
  }

  /**
   * Atomically moves a claim from exactly one expected state.  This is the workflow's optimistic
   * concurrency boundary: a retry or a second reviewer receives zero affected rows and must not
   * create another approval/rejection audit record.
   */
  public int updateStatus(
      long tenantId,
      String id,
      String expectedStatus,
      String status,
      Long submittedBy,
      Long reviewedBy
  ) {
    return jdbcTemplate.update("""
        update expense_claim set
          status = ?,
          submitted_by = coalesce(?, submitted_by),
          reviewed_by = ?,
          reviewed_at = case when ? is null then null else current_timestamp end,
          updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = ?
        """,
        status,
        submittedBy,
        reviewedBy,
        reviewedBy,
        tenantId,
        id,
        expectedStatus
    );
  }

  public int markSupplemented(long tenantId, String id, long submittedBy) {
    return jdbcTemplate.update("""
        update expense_claim set
          status = ?,
          submitted_by = ?,
          reviewed_by = null,
          reviewed_at = null,
          updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = ?
        """,
        ExpenseService.STATUS_PENDING,
        submittedBy,
        tenantId,
        id,
        ExpenseService.STATUS_SUPPLEMENT
    );
  }

  /** Deletes a draft and its primary receipt blobs in one transaction. */
  public int deleteDraft(long tenantId, String id) {
    int deleted = jdbcTemplate.update(
        "delete from expense_claim where tenant_id = ? and id = ? and status = ?",
        tenantId,
        id,
        ExpenseService.STATUS_DRAFT
    );
    if (deleted > 0) {
      jdbcTemplate.update("""
          delete from warehouse_attachment
          where tenant_id = ? and business_id = ? and business_type in ('EXPENSE', 'EXPENSE_CLAIM')
          """, tenantId, id);
    }
    return deleted;
  }

  public int deleteAttachment(long tenantId, String expenseId, String storeId, long attachmentId) {
    return jdbcTemplate.update("""
        delete from warehouse_attachment
        where tenant_id = ? and id = ? and store_id = ? and business_id = ?
          and business_type in ('EXPENSE', 'EXPENSE_CLAIM')
        """, tenantId, attachmentId, storeId, expenseId);
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

  public void logAction(
      long tenantId,
      long operatorId,
      String operatorName,
      String action,
      String id,
      String storeId,
      String month,
      String reason
  ) {
    jdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, reason, created_at
        )
        values (?, ?, ?, ?, 'expense_claim', ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        operatorId,
        operatorName,
        action,
        id,
        storeId,
        month,
        reason
    );
  }

  private boolean claimExists(long tenantId, String id) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from expense_claim where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        id
    );
    return count != null && count > 0;
  }

  private void insert(long tenantId, String id, ExpenseClaimRequest request, String status, Long submittedBy) {
    jdbcTemplate.update("""
        insert into expense_claim(
          id, tenant_id, store_id, month, expense_date, amount, category, reason, status,
          image_url, submitted_by, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        id,
        tenantId,
        request.storeId(),
        request.month(),
        java.sql.Date.valueOf(request.expenseDate()),
        amount(request.amount()),
        blankToNull(request.category()),
        blankToNull(request.reason()),
        status,
        blankToNull(request.imageUrl()),
        submittedBy
    );
  }

  private int update(long tenantId, String id, ExpenseClaimRequest request) {
    return jdbcTemplate.update("""
        update expense_claim set
          store_id = ?,
          month = ?,
          expense_date = ?,
          amount = ?,
          category = ?,
          reason = ?,
          image_url = ?,
          reviewed_by = null,
          reviewed_at = null,
          updated_at = current_timestamp
        where tenant_id = ? and id = ? and status in ('草稿', '待补资料', '已驳回')
        """,
        request.storeId(),
        request.month(),
        java.sql.Date.valueOf(request.expenseDate()),
        amount(request.amount()),
        blankToNull(request.category()),
        blankToNull(request.reason()),
        blankToNull(request.imageUrl()),
        tenantId,
        id
    );
  }

  private ExpenseClaimResponse mapClaim(ResultSet rs, int rowNum) throws SQLException {
    return new ExpenseClaimResponse(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_code"),
        rs.getString("store_name"),
        getLongOrNull(rs, "brand_id"),
        rs.getString("brand_name"),
        rs.getString("month"),
        amount(rs.getBigDecimal("amount")),
        rs.getString("category"),
        rs.getString("reason"),
        rs.getString("status"),
        rs.getString("image_url"),
        getLongOrNull(rs, "submitted_by"),
        getLongOrNull(rs, "reviewed_by"),
        getDateTimeOrNull(rs, "reviewed_at"),
        getDateOrNull(rs, "expense_date"),
        rs.getString("review_note")
    );
  }

  private Long getLongOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private LocalDateTime getDateTimeOrNull(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toLocalDateTime();
  }

  private LocalDate getDateOrNull(ResultSet rs, String column) throws SQLException {
    java.sql.Date value = rs.getDate(column);
    return value == null ? null : value.toLocalDate();
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private void appendStoreScope(
      StringBuilder sql,
      MapSqlParameterSource params,
      String storeColumn,
      DataScope dataScope
  ) {
    if (dataScope == null || dataScope.allowsAllStores()) {
      return;
    }
    Collection<String> storeIds = dataScope.storeIds();
    if (dataScope.deniesStoreAccess() || storeIds == null || storeIds.isEmpty()) {
      sql.append(" and 1 = 0");
      return;
    }
    sql.append(" and ").append(storeColumn).append(" in (:scopeStoreIds)");
    params.addValue("scopeStoreIds", storeIds);
  }

  public record ClaimScope(String id, String storeId, String month) {
  }
}
