package com.storeprofit.system.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AuditRepository {
  private final JdbcTemplate jdbcTemplate;

  public AuditRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<OperationLogResponse> logs(long tenantId, int limit) {
    return jdbcTemplate.query("""
        select id, operator_id, operator_name, action, target_type, target_id,
               store_id, month, reason, date_format(created_at, '%Y-%m-%d %H:%i:%s') as created_at
        from operation_log
        where tenant_id = ?
        order by created_at desc, id desc
        limit ?
        """, this::mapLog, tenantId, Math.max(1, Math.min(limit, 500)));
  }

  public OperationLogQueryResponse search(long tenantId, OperationLogQuery query) {
    FilteredAuditQuery filtered = buildSearchQuery(tenantId, query);
    Long matched = jdbcTemplate.queryForObject(
        "select count(*) " + filtered.fromAndWhere(), Long.class, filtered.params().toArray());
    long total = matched == null ? 0 : matched;
    int totalPages = Math.max(1, (int) Math.ceil((double) total / query.pageSize()));
    int page = Math.min(query.page(), totalPages);

    ArrayList<Object> pageParams = new ArrayList<>(filtered.params());
    pageParams.add(query.pageSize());
    pageParams.add((page - 1) * query.pageSize());
    List<OperationLogResponse> rows = jdbcTemplate.query("""
        select id, operator_id, operator_name, action, target_type, target_id,
               store_id, month, reason, date_format(created_at, '%Y-%m-%d %H:%i:%s') as created_at
        """ + filtered.fromAndWhere() + """
         order by created_at desc, id desc
         limit ? offset ?
        """, this::mapLog, pageParams.toArray());

    return new OperationLogQueryResponse(
        List.copyOf(rows),
        total,
        page,
        query.pageSize(),
        totalPages,
        filterOptions(tenantId, "operator_name"),
        filterOptions(tenantId, "action")
    );
  }

  public void writeLog(com.storeprofit.system.platform.auth.AuthUser user, AuditLogRequest request) {
    jdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, before_json, after_json, reason, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        user.tenantId(),
        user.id(),
        user.displayName(),
        truncate(requiredText(request == null ? null : request.action(), "前端操作"), 80),
        truncate(requiredText(request == null ? null : request.targetType(), "前端业务"), 80),
        truncate(blankToNull(request == null ? null : request.targetId()), 120),
        truncate(blankToNull(request == null ? null : request.storeId()), 64),
        truncate(blankToNull(request == null ? null : request.month()), 7),
        blankToNull(request == null ? null : request.beforeJson()),
        blankToNull(request == null ? null : request.afterJson()),
        truncate(blankToNull(request == null ? null : request.reason()), 255)
    );
  }

  /**
   * A denial normally occurs inside a business write transaction. Persist it in a separate
   * transaction so the expected rollback of the denied operation cannot erase its audit trail.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void writePermissionDenied(
      com.storeprofit.system.platform.auth.AuthUser user,
      String action,
      String targetType,
      String targetId,
      String storeId,
      String reason
  ) {
    writePermissionDenied(user, action, targetType, targetId, storeId, null, reason);
  }

  /** Preserves the requested accounting month when a finance write is rejected before execution. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void writePermissionDenied(
      com.storeprofit.system.platform.auth.AuthUser user,
      String action,
      String targetType,
      String targetId,
      String storeId,
      String month,
      String reason
  ) {
    jdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, reason, created_at
        )
        values (?, ?, ?, 'permission_denied', ?, ?, ?, ?, ?, current_timestamp)
        """,
        user.tenantId(),
        user.id(),
        user.displayName(),
        truncate(requiredText(targetType, "API"), 80),
        truncate(blankToNull(targetId), 120),
        truncate(blankToNull(storeId), 64),
        truncate(blankToNull(month), 7),
        truncate(requiredText(action + "：" + reason, "权限拒绝"), 255)
    );
  }

  private OperationLogResponse mapLog(ResultSet rs, int rowNum) throws SQLException {
    long operatorIdValue = rs.getLong("operator_id");
    Long operatorId = rs.wasNull() ? null : operatorIdValue;
    return new OperationLogResponse(
        rs.getLong("id"),
        operatorId,
        rs.getString("operator_name"),
        rs.getString("action"),
        rs.getString("target_type"),
        rs.getString("target_id"),
        rs.getString("store_id"),
        rs.getString("month"),
        rs.getString("reason"),
        rs.getString("created_at")
    );
  }

  private FilteredAuditQuery buildSearchQuery(long tenantId, OperationLogQuery query) {
    StringBuilder sql = new StringBuilder(" from operation_log where tenant_id = ?");
    ArrayList<Object> params = new ArrayList<>();
    params.add(tenantId);

    if (query.keyword() != null) {
      sql.append("""
           and locate(?, lower(concat(
             coalesce(operator_name, ''), ' ', coalesce(action, ''), ' ',
             coalesce(target_type, ''), ' ', coalesce(target_id, ''), ' ',
             coalesce(store_id, ''), ' ', coalesce(month, ''), ' ', coalesce(reason, '')
           ))) > 0
          """);
      params.add(query.keyword().toLowerCase(java.util.Locale.ROOT));
    }
    if (query.operatorName() != null) {
      sql.append(" and operator_name = ?");
      params.add(query.operatorName());
    }
    if (query.action() != null) {
      sql.append(" and action = ?");
      params.add(query.action());
    }
    if (OperationLogQuery.STORE_SCOPE_STORE.equals(query.storeScope())) {
      sql.append(" and store_id = ?");
      params.add(query.storeId());
    } else if (OperationLogQuery.STORE_SCOPE_GLOBAL.equals(query.storeScope())) {
      sql.append(" and (store_id is null or trim(store_id) = '')");
    }
    if (query.startDate() != null) {
      sql.append(" and created_at >= ? and created_at < ?");
      params.add(Timestamp.valueOf(query.startDate().atStartOfDay()));
      params.add(Timestamp.valueOf(query.endDate().plusDays(1).atStartOfDay()));
    }
    return new FilteredAuditQuery(sql.toString(), params);
  }

  private List<String> filterOptions(long tenantId, String column) {
    if (!"operator_name".equals(column) && !"action".equals(column)) {
      throw new IllegalArgumentException("Unsupported audit filter column");
    }
    return jdbcTemplate.queryForList("""
        select distinct trim(%s)
        from operation_log
        where tenant_id = ?
          and %s is not null
          and trim(%s) <> ''
        order by trim(%s)
        """.formatted(column, column, column, column), String.class, tenantId);
  }

  private record FilteredAuditQuery(String fromAndWhere, ArrayList<Object> params) {
  }

  private String requiredText(String value, String fallback) {
    String normalized = blankToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String truncate(String value, int length) {
    if (value == null || value.length() <= length) {
      return value;
    }
    return value.substring(0, length);
  }
}
