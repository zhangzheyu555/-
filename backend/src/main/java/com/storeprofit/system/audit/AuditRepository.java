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
}
