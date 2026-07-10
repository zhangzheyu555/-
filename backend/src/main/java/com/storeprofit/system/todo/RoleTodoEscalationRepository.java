package com.storeprofit.system.todo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoleTodoEscalationRepository {
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public RoleTodoEscalationRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  public void save(RoleTodoEscalationRecord record) {
    namedJdbcTemplate.update("""
        insert into todo_escalation(
          id, tenant_id, source_role, source_module, source_id, source_todo_id,
          reason, severity, reported_by_user_id, reported_by_name, boss_todo_id, status, created_at
        ) values (
          :id, :tenantId, :sourceRole, :sourceModule, :sourceId, :sourceTodoId,
          :reason, :severity, :reportedByUserId, :reportedByName, :bossTodoId, :status, current_timestamp
        )
        """,
        new MapSqlParameterSource()
            .addValue("id", record.id())
            .addValue("tenantId", record.tenantId())
            .addValue("sourceRole", record.sourceRole())
            .addValue("sourceModule", record.sourceModule())
            .addValue("sourceId", record.sourceId())
            .addValue("sourceTodoId", record.sourceTodoId())
            .addValue("reason", record.reason())
            .addValue("severity", record.severity())
            .addValue("reportedByUserId", record.reportedByUserId())
            .addValue("reportedByName", record.reportedByName())
            .addValue("bossTodoId", record.bossTodoId())
            .addValue("status", record.status())
    );
  }

  public List<RoleTodoEscalationRow> openEscalations(long tenantId, int limit) {
    return namedJdbcTemplate.query("""
        select id, source_role, source_module, source_id, source_todo_id,
               reason, severity, reported_by_name, boss_todo_id, created_at
        from todo_escalation
        where tenant_id = :tenantId and status = 'OPEN'
        order by created_at desc, id
        limit :limit
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", limit),
        this::mapEscalation
    );
  }

  public List<RoleTodoEscalationRow> resolvedEscalations(long tenantId, int limit) {
    return namedJdbcTemplate.query("""
        select id, source_role, source_module, source_id, source_todo_id,
               reason, severity, reported_by_name, boss_todo_id, created_at
        from todo_escalation
        where tenant_id = :tenantId and status = 'RESOLVED'
        order by resolved_at desc, created_at desc, id
        limit :limit
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", limit),
        this::mapEscalation
    );
  }

  public Set<String> openSourceTodoIds(long tenantId, String sourceRole) {
    List<String> ids = namedJdbcTemplate.queryForList("""
        select source_todo_id
        from todo_escalation
        where tenant_id = :tenantId
          and source_role = :sourceRole
          and status = 'OPEN'
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("sourceRole", sourceRole),
        String.class
    );
    return Set.copyOf(ids);
  }

  public Set<String> resolvedSourceTodoIds(long tenantId) {
    List<String> ids = namedJdbcTemplate.queryForList("""
        select source_todo_id
        from todo_escalation
        where tenant_id = :tenantId
          and status = 'RESOLVED'
        """,
        new MapSqlParameterSource("tenantId", tenantId),
        String.class
    );
    return Set.copyOf(ids);
  }

  public Optional<RoleTodoEscalationRow> findOpenByBossTodoId(long tenantId, String bossTodoId) {
    List<RoleTodoEscalationRow> rows = namedJdbcTemplate.query("""
        select id, source_role, source_module, source_id, source_todo_id,
               reason, severity, reported_by_name, boss_todo_id, created_at
        from todo_escalation
        where tenant_id = :tenantId
          and boss_todo_id = :bossTodoId
          and status = 'OPEN'
        limit 1
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("bossTodoId", bossTodoId),
        this::mapEscalation
    );
    return rows.stream().findFirst();
  }

  public void resolve(long tenantId, String escalationId) {
    namedJdbcTemplate.update("""
        update todo_escalation
        set status = 'RESOLVED',
            resolved_at = current_timestamp
        where tenant_id = :tenantId and id = :id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", escalationId)
    );
  }

  private RoleTodoEscalationRow mapEscalation(ResultSet rs, int rowNum) throws SQLException {
    Timestamp createdAt = rs.getTimestamp("created_at");
    return new RoleTodoEscalationRow(
        rs.getString("id"),
        rs.getString("source_role"),
        rs.getString("source_module"),
        rs.getString("source_id"),
        rs.getString("source_todo_id"),
        rs.getString("reason"),
        rs.getString("severity"),
        rs.getString("reported_by_name"),
        rs.getString("boss_todo_id"),
        createdAt == null ? null : createdAt.toLocalDateTime().toString()
    );
  }

  public record RoleTodoEscalationRecord(
      String id,
      long tenantId,
      String sourceRole,
      String sourceModule,
      String sourceId,
      String sourceTodoId,
      String reason,
      String severity,
      long reportedByUserId,
      String reportedByName,
      String bossTodoId,
      String status
  ) {
  }

  public record RoleTodoEscalationRow(
      String id,
      String sourceRole,
      String sourceModule,
      String sourceId,
      String sourceTodoId,
      String reason,
      String severity,
      String reportedByName,
      String bossTodoId,
      String createdAt
  ) {
  }
}
