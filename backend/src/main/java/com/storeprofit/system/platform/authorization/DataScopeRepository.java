package com.storeprofit.system.platform.authorization;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DataScopeRepository {
  private final JdbcTemplate jdbcTemplate;

  public DataScopeRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<DataScopeAssignmentRow> assignmentsForUser(long tenantId, long userId) {
    return jdbcTemplate.query("""
        select domain_code, scope_type, scope_value_json
        from user_data_scope
        where tenant_id = ? and user_id = ?
        order by domain_code
        """, (rs, rowNum) -> new DataScopeAssignmentRow(
        rs.getString("domain_code"),
        rs.getString("scope_type"),
        rs.getString("scope_value_json")
    ), tenantId, userId);
  }

  public List<String> enabledWarehouseIds(long tenantId) {
    return jdbcTemplate.query(
        "select id from warehouse_facility where tenant_id = ? and enabled = 1 order by id",
        (rs, rowNum) -> Long.toString(rs.getLong(1)),
        tenantId
    );
  }

  @Transactional
  public void replaceAssignments(
      long tenantId,
      long userId,
      List<DataScopeAssignmentRow> assignments,
      Long actorId
  ) {
    jdbcTemplate.update(
        "delete from user_data_scope where tenant_id = ? and user_id = ?",
        tenantId,
        userId
    );
    if (assignments == null) {
      return;
    }
    for (DataScopeAssignmentRow assignment : assignments) {
      jdbcTemplate.update("""
          insert into user_data_scope(
            tenant_id, user_id, domain_code, scope_type, scope_value_json,
            created_by, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """,
          tenantId,
          userId,
          assignment.domainCode(),
          assignment.scopeType(),
          assignment.scopeValueJson(),
          actorId
      );
    }
  }

  public record DataScopeAssignmentRow(
      String domainCode,
      String scopeType,
      String scopeValueJson
  ) {
  }
}
