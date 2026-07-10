package com.storeprofit.system.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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

  public void writePermissionDenied(
      com.storeprofit.system.platform.auth.AuthUser user,
      String action,
      String targetType,
      String targetId,
      String storeId,
      String reason
  ) {
    jdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, reason, created_at
        )
        values (?, ?, ?, 'permission_denied', ?, ?, ?, ?, current_timestamp)
        """,
        user.tenantId(),
        user.id(),
        user.displayName(),
        truncate(requiredText(targetType, "API"), 80),
        truncate(blankToNull(targetId), 120),
        truncate(blankToNull(storeId), 64),
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
