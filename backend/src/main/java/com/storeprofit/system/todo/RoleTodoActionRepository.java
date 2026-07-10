package com.storeprofit.system.todo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoleTodoActionRepository {
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public RoleTodoActionRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  public void saveAction(RoleTodoActionRecord record) {
    namedJdbcTemplate.update("""
        insert into todo_action(
          id, tenant_id, todo_id, action_type, status, note,
          actor_user_id, actor_name, actor_role, created_at
        ) values (
          :id, :tenantId, :todoId, :actionType, :status, :note,
          :actorUserId, :actorName, :actorRole, current_timestamp
        )
        """,
        new MapSqlParameterSource()
            .addValue("id", record.id())
            .addValue("tenantId", record.tenantId())
            .addValue("todoId", record.todoId())
            .addValue("actionType", record.actionType())
            .addValue("status", record.status())
            .addValue("note", record.note())
            .addValue("actorUserId", record.actorUserId())
            .addValue("actorName", record.actorName())
            .addValue("actorRole", record.actorRole())
    );
  }

  public void saveAttachment(RoleTodoAttachmentRecord record) {
    namedJdbcTemplate.update("""
        insert into todo_action_attachment(
          id, tenant_id, action_id, todo_id, file_name, content_type,
          size_bytes, content, created_at
        ) values (
          :id, :tenantId, :actionId, :todoId, :fileName, :contentType,
          :sizeBytes, :content, current_timestamp
        )
        """,
        new MapSqlParameterSource()
            .addValue("id", record.id())
            .addValue("tenantId", record.tenantId())
            .addValue("actionId", record.actionId())
            .addValue("todoId", record.todoId())
            .addValue("fileName", record.fileName())
            .addValue("contentType", record.contentType())
            .addValue("sizeBytes", record.sizeBytes())
            .addValue("content", record.content())
    );
  }

  public void saveOperationLog(RoleTodoOperationLogRecord record) {
    namedJdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, reason, created_at
        ) values (
          :tenantId, :operatorId, :operatorName, :action, :targetType, :targetId,
          :storeId, :month, :reason, current_timestamp
        )
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", record.tenantId())
            .addValue("operatorId", record.operatorId())
            .addValue("operatorName", record.operatorName())
            .addValue("action", record.action())
            .addValue("targetType", record.targetType())
            .addValue("targetId", record.targetId())
            .addValue("storeId", record.storeId())
            .addValue("month", record.month())
            .addValue("reason", record.reason())
    );
  }

  public Map<String, RoleTodoActionSummary> completedActions(long tenantId) {
    List<RoleTodoActionSummary> rows = namedJdbcTemplate.query("""
        select todo_id, action_type, status, note, actor_name, actor_role, created_at
        from todo_action
        where tenant_id = :tenantId and status = 'DONE'
        order by created_at desc, id desc
        """,
        new MapSqlParameterSource("tenantId", tenantId),
        this::mapSummary
    );
    Map<String, RoleTodoActionSummary> byTodoId = new LinkedHashMap<>();
    for (RoleTodoActionSummary row : rows) {
      byTodoId.putIfAbsent(row.todoId(), row);
    }
    return byTodoId;
  }

  public List<RoleTodoActionHistory> history(long tenantId, String todoId) {
    return namedJdbcTemplate.query("""
        select id, action_type, status, note, actor_name, actor_role, created_at
        from todo_action
        where tenant_id = :tenantId and todo_id = :todoId
        order by created_at asc, id asc
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("todoId", todoId),
        (rs, rowNum) -> new RoleTodoActionHistory(
            rs.getString("id"),
            rs.getString("action_type"),
            rs.getString("status"),
            rs.getString("note"),
            rs.getString("actor_name"),
            rs.getString("actor_role"),
            timestamp(rs.getTimestamp("created_at"))
        )
    );
  }

  public List<RoleTodoAttachmentSummary> attachments(long tenantId, String actionId, String todoId) {
    return namedJdbcTemplate.query("""
        select id, file_name, content_type, size_bytes
        from todo_action_attachment
        where tenant_id = :tenantId and action_id = :actionId and todo_id = :todoId
        order by created_at asc, id asc
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("actionId", actionId)
            .addValue("todoId", todoId),
        (rs, rowNum) -> new RoleTodoAttachmentSummary(
            rs.getString("id"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes")
        )
    );
  }

  public java.util.Optional<RoleTodoAttachmentContent> attachment(long tenantId, String todoId, String attachmentId) {
    List<RoleTodoAttachmentContent> rows = namedJdbcTemplate.query("""
        select id, file_name, content_type, size_bytes, content
        from todo_action_attachment
        where tenant_id = :tenantId and todo_id = :todoId and id = :attachmentId
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("todoId", todoId)
            .addValue("attachmentId", attachmentId),
        (rs, rowNum) -> new RoleTodoAttachmentContent(
            rs.getString("id"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getBytes("content")
        )
    );
    return rows.stream().findFirst();
  }

  private RoleTodoActionSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
    Timestamp createdAt = rs.getTimestamp("created_at");
    return new RoleTodoActionSummary(
        rs.getString("todo_id"),
        rs.getString("action_type"),
        rs.getString("status"),
        rs.getString("note"),
        rs.getString("actor_name"),
        rs.getString("actor_role"),
        createdAt == null ? null : createdAt.toLocalDateTime().toString()
    );
  }

  private String timestamp(Timestamp value) {
    return value == null ? null : value.toLocalDateTime().toString();
  }

  public record RoleTodoActionRecord(
      String id,
      long tenantId,
      String todoId,
      String actionType,
      String status,
      String note,
      Long actorUserId,
      String actorName,
      String actorRole
  ) {
  }

  public record RoleTodoAttachmentRecord(
      String id,
      long tenantId,
      String actionId,
      String todoId,
      String fileName,
      String contentType,
      long sizeBytes,
      byte[] content
  ) {
  }

  public record RoleTodoOperationLogRecord(
      long tenantId,
      Long operatorId,
      String operatorName,
      String action,
      String targetType,
      String targetId,
      String storeId,
      String month,
      String reason
  ) {
  }

  public record RoleTodoActionSummary(
      String todoId,
      String actionType,
      String status,
      String note,
      String actorName,
      String actorRole,
      String createdAt
  ) {
  }

  public record RoleTodoActionHistory(
      String id,
      String actionType,
      String status,
      String note,
      String actorName,
      String actorRole,
      String createdAt
  ) {
  }

  public record RoleTodoAttachmentSummary(
      String id,
      String fileName,
      String contentType,
      long sizeBytes
  ) {
  }

  public record RoleTodoAttachmentContent(
      String id,
      String fileName,
      String contentType,
      long sizeBytes,
      byte[] content
  ) {
  }
}
